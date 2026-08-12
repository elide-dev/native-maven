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

/**
 * Maven-build-side replacement for the bash prep pipeline of build-nmvn-prebuilt.sh (step 2 of the
 * native-build-tools migration): given a list of plugin entries {@code g:a:v[|canonical-deps]},
 * it produces everything the native-image invocation needs beyond the static flags already baked
 * into the sidecar jar's {@code META-INF/native-image} properties:
 *
 * <ol>
 * <li>Resolves each plugin's runtime classpath by SYNTHESIZING a throwaway pom (per-plugin
 *     dependencies declared both as dependencies and dependencyManagement, exclusions honored) and
 *     forking the DIST's {@code mvn dependency:build-classpath} — deliberately the same mechanism
 *     as the bash script, so user settings (mirrors, proxies, local repo) behave identically. A
 *     future step replaces this with an injected Aether {@code RepositorySystem} inside a mojo.</li>
 * <li>Drops jars whose groupId:artifactId maven-core EXPORTS to plugin realms (read from the dist
 *     maven-core jar's {@code META-INF/maven/extension.xml}) — stock Maven excludes exported
 *     artifacts from plugin realms the same way.</li>
 * <li>kotlin-compiler-embeddable special case: injects kotlinx-serialization-core-jvm (see the
 *     comment at {@link #resolveKotlinxSerialization}).</li>
 * <li>Writes the realm spec ({@code g:a:v[|deps]=jar{pathSep}jar...}, one line per plugin,
 *     LF-terminated) with the same validation the bash/python pipeline performs.</li>
 * <li>Forks the {@link SanitizeRealmJars} JVM (attribute strip + JVMCI link probe) over the spec,
 *     which rewrites it in place and produces {@code unlinkable.txt}.</li>
 * <li>Writes a native-image @argfile with the DYNAMIC arguments: the dist-exact image classpath
 *     ({@code boot/*.jar} + {@code lib/*.jar} + the extra jars passed in), the
 *     {@code -Dnmvn.prebuilt.*} inputs, builder heap, and the variant's metadata directory. The
 *     native-maven-plugin passes just {@code @thisfile} in {@code buildArgs}, keeping the pom
 *     static.</li>
 * </ol>
 *
 * <p>Usage (all flags may repeat where noted):
 * <pre>
 *   --maven-home &lt;dist dir&gt;         required
 *   --work &lt;dir&gt;                    required, scratch + outputs
 *   --argfile &lt;path&gt;                required, native-image argfile to write
 *   --config-dir &lt;dir&gt;              required, -H:ConfigurationFileDirectories value
 *   --extra-cp &lt;path&gt;               repeatable: sidecar jar, jvm-channel jar, ... (image classpath)
 *   --plugins &lt;entries&gt;             optional; entries separated by whitespace/newlines, commas
 *                                     also accepted when no entry carries a '|' dependency key
 *   --plugins-file &lt;path&gt;           optional; newline-separated entries
 *   --max-ram-percentage &lt;value&gt;    builder heap, default 80.0
 * </pre>
 *
 * <p>With no plugins at all the spec/sanitize steps are skipped (baseline image, parity with the
 * script's no-args mode) and the argfile carries no {@code -Dnmvn.prebuilt.*} entries.
 */
public final class GenerateRealmSpec {

    public static void main(String[] args) throws Exception {
        Path mavenHome = null;
        Path work = null;
        Path argfile = null;
        Path configDir = null;
        List<Path> extraCp = new ArrayList<>();
        StringBuilder pluginsInput = new StringBuilder();
        String maxRamPercentage = "80.0";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--maven-home" -> mavenHome = Path.of(args[++i]);
                case "--work" -> work = Path.of(args[++i]);
                case "--argfile" -> argfile = Path.of(args[++i]);
                case "--config-dir" -> configDir = Path.of(args[++i]);
                case "--extra-cp" -> extraCp.add(Path.of(args[++i]));
                case "--plugins" -> pluginsInput.append('\n').append(args[++i]);
                case "--plugins-file" -> {
                    Path f = Path.of(args[++i]);
                    if (!args[i].isBlank() && Files.isRegularFile(f)) {
                        pluginsInput.append('\n').append(Files.readString(f));
                    }
                }
                case "--max-ram-percentage" -> maxRamPercentage = args[++i];
                default -> throw new IllegalArgumentException("Unknown flag: " + args[i]);
            }
        }
        if (mavenHome == null || work == null || argfile == null || configDir == null) {
            throw new IllegalArgumentException("--maven-home, --work, --argfile and --config-dir are required");
        }
        if (!Files.isDirectory(mavenHome.resolve("lib"))) {
            throw new IllegalStateException("Not a Maven dist (no lib/): " + mavenHome
                    + " — build the distribution first (mvn package -DskipTests from the repo root)");
        }
        Files.createDirectories(work);

        List<String> entries = splitPluginEntries(pluginsInput.toString());
        Path spec = work.resolve("spec.txt");
        Path unlinkable = work.resolve("unlinkable.txt");
        if (entries.isEmpty()) {
            System.err.println("GenerateRealmSpec: WARNING — no plugins to bake; the image can run"
                    + " pluginless commands natively, everything else goes to the JVM fallback.");
        } else {
            Set<String> exported = exportedArtifacts(mavenHome);
            List<String> specLines = new ArrayList<>();
            for (String entry : entries) {
                specLines.add(resolveEntry(entry, mavenHome, work, exported));
            }
            Files.writeString(spec, String.join("\n", specLines) + "\n", StandardCharsets.UTF_8);
            System.err.println("GenerateRealmSpec: wrote " + specLines.size() + " realm(s) to " + spec);
            runSanitize(imageClasspath(mavenHome, extraCp), spec, work, unlinkable);
        }
        writeArgfile(
                argfile,
                imageClasspath(mavenHome, extraCp),
                configDir,
                maxRamPercentage,
                entries.isEmpty() ? null : new Path[] {spec, unlinkable});
        System.err.println("GenerateRealmSpec: wrote native-image argfile " + argfile);
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

    /** The groupId:artifactId set maven-core exports to every plugin realm (from extension.xml). */
    private static Set<String> exportedArtifacts(Path mavenHome) throws Exception {
        Path coreJar;
        try (var jars = Files.newDirectoryStream(mavenHome.resolve("lib"), "maven-core-*.jar")) {
            var it = jars.iterator();
            if (!it.hasNext()) {
                throw new IllegalStateException("No maven-core jar under " + mavenHome.resolve("lib"));
            }
            coreJar = it.next();
        }
        Set<String> exported = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(coreJar.toFile())) {
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
    private static String resolveEntry(String entry, Path mavenHome, Path work, Set<String> exported) throws Exception {
        String gav = entry.split("\\|", 2)[0];
        String depKey = entry.contains("|") ? entry.substring(entry.indexOf('|') + 1) : "";
        String[] g = gav.split(":");
        if (g.length != 3) {
            throw new IllegalArgumentException("Expected groupId:artifactId:version[|deps], got: " + entry);
        }
        Path pom = work.resolve(g[1] + "-pom.xml");
        Files.writeString(pom, synthesizePom(g[0], g[1], g[2], depKey), StandardCharsets.UTF_8);
        Path cpFile = work.resolve(g[1] + ".cp");
        System.err.println(">>> Resolving runtime classpath for " + gav
                + (depKey.isEmpty() ? "" : " (+ per-plugin deps: " + depKey + ")"));
        buildClasspath(mavenHome, pom, cpFile);

        List<String> jars = new ArrayList<>();
        boolean kotlinEmbeddable = false;
        for (String jar : Files.readString(cpFile).trim().split(File.pathSeparator)) {
            if (jar.isBlank()) {
                continue;
            }
            String ga = repoLayoutGa(jar);
            if (ga != null && exported.contains(ga)) {
                System.err.println("    excluded (provided by core): " + ga);
                continue;
            }
            kotlinEmbeddable |= Path.of(jar).getFileName().toString().startsWith("kotlin-compiler-embeddable-");
            jars.add(jar.trim());
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("Could not resolve runtime classpath for " + gav);
        }
        if (kotlinEmbeddable) {
            for (String serJar : resolveKotlinxSerialization(mavenHome, work)) {
                if (!jars.contains(serJar)) {
                    jars.add(serJar);
                    System.err.println("    + " + serJar);
                }
            }
        }
        return specLine(gav, depKey, jars);
    }

    /** The throwaway pom dependency:build-classpath resolves — plugin + decoded per-plugin deps. */
    private static String synthesizePom(String groupId, String artifactId, String version, String depKey) {
        StringBuilder deps = new StringBuilder();
        StringBuilder mgmt = new StringBuilder();
        if (!depKey.isEmpty()) {
            // per-entry form: g:a:v:type:classifier:scope with ^-separated g:a exclusions appended
            for (String dep : depKey.split(",")) {
                String[] exclusionSplit = dep.split("\\^");
                String[] c = exclusionSplit[0].split(":", -1);
                StringBuilder exclusions = new StringBuilder();
                if (exclusionSplit.length > 1) {
                    exclusions.append("<exclusions>");
                    for (int i = 1; i < exclusionSplit.length; i++) {
                        String[] ex = exclusionSplit[i].split(":", -1);
                        exclusions
                                .append("<exclusion><groupId>")
                                .append(ex[0])
                                .append("</groupId><artifactId>")
                                .append(ex[1])
                                .append("</artifactId></exclusion>");
                    }
                    exclusions.append("</exclusions>");
                }
                String block = "    <dependency><groupId>" + c[0] + "</groupId><artifactId>" + c[1]
                        + "</artifactId><version>" + c[2] + "</version><type>"
                        + (c.length > 3 && !c[3].isEmpty() ? c[3] : "jar") + "</type>"
                        + (c.length > 4 && !c[4].isEmpty() ? "<classifier>" + c[4] + "</classifier>" : "")
                        + "<scope>" + (c.length > 5 && !c[5].isEmpty() ? c[5] : "compile") + "</scope>"
                        + exclusions + "</dependency>\n";
                deps.append(block);
                // declared in BOTH places: dependencies adds jars to the realm, dependencyManagement
                // overrides versions inside the plugin's own tree — the two effects Maven's
                // per-plugin <dependencies> have.
                mgmt.append(block);
            }
        }
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>nmvn</groupId><artifactId>prebuilt-cp</artifactId><version>1</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <dependencyManagement><dependencies>\n" + mgmt + "  </dependencies></dependencyManagement>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId
                + "</artifactId><version>" + version + "</version></dependency>\n"
                + deps
                + "  </dependencies>\n"
                + "</project>\n";
    }

    /** Forks the DIST's mvn so user settings (mirrors, proxies, repo location) apply identically. */
    private static void buildClasspath(Path mavenHome, Path pom, Path cpFile) throws Exception {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path mvn = mavenHome.resolve("bin").resolve(windows ? "mvn.cmd" : "mvn");
        Process p = new ProcessBuilder(
                        mvn.toString(),
                        "-q",
                        "-f",
                        pom.toString(),
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                        "-Dmdep.outputFile=" + cpFile,
                        "-DincludeScope=runtime")
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0 || !Files.isRegularFile(cpFile)) {
            throw new IllegalStateException("dependency:build-classpath failed for " + pom + ":\n" + output);
        }
    }

    /**
     * kotlin-compiler-embeddable ships IntelliJ XML DOM types compiled against
     * kotlinx.serialization without declaring the dependency. Harmless on HotSpot (never
     * reflected), but bake-time getDeclaredMethods hits NoClassDefFoundError: KSerializer and the
     * poison cascades to the mojos — the whole plugin would be SKIPPED->dynamic. Injecting
     * kotlinx-serialization-core-jvm (only that artifact — its transitive kotlin-stdlib may be
     * OLDER than the realm's and would reintroduce version mixing) keeps the bake gate green.
     */
    private static List<String> resolveKotlinxSerialization(Path mavenHome, Path work) throws Exception {
        String version = System.getenv().getOrDefault("KOTLINX_SERIALIZATION_VERSION", "1.9.0");
        System.err.println(">>> Adding kotlinx-serialization-core-jvm:" + version
                + " (required by kotlin-compiler-embeddable bake)...");
        Path pom = work.resolve("kotlinx-serialization-pom.xml");
        Files.writeString(
                pom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>nmvn</groupId><artifactId>prebuilt-ser</artifactId><version>1</version>\n"
                        + "  <packaging>pom</packaging>\n"
                        + "  <dependencies><dependency><groupId>org.jetbrains.kotlinx</groupId>"
                        + "<artifactId>kotlinx-serialization-core-jvm</artifactId><version>" + version
                        + "</version></dependency></dependencies>\n"
                        + "</project>\n",
                StandardCharsets.UTF_8);
        Path cpFile = work.resolve("kotlinx-serialization.cp");
        buildClasspath(mavenHome, pom, cpFile);
        List<String> jars = new ArrayList<>();
        for (String jar : Files.readString(cpFile).trim().split(File.pathSeparator)) {
            if (jar.contains("kotlinx-serialization")) {
                jars.add(jar.trim());
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

    /**
     * The native-image @argfile with everything dynamic: classpath, builder heap, prebuilt inputs,
     * metadata dir. Static flags come from the sidecar jar's META-INF native-image.properties;
     * main class and image name from the native-maven-plugin configuration.
     */
    private static void writeArgfile(
            Path argfile, List<String> imageClasspath, Path configDir, String maxRamPercentage, Path[] prebuilt)
            throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "-J-XX:MaxRAMPercentage=" + maxRamPercentage,
                "-cp",
                String.join(File.pathSeparator, imageClasspath),
                "-Dguice_bytecode_gen_option=DISABLED",
                "-H:+UnlockExperimentalVMOptions",
                "-H:ConfigurationFileDirectories=" + configDir.toAbsolutePath()));
        if (prebuilt != null) {
            args.add("-Dnmvn.prebuilt.pluginsFile=" + prebuilt[0].toAbsolutePath());
            args.add("-Dnmvn.prebuilt.unlinkable=" + prebuilt[1].toAbsolutePath());
        }
        StringBuilder content = new StringBuilder();
        for (String arg : args) {
            // same quoting as the script's write_argfile: quote anything with whitespace/quotes/#
            if (arg.isEmpty() || arg.matches(".*[\\s\"'].*") || arg.startsWith("#")) {
                arg = "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            content.append(arg).append('\n');
        }
        Files.createDirectories(argfile.toAbsolutePath().getParent());
        Files.writeString(argfile, content.toString(), StandardCharsets.UTF_8);
    }

    private GenerateRealmSpec() {}
}
