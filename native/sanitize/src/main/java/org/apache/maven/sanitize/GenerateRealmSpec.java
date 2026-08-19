/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.sanitize;

import javax.inject.Inject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

/**
 * Maven-build-side replacement for the bash prep pipeline of build-nmvn-prebuilt.sh (step 2 of the
 * native-build-tools migration): given a list of plugin entries {@code g:a:v[|canonical-deps]},
 * it produces everything the native-image invocation needs beyond the static flags already baked
 * into the sidecar jar's {@code META-INF/native-image} properties:
 *
 * <ol>
 * <li>Resolves each plugin's runtime classpath IN-PROCESS through the host Maven's injected
 *     {@code RepositorySystem}, with the same CollectRequest shape stock Maven's
 *     DefaultPluginDependenciesResolver uses for plugin realms (plugin as root, per-plugin
 *     dependencies direct + managed, exclusions honored, runtime filter). The injected session
 *     inherits the running build's mirrors/proxies/local repo/offline configuration.</li>
 * <li>Drops jars whose groupId:artifactId maven-core EXPORTS to plugin realms (read from the dist
 *     maven-core jar's {@code META-INF/maven/extension.xml}) — stock Maven excludes exported
 *     artifacts from plugin realms the same way.</li>
 * <li>kotlin-compiler-embeddable special case: injects kotlinx-serialization-core-jvm (see the
 *     comment at {@link #resolveKotlinxSerialization}).</li>
 * <li>Writes the realm spec ({@code g:a:v[|deps]=jar{pathSep}jar...}, one line per plugin,
 *     LF-terminated) with the same validation the bash/python pipeline performs.</li>
 * <li>Forks the {@link SanitizeRealmJars} JVM (attribute strip + JVMCI link probe) over the spec,
 *     which rewrites it in place and produces {@code unlinkable.txt}.</li>
 * </ol>
 *
 * <p>Usage (all flags may repeat where noted):
 * <pre>
 *   --maven-home &lt;dist dir&gt;         required
 *   --work &lt;dir&gt;                    required, scratch + outputs (spec.txt, unlinkable.txt, ...)
 *   --extra-cp &lt;path&gt;               repeatable: sidecar jar, jvm-channel jar, ... (image classpath)
 *   --plugins &lt;entries&gt;             optional; entries separated by whitespace/newlines, commas
 *                                     also accepted when no entry carries a '|' dependency key;
 *                                     the entry {@code @default-lifecycle} expands to the dist's
 *                                     default lifecycle plugin set (see defaultLifecyclePlugins)
 *   --plugins-file &lt;path&gt;           optional; newline-separated entries
 * </pre>
 *
 * <p>With no plugins at all the resolution/sanitize steps are skipped (baseline image, parity with
 * the script's no-args mode); {@code spec.txt} and {@code unlinkable.txt} are still written, EMPTY,
 * because the pom references them unconditionally and PrebuiltPluginRealms reads a blank spec as
 * "registry empty".
 */
@Mojo(name = "generate-realm-spec")
public final class GenerateRealmSpec extends AbstractMojo {

    /** The HOST Maven's resolver engine — the same instance the running build resolves with. */
    @Inject
    RepositorySystem repoSystem;

    // Current maven project context (needed to fetch remote repositories)
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The running build's session: local repo, mirrors, proxies, auth, offline — all inherited. */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    RepositorySystemSession repoSession;

    /**
     * The PLUGIN repositories of the project being built — what stock Maven's
     * DefaultPluginDependenciesResolver resolves plugin realms against.
     */
    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    List<RemoteRepository> remoteRepositories;

    @Parameter
    Path mavenHome;

    @Parameter
    Path work;

    @Parameter
    List<String> extraCp = new ArrayList<>();

    @Parameter
    List<String> plugins;

    @Parameter
    Path pluginsFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            executeImpl();
        } catch (Exception ex) {
            throw new MojoExecutionException(ex);
        }
    }

    private void executeImpl() throws Exception {
        var pluginsInput = new StringBuilder();
        if (plugins != null) {
            for (var arg : plugins) {
                if (arg != null) {
                    pluginsInput.append('\n').append(arg);
                }
            }
        }
        if (pluginsFile != null && !pluginsFile.toString().isEmpty()) {
            // Fail LOUDLY on a missing file: silently skipping would bake a baseline image with
            // nothing in it. Classic cause: a relative path — Maven resolves relative Path
            // parameters against the MODULE basedir (native/launcher), not the invocation dir.
            if (!Files.isRegularFile(pluginsFile)) {
                throw new IllegalArgumentException("pluginsFile does not exist: " + pluginsFile
                        + " (relative paths resolve against the module basedir — pass an absolute path)");
            }
            pluginsInput.append('\n').append(Files.readString(pluginsFile));
        }

        if (mavenHome == null || work == null) {
            throw new IllegalArgumentException("mavenHome and work are required");
        }
        if (!Files.isDirectory(mavenHome.resolve("lib"))) {
            throw new IllegalStateException("Not a Maven dist (no lib/): " + mavenHome
                    + " — build the distribution first (mvn package -DskipTests from the repo root)");
        }
        Files.createDirectories(work);

        List<String> entries = new ArrayList<>();
        for (String entry : splitPluginEntries(pluginsInput.toString())) {
            if (DEFAULT_LIFECYCLE_SENTINEL.equals(entry)) {
                entries.addAll(defaultLifecyclePlugins(mavenHome));
            } else {
                entries.add(entry);
            }
        }
        // The resolved entry list, as a record (and so thin drivers can publish a .plugins file
        // without re-deriving it — the @default-lifecycle sentinel is expanded here).
        Files.write(work.resolve("plugins.txt"), entries, StandardCharsets.UTF_8);
        // Both files are ALWAYS written, even with nothing to bake: the pom passes their paths as
        // static -Dnmvn.prebuilt.* buildArgs, and PrebuiltPluginRealms treats a BLANK spec as
        // "registry empty" (and an empty unlinkable list as no drops) — the baseline image.
        Path spec = work.resolve("spec.txt");
        Path unlinkable = work.resolve("unlinkable.txt");
        Files.writeString(spec, "", StandardCharsets.UTF_8);
        Files.writeString(unlinkable, "", StandardCharsets.UTF_8);
        if (entries.isEmpty()) {
            System.err.println("GenerateRealmSpec: WARNING — no plugins to bake; the image can run"
                    + " pluginless commands natively, everything else goes to the JVM fallback.");
        } else {
            Set<String> exported = exportedArtifacts(mavenHome);
            List<String> specLines = new ArrayList<>();
            for (String entry : entries) {
                specLines.add(resolveEntry(entry, exported));
            }
            Files.writeString(spec, String.join("\n", specLines) + "\n", StandardCharsets.UTF_8);
            System.err.println("GenerateRealmSpec: wrote " + specLines.size() + " realm(s) to " + spec);
            runSanitize(imageClasspath(mavenHome, toExtraCp()), spec, work, unlinkable);
        }
    }

    /**
     * Entries are separated by whitespace/newlines; commas are ALSO separators unless any entry
     * carries a '|' dependency key — the canonical dependency encoding itself joins dependencies
     * with commas (see PrebuiltPluginRealms.dependencyKey), so comma-splitting would cut through it.
     */
    static List<String> splitPluginEntries(String input) {
        String separators = input.indexOf('|') >= 0 ? "[\\s\\n]+" : "[,\\s\\n]+";
        List<String> entries = new ArrayList<>();
        for (String part : input.split(separators)) {
            String t = part.trim().replace("\r", "");
            if (!t.isEmpty()) {
                entries.add(t);
            }
        }
        return entries;
    }

    /** Plugin-list entry that expands to the dist's default lifecycle plugin set. */
    static final String DEFAULT_LIFECYCLE_SENTINEL = "@default-lifecycle";

    /**
     * The default 'clean' + 'default' lifecycle bindings for jar packaging — the plugin versions
     * a project WITHOUT a version-pinning parent requests. NOT hardcoded: read from the compiled
     * version constants of the DIST's maven-core (the ConstantValue attributes of the fields
     * below, parsed with the ClassFile API — no class loading, no second Maven in this VM), so
     * the baked versions always match the binary actually embedded in the image, even if the
     * working tree has moved on since the dist was built. Same source of truth the retired
     * build-nmvn-generic.sh read via javap.
     */
    private static List<String> defaultLifecyclePlugins(Path mavenHome) throws Exception {
        String provider = "org/apache/maven/lifecycle/providers/packaging/AbstractLifecycleMappingProvider.class";
        String cleanLifecycle = "org/apache/maven/internal/impl/DefaultLifecycleRegistry$CleanLifecycle.class";
        List<String> plugins = new ArrayList<>();
        try (JarFile jar = new JarFile(coreJar(mavenHome).toFile())) {
            plugins.add("org.apache.maven.plugins:maven-clean-plugin:"
                    + stringConstant(jar, cleanLifecycle, "MAVEN_CLEAN_PLUGIN_VERSION"));
            for (String[] plugin : new String[][] {
                {"maven-resources-plugin", "RESOURCES_PLUGIN_VERSION"},
                {"maven-compiler-plugin", "COMPILER_PLUGIN_VERSION"},
                {"maven-surefire-plugin", "SUREFIRE_PLUGIN_VERSION"},
                {"maven-jar-plugin", "JAR_PLUGIN_VERSION"},
                {"maven-install-plugin", "INSTALL_PLUGIN_VERSION"},
                {"maven-deploy-plugin", "DEPLOY_PLUGIN_VERSION"},
            }) {
                plugins.add("org.apache.maven.plugins:" + plugin[0] + ":" + stringConstant(jar, provider, plugin[1]));
            }
        }
        System.err.println("GenerateRealmSpec: " + DEFAULT_LIFECYCLE_SENTINEL + " (from the dist's maven-core):");
        for (String plugin : plugins) {
            System.err.println("    " + plugin);
        }
        return plugins;
    }

    /** A compiled {@code static final String} constant, read from the class file's ConstantValue. */
    private static String stringConstant(JarFile jar, String classEntry, String fieldName) throws Exception {
        ZipEntry entry = jar.getEntry(classEntry);
        if (entry == null) {
            throw new IllegalStateException(
                    "No " + classEntry + " in " + jar.getName() + " (class moved? update defaultLifecyclePlugins)");
        }
        var classModel = java.lang.classfile.ClassFile.of()
                .parse(jar.getInputStream(entry).readAllBytes());
        for (var field : classModel.fields()) {
            if (field.fieldName().equalsString(fieldName)) {
                var constant = field.findAttribute(java.lang.classfile.Attributes.constantValue());
                if (constant.isPresent() && constant.get().constant().constantValue() instanceof String version) {
                    return version;
                }
            }
        }
        throw new IllegalStateException("No String constant " + fieldName + " in " + classEntry
                + " (constant renamed/moved? update defaultLifecyclePlugins)");
    }

    private static Path coreJar(Path mavenHome) throws Exception {
        try (var jars = Files.newDirectoryStream(mavenHome.resolve("lib"), "maven-core-*.jar")) {
            var it = jars.iterator();
            if (!it.hasNext()) {
                throw new IllegalStateException("No maven-core jar under " + mavenHome.resolve("lib"));
            }
            return it.next();
        }
    }

    /** The groupId:artifactId set maven-core exports to every plugin realm (from extension.xml). */
    private static Set<String> exportedArtifacts(Path mavenHome) throws Exception {
        Set<String> exported = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(coreJar(mavenHome).toFile())) {
            ZipEntry entry = jar.getEntry("META-INF/maven/extension.xml");
            String xml = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<exportedArtifact>\\s*([^<\\s]+)\\s*</exportedArtifact>")
                    .matcher(xml);
            while (m.find()) {
                exported.add(m.group(1));
            }
        }
        return exported;
    }

    /** Resolves one {@code g:a:v[|deps]} entry to its realm jars; returns the spec line. */
    private String resolveEntry(String entry, Set<String> exported) throws Exception {
        String gav = entry.split("\\|", 2)[0];
        String depKey = entry.contains("|") ? entry.substring(entry.indexOf('|') + 1) : "";
        String[] g = gav.split(":");
        if (g.length != 3) {
            throw new IllegalArgumentException("Expected groupId:artifactId:version[|deps], got: " + entry);
        }
        System.err.println(">>> Resolving runtime classpath for " + gav
                + (depKey.isEmpty() ? "" : " (+ per-plugin deps: " + depKey + ")"));
        List<String> resolved =
                resolveRuntimeClasspath(new DefaultArtifact(g[0], g[1], "jar", g[2]), decodeDependencies(depKey));

        List<String> jars = new ArrayList<>();
        boolean kotlinEmbeddable = false;
        for (String jar : resolved) {
            String ga = repoLayoutGa(jar);
            if (ga != null && exported.contains(ga)) {
                System.err.println("    excluded (provided by core): " + ga);
                continue;
            }
            kotlinEmbeddable |= Path.of(jar).getFileName().toString().startsWith("kotlin-compiler-embeddable-");
            jars.add(jar);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("Could not resolve runtime classpath for " + gav);
        }
        if (kotlinEmbeddable) {
            for (String serJar : resolveKotlinxSerialization()) {
                if (!jars.contains(serJar)) {
                    jars.add(serJar);
                    System.err.println("    + " + serJar);
                }
            }
        }
        return specLine(gav, depKey, jars);
    }

    /**
     * One in-process resolver call replacing the forked dist {@code mvn dependency:build-classpath}
     * of earlier iterations: the SAME CollectRequest shape stock Maven's
     * DefaultPluginDependenciesResolver uses when it builds a plugin realm — plugin artifact as
     * root, per-plugin dependencies direct AND managed, runtime classpath filter. The injected
     * session inherits the running build's mirrors/proxies/auth/local repo/offline verbatim, and
     * resolution failures surface as typed exceptions naming the missing artifact.
     */
    private List<String> resolveRuntimeClasspath(DefaultArtifact root, List<Dependency> extraDependencies)
            throws Exception {
        CollectRequest collect = new CollectRequest();
        collect.setRoot(new Dependency(root, JavaScopes.RUNTIME));
        collect.setRepositories(remoteRepositories);
        for (Dependency dependency : extraDependencies) {
            // BOTH: direct adds the jars to the realm, managed overrides versions inside the
            // plugin's own tree — the two effects Maven's per-plugin <dependencies> have.
            collect.addDependency(dependency);
            collect.addManagedDependency(dependency);
        }
        DependencyRequest request =
                new DependencyRequest(collect, DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
        List<String> jars = new ArrayList<>();
        for (ArtifactResult result :
                repoSystem.resolveDependencies(repoSession, request).getArtifactResults()) {
            jars.add(result.getArtifact().getFile().getAbsolutePath());
        }
        return jars;
    }

    /**
     * The per-plugin {@code <dependencies>} decoded from the canonical key (per-entry form:
     * {@code g:a:v:type:classifier:scope} with {@code ^}-separated {@code g:a} exclusions
     * appended — see PrebuiltPluginRealms.dependencyKey).
     */
    private static List<Dependency> decodeDependencies(String depKey) {
        List<Dependency> dependencies = new ArrayList<>();
        if (depKey.isEmpty()) {
            return dependencies;
        }
        for (String dep : depKey.split(",")) {
            String[] exclusionSplit = dep.split("\\^");
            String[] c = exclusionSplit[0].split(":", -1);
            List<Exclusion> exclusions = new ArrayList<>();
            for (int i = 1; i < exclusionSplit.length; i++) {
                String[] ex = exclusionSplit[i].split(":", -1);
                exclusions.add(new Exclusion(ex[0], ex[1], "*", "*"));
            }
            String type = c.length > 3 && !c[3].isEmpty() ? c[3] : "jar";
            String classifier = c.length > 4 ? c[4] : "";
            String scope = c.length > 5 && !c[5].isEmpty() ? c[5] : JavaScopes.COMPILE;
            dependencies.add(
                    new Dependency(new DefaultArtifact(c[0], c[1], classifier, type, c[2]), scope, false, exclusions));
        }
        return dependencies;
    }

    /**
     * kotlin-compiler-embeddable ships IntelliJ XML DOM types compiled against
     * kotlinx.serialization without declaring the dependency. Harmless on HotSpot (never
     * reflected), but bake-time getDeclaredMethods hits NoClassDefFoundError: KSerializer and the
     * poison cascades to the mojos — the whole plugin would be SKIPPED->dynamic. Injecting
     * kotlinx-serialization-core-jvm (only that artifact — its transitive kotlin-stdlib may be
     * OLDER than the realm's and would reintroduce version mixing) keeps the bake gate green.
     */
    private List<String> resolveKotlinxSerialization() throws Exception {
        String version = System.getenv().getOrDefault("KOTLINX_SERIALIZATION_VERSION", "1.9.0");
        System.err.println(">>> Adding kotlinx-serialization-core-jvm:" + version
                + " (required by kotlin-compiler-embeddable bake)...");
        List<String> jars = new ArrayList<>();
        for (String jar : resolveRuntimeClasspath(
                new DefaultArtifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "jar", version),
                List.of())) {
            // only the serialization artifacts — not the transitive kotlin-stdlib, which may be
            // OLDER than the realm's and would reintroduce version mixing
            if (jar.contains("kotlinx-serialization")) {
                jars.add(jar);
            }
        }
        return jars;
    }

    /** groupId:artifactId from a local-repo-layout path, or null (mirrors the script's python). */
    static String repoLayoutGa(String jarPath) {
        String[] parts = jarPath.split(Pattern.quote(File.separator));
        for (int i = parts.length - 1; i >= 0; i--) {
            if ("repository".equals(parts[i]) && parts.length - i >= 4) {
                StringBuilder group = new StringBuilder();
                for (int j = i + 1; j < parts.length - 3; j++) {
                    if (!group.isEmpty()) {
                        group.append('.');
                    }
                    group.append(parts[j]);
                }
                return group.isEmpty() ? null : group + ":" + parts[parts.length - 3];
            }
        }
        return null;
    }

    /** One validated spec line — same checks the bash/python pipeline performs at this choke point. */
    private static String specLine(String gav, String depKey, List<String> jars) {
        for (String value : new String[] {gav, depKey}) {
            for (char c : value.toCharArray()) {
                if (c == '=' || c == '\n' || c == '\r' || c < 0x20) {
                    throw new IllegalArgumentException(
                            "coordinates contain characters that would corrupt the spec line: " + value);
                }
            }
        }
        String joined = String.join(File.pathSeparator, jars).replace('\\', '/');
        if (joined.contains("\n") || joined.contains("\r")) {
            throw new IllegalArgumentException("jar list for " + gav + " contains a line terminator");
        }
        return (depKey.isEmpty() ? gav : gav + "|" + depKey) + "=" + joined;
    }

    /** The dist-exact image classpath: boot/*.jar + lib/*.jar + the extra jars (sidecar, ...). */
    private static List<String> imageClasspath(Path mavenHome, List<Path> extraCp) throws Exception {
        List<String> classpath = new ArrayList<>();
        for (String dir : new String[] {"boot", "lib"}) {
            try (var jars = Files.newDirectoryStream(mavenHome.resolve(dir), "*.jar")) {
                List<String> sorted = new ArrayList<>();
                jars.forEach(j -> sorted.add(j.toAbsolutePath().toString()));
                sorted.sort(String::compareTo);
                classpath.addAll(sorted);
            }
        }
        for (Path extra : extraCp) {
            if (!Files.exists(extra)) {
                throw new IllegalStateException("--extra-cp entry does not exist: " + extra);
            }
            classpath.add(extra.toAbsolutePath().toString());
        }
        return classpath;
    }

    /** Forks the SanitizeRealmJars JVM (JVMCI link probe cannot run in-process of an image build). */
    private static void runSanitize(List<String> imageClasspath, Path spec, Path work, Path unlinkable)
            throws Exception {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String ownJar = Path.of(GenerateRealmSpec.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+EnableJVMCI",
                "--add-exports=jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED",
                "--add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED",
                "-cp",
                String.join(File.pathSeparator, imageClasspath) + File.pathSeparator + ownJar,
                SanitizeRealmJars.class.getName(),
                spec.toString(),
                work.resolve("sanitized").toString(),
                unlinkable.toString(),
                spec.toString()));
        Process p = new ProcessBuilder(command).inheritIO().start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("SanitizeRealmJars failed (see output above)");
        }
    }

    private List<Path> toExtraCp() throws MojoExecutionException {
        var arr = new ArrayList<Path>();
        for (var extraArtifact : extraCp) {
            try {
                var artifact = new DefaultArtifact(extraArtifact);
                var request = new ArtifactRequest();
                request.setArtifact(artifact);
                var remoteRepos = project.getRemoteProjectRepositories();
                request.setRepositories(remoteRepos);
                var result = repoSystem.resolveArtifact(repoSession, request);
                var path = result.getArtifact().getFile().toPath();
                arr.add(path);
            } catch (ArtifactResolutionException e) {
                throw new MojoExecutionException("Could not resolve additional artifact dependencies", e);
            }
        }
        return arr;
    }
}
