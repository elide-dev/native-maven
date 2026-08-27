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
package nmvn.launcher;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;

import nmvn.HotspotMavenRunner;
import nmvn.NmvnMode;
import nmvn.PrebuiltPluginRealms;
import nmvn.PrebuiltRoutingLog;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.cling.MavenCling;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;

/**
 * nmvn image entry point. Replaces the classworlds Launcher: instead of building a ClassWorld from
 * m2.conf at runtime, it takes the BAKED world (plexus.core + prebuilt plugin realms, constructed at
 * image build time by PrebuiltPluginRealms), applies the single runtime fixup the snapshot needs,
 * and hands the world to stock Maven via {@code MavenCling.main(args, world)} — the "enhanced"
 * entry point Maven already exposes for external worlds.
 *
 * <p>The one fixup: no hosted class loader reference survives the heap snapshot (verified for both
 * the image classloader and the system loader, 2026-07-03), so the baked plexus.core wakes up with
 * a null parent. Re-attaching the runtime system loader restores delegation to the AOT core classes
 * and the embedded META-INF resources. Everything below plexus.core (the plugin realms) was
 * parented to it at build time — baked-realm-to-baked-realm references survive verbatim, so no
 * per-plugin fixup exists anymore.
 */
public final class NmvnLauncher {

    private NmvnLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("--info") || args[0].startsWith("--info="))) {
            System.exit(printInfo(args[0]));
        } else {
            System.exit(runMain(args, null, null, null, false));
        }
    }

    static int runMain(
            String[] args,
            @Nullable InputStream stdIn,
            @Nullable OutputStream stdOut,
            @Nullable OutputStream stdErr,
            boolean embedded)
            throws IOException, URISyntaxException {
        setupMavenEnvironment(args);
        mirrorCommandLineProperties(args);
        args = extractMode(args);
        if (NmvnMode.current() == NmvnMode.LEGACY
                && NmvnMode.imageRuntime()
                && !PrebuiltPluginRealms.RUNTIME_CLASS_LOADING) {
            // Legacy = plain Apache Maven for the whole build: skip the baked world entirely and
            // run the untouched command line as one batch on the in-process HotSpot JVM. (A
            // plain-JVM launcher run IS legacy execution already, hence the image-runtime guard;
            // crema has no modes at all — extractMode rejected the flag, and a mirrored
            // -Dnmvn.mode is ignored here like everywhere else on that variant.)
            return HotspotMavenRunner.runFullBuild(args);
        }
        ClassWorld world = PrebuiltPluginRealms.world();
        ClassRealm core = world == null ? null : world.getClassRealm(PrebuiltPluginRealms.CORE_REALM_ID);
        if (core == null) {
            // Build-time init failed or was skipped — fall back to a plain world so Maven still
            // boots (all plugins resolve dynamically).
            world = new ClassWorld(PrebuiltPluginRealms.CORE_REALM_ID, ClassLoader.getSystemClassLoader());
            core = world.getClassRealm(PrebuiltPluginRealms.CORE_REALM_ID);
        } else {
            // THE single runtime fixup of the baked hierarchy (see class doc).
            core.setParentClassLoader(ClassLoader.getSystemClassLoader());
        }
        Thread.currentThread().setContextClassLoader(core);
        diagnose(core);
        PrebuiltRoutingLog.originalStdOut(stdOut);
        var cling = new MavenCling(world);
        var exitCode = cling.run(args, stdIn, stdOut, stdErr, embedded);
        return exitCode;
    }

    /**
     * Binary introspection: {@code --info=plugins|flavor|variant} prints one fact about THIS image
     * and exits without booting Maven (works with no MAVEN_HOME/JAVA_HOME set). Every answer is
     * DERIVED from what the binary contains or can do, never read from a build-time label — a label
     * can drift from the content it describes, a derivation cannot:
     * <ul>
     * <li>{@code plugins} — the GAVs of the baked plugin registry, one per line. This is the
     * post-gate truth: a plugin the build script requested but the bake-or-fall-back gate skipped
     * is (correctly) absent.</li>
     * <li>{@code flavor} — {@code spring@<bootVersion>} when spring-boot-maven-plugin is baked
     * (its version IS the Spring Boot version), else {@code generic}; resolved at build time from
     * the bake set, see {@link PrebuiltPluginRealms#FLAVOR}.</li>
     * <li>{@code variant} — {@code crema} when this image was built with runtime class loading
     * (the flag captured at build time from the builder itself, see
     * {@link PrebuiltPluginRealms#RUNTIME_CLASS_LOADING}), else {@code hotspot}.</li>
     * </ul>
     *
     * <p>Bare {@code --info} prints all facts as key/value pairs (for humans); {@code
     * --info=<topic>} prints the bare value only (for scripts: {@code $(nmvn --info=flavor)}).
     */
    private static int printInfo(String arg) {
        String what = arg.startsWith("--info=") ? arg.substring("--info=".length()) : "";
        switch (what) {
            case "plugins" -> bakedPluginGavs().forEach(System.out::println);
            case "flavor" -> System.out.println(PrebuiltPluginRealms.FLAVOR);
            case "variant" -> System.out.println(variant());
            case "" -> {
                // bare --info: all facts as key/value pairs, for humans
                System.out.println("flavor: " + PrebuiltPluginRealms.FLAVOR);
                System.out.println("variant: " + variant());
                System.out.println("plugins:");
                bakedPluginGavs().forEach(gav -> System.out.println("  " + gav));
            }
            default -> {
                System.err.println("Usage: --info[=plugins|flavor|variant]");
                return 2;
            }
        }
        return 0;
    }

    private static java.util.List<String> bakedPluginGavs() {
        var gavs = new java.util.ArrayList<String>();
        for (var prebuilt : PrebuiltPluginRealms.all().values()) {
            var descriptor = prebuilt.descriptor;
            gavs.add(descriptor.getGroupId() + ":" + descriptor.getArtifactId() + ":" + descriptor.getVersion());
        }
        return gavs;
    }

    private static String variant() {
        return PrebuiltPluginRealms.RUNTIME_CLASS_LOADING ? "crema" : "hotspot";
    }

    /**
     * Mirrors every {@code -Dkey[=value]} argument into Java System Properties, standing in for the
     * JVM flags the {@code mvn} script passes on HotSpot ({@code -Dmaven.home}, {@code
     * -Dmaven.multiModuleProjectDirectory}, ...).
     *
     * <p>The image is built with {@code -H:-ParseRuntimeOptions}: SubstrateVM would otherwise
     * consume every {@code -D} argument at VM startup and strip it from argv, so a bare {@code
     * -DskipTests} became system property {@code skipTests=""} and Maven's CLI — where a bare
     * {@code -D} means {@code true} — never saw the flag. With SVM parsing off, all arguments reach
     * {@link MavenCling} as regular user properties; this mirror covers the properties that must be
     * REAL system properties before CLI parsing runs (BaseParser.getInstallationDirectory demands
     * {@code maven.home}; Guice reads {@code guice_bytecode_gen_option} during bootstrap). Bare
     * {@code -Dkey} mirrors as {@code "true"}, matching Maven's own CLI defaulting.
     */
    private static void mirrorCommandLineProperties(String[] args) {
        for (String arg : args) {
            if (arg.length() > 2 && arg.startsWith("-D")) {
                String prop = arg.substring(2);
                int eq = prop.indexOf('=');
                if (eq < 0) {
                    System.setProperty(prop, "true");
                } else if (eq > 0) {
                    System.setProperty(prop.substring(0, eq), prop.substring(eq + 1));
                }
            }
        }
    }

    /**
     * Consumes the launcher-owned {@code --mode=native|mixed|legacy} flag (NATIVEMVN.md "Modes"):
     * publishes it as the {@code nmvn.mode} system property for the mojo-execution seam and
     * returns the remaining arguments — stock Maven's CLI parser must never see the flag. Runs
     * AFTER {@link #mirrorCommandLineProperties} so an explicit {@code --mode} wins over a
     * mirrored {@code -Dnmvn.mode}. An invalid or valueless {@code --mode} ends the process with
     * a usage error (exit 2) before any Maven machinery starts — as does ANY {@code --mode} on a
     * crema image, which has no modes ({@link PrebuiltPluginRealms#RUNTIME_CLASS_LOADING}).
     */
    private static String[] extractMode(String[] args) {
        var remaining = new java.util.ArrayList<String>(args.length);
        for (String arg : args) {
            if (arg.equals("--mode") || arg.startsWith("--mode=")) {
                if (PrebuiltPluginRealms.RUNTIME_CLASS_LOADING) {
                    System.err.println("Error: --mode is not supported by this Native Maven binary — it runs"
                            + " every plugin natively (baked-in ones from prebuilt realms, others via"
                            + " runtime class loading)");
                    System.exit(2);
                }
                try {
                    String value = arg.startsWith("--mode=") ? arg.substring("--mode=".length()) : "";
                    System.setProperty(
                            NmvnMode.PROPERTY, NmvnMode.parse(value).name().toLowerCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    System.err.println("Error: " + e.getMessage());
                    System.exit(2);
                }
            } else {
                remaining.add(arg);
            }
        }
        return remaining.toArray(new String[0]);
    }

    /** Implements processing as originally done by {@code nmvn} script.
     *
     */
    private static void setupMavenEnvironment(String[] args) throws URISyntaxException, java.io.IOException {
        var mavenHome = findMavenHome();
        if (!mavenHome.isDirectory()) {
            throw new FileNotFoundException(
                    "Error: Maven home not found at " + mavenHome + " - Set it with: export MAVEN_HOME=/path/to/maven");
        }
        var javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null) {
            throw new FileNotFoundException("Error: JAVA_HOME is not set. Set it with: export JAVA_HOME=/path/to/jdk");
        }

        // The flags below are the verified minimal set (java.home is REQUIRED — the compiler plugin
        // fails to initialize without it; maven.conf is derived from maven.home by Maven itself;
        // library.jline.path did nothing in the image — jline picks its FFM provider either way).
        // Keep this launch in sync with MavenCommand.kt in WHIPLASH (the `elide mvn` launcher).

        System.setProperty("guice_bytecode_gen_option", "DISABLED");
        System.setProperty("java.home", javaHome);
        System.setProperty("maven.home", mavenHome.getPath());
        System.setProperty(
                "maven.multiModuleProjectDirectory", findProjectBaseDir(args).getPath());
    }

    private static File findProjectBaseDir(String[] args) throws java.io.IOException {
        var basedir = new File(System.getProperty("user.dir"));
        for (var i = 0; i < args.length - 1; i++) {
            if ("-f".equals(args[i]) || "--file".equals(args[i])) {
                var file = new File(args[i + 1]).getCanonicalFile();
                if (file.isDirectory()) {
                    basedir = file;
                } else if (file.isFile()) {
                    basedir = file.getParentFile();
                }
                break;
            }
        }
        basedir = basedir.getCanonicalFile();
        for (var dir = basedir; dir != null; dir = dir.getParentFile()) {
            if (new File(dir, ".mvn").isDirectory()) {
                return dir;
            }
        }
        return basedir;
    }

    private static File findMavenHome() throws URISyntaxException {
        var envHome = System.getenv("MAVEN_HOME");
        if (envHome != null) {
            return new File(envHome);
        } else {
            var exeUrl =
                    NmvnLauncher.class.getProtectionDomain().getCodeSource().getLocation();
            var exeFile = new File(exeUrl.toURI());
            for (var baseDir = exeFile.getParentFile(); baseDir != null; baseDir = baseDir.getParentFile()) {
                var mavenHome = files(baseDir, "apache-maven", "target", "apache-maven-4.1.0-SNAPSHOT");
                if (mavenHome.isDirectory()) {
                    return mavenHome;
                }
            }
            // fallback
            return files(exeFile.getParentFile(), "apache-maven-4.1.0-SNAPSHOT");
        }
    }

    /** Startup diagnosis for the sidecar-activation path (temporary; prints to stderr). */
    private static void diagnose(ClassRealm core) {
        try {
            int indexes = 0;
            int nmvnIndexes = 0;
            java.util.Enumeration<java.net.URL> urls = core.getResources("META-INF/sisu/javax.inject.Named");
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                indexes++;
                try (java.io.InputStream in = url.openStream()) {
                    if (new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("nmvn.")) {
                        nmvnIndexes++;
                    }
                }
            }
            Class<?> cache = Class.forName("nmvn.PrebuiltPluginDescriptorCache");
            System.err.println("nmvn diag: sisu indexes visible=" + indexes + " (containing nmvn entries="
                    + nmvnIndexes + "), cache @Named=" + (cache.getAnnotation(javax.inject.Named.class) != null)
                    + " @Priority=" + (cache.getAnnotation(org.eclipse.sisu.Priority.class) != null));
        } catch (Throwable t) {
            System.err.println("nmvn diag FAILED: " + t);
        }
    }

    private static File files(File root, String... relativePath) {
        var dir = root;
        for (var p : relativePath) {
            dir = new File(dir, p);
        }
        return dir;
    }
}
