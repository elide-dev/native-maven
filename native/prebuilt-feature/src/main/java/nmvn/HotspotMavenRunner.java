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
package nmvn;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.slf4j.MavenSimpleLogger;
import org.apidesign.jvm.channel.JVM;
import org.apidesign.jvm.interop.OtherJvmClassLoader;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a NON-BAKED plugin goal on a HotSpot JVM booted INSIDE the native-image process (via
 * jvm-channel's {@link JVM#create}, i.e. {@code JNI_CreateJavaVM} on {@code $JAVA_HOME}'s libjvm).
 * This is the non-crema answer to Crema's dynamic fallback: the frozen image cannot load plugin
 * classes at runtime, so the goal is re-invoked as {@code g:a:v:goal@execId} through STOCK Maven
 * from the already-required {@code MAVEN_HOME} dist, against the current project's pom.
 *
 * <p><b>One JVM per process.</b> HotSpot permits a single {@code JNI_CreateJavaVM} per process
 * and cannot be re-created after destruction, so the JVM is created lazily on the first delegated
 * goal and reused for all others (the HotSpot-side {@code nmvn.hotspot.HotspotMavenMain} likewise
 * configures its classworlds Launcher once and reuses it).
 *
 * <p><b>Exit codes, not System.exit.</b> The HotSpot side shares the process, so Maven's exiting
 * entry points must not run. The wrapper main calls {@code Launcher.launch} (which returns the
 * enhanced main's exit code) and reports it through a temp file — JNI's {@code
 * CallStaticVoidMethod} has no return channel and jvm-channel's {@code executeMain} swallows
 * JVM-side exceptions.
 *
 * <p><b>Known limitation (accepted for this iteration):</b> the delegated invocation re-reads the
 * pom in a FRESH Maven session — pom-declared plugin configuration is honored, but reactor/session
 * state is not shared. A delegated goal that ATTACHES artifacts (jar:jar, ...) produces its files
 * on disk without updating the native session's MavenProject, so a downstream baked install/deploy
 * in the same run will not see them.
 */
final class HotspotMavenRunner {

    private static final Logger LOG = LoggerFactory.getLogger(HotspotMavenRunner.class);

    /** Binary name of the HotSpot-side wrapper main; its jar is baked as an image resource. */
    private static final String MAIN_CLASS = "nmvn.hotspot.HotspotMavenMain";

    /** @GuardedBy("HotspotMavenRunner.class") — see "one JVM per process" above. */
    private static JVM jvm;
    /** @GuardedBy("HotspotMavenRunner.class") — only one class per {@link #jvm} */
    private static Value clazzHotSpotMavenMain;

    private static IllegalStateException bootFailure;

    private HotspotMavenRunner() {}

    /**
     * Is the JVM fallback active? Only inside a native image; the default is baked per variant
     * ({@link PrebuiltPluginRealms#JVM_FALLBACK_DEFAULT} — true for non-crema, false for crema,
     * whose runtime class loading serves non-baked plugins natively); {@code -Dnmvn.jvm.fallback}
     * overrides at run time.
     */
    static boolean enabled() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))
                && Boolean.parseBoolean(System.getProperty(
                        "nmvn.jvm.fallback", String.valueOf(PrebuiltPluginRealms.JVM_FALLBACK_DEFAULT)));
    }

    /**
     * Executes one mojo through the in-process HotSpot Maven.
     *
     * @throws IOException on plumbing failures (JVM boot, exit-code file)
     * @throws HotspotGoalFailedException when the delegated Maven invocation returns non-zero
     */
    static synchronized void execute(MavenSession session, MojoExecution mojoExecution) throws IOException {
        var args = buildArgs(session, mojoExecution);
        LOG.info("nmvn: running {} on HotSpot JVM: mvn {}", mojoExecution, String.join(" ", args));
        final Object singleArrayArg = args.toArray();
        var code = hotSpotMavenMain().invokeMember("run", singleArrayArg);
        int exitCode = code.asInt();
        if (exitCode != 0) {
            throw new HotspotGoalFailedException(
                    "Goal " + goalSpec(mojoExecution) + " failed on the HotSpot JVM (exit code " + exitCode + ")");
        }
        if (jvm == null) {
            // when running in mock dual JVM
            // some sink is always needed, otherwise there is StackOverflowError
            MavenSimpleLogger.setLogSink((msg) -> {});
        }
    }

    /** Non-zero exit of the delegated Maven run — the plugin itself failed, not the plumbing. */
    static final class HotspotGoalFailedException extends RuntimeException {
        HotspotGoalFailedException(String message) {
            super(message);
        }
    }

    /**
     * The stock-Maven command line for one mojo execution: scoped to the current project's pom,
     * non-recursive, with the caller's repo/settings/offline context and user properties forwarded.
     * Configuration comes from the re-read pom; {@code @executionId} selects the matching
     * {@code <execution>} block (skipped for {@code default-cli}, whose config IS the CLI).
     */
    private static List<String> buildArgs(MavenSession session, MojoExecution mojoExecution) {
        List<String> args = new ArrayList<>();
        args.add("--batch-mode");
        args.add("--non-recursive");
        File pom = session.getCurrentProject().getFile();
        if (pom != null) {
            args.add("--file");
            args.add(pom.getAbsolutePath());
        }
        String localRepo = session.getRequest().getLocalRepositoryPath() == null
                ? null
                : session.getRequest().getLocalRepositoryPath().getPath();
        if (localRepo != null) {
            args.add("-Dmaven.repo.local=" + localRepo);
        }
        if (session.getRequest().isOffline()) {
            args.add("--offline");
        }
        File settings = session.getRequest().getUserSettingsFile();
        if (settings != null && settings.isFile()) {
            args.add("--settings");
            args.add(settings.getAbsolutePath());
        }
        // The delegated Maven runs from the same maven.home, so it picks up the installation
        // settings on its own — forward them only when they differ (custom -gs), sparing the
        // "option -gs is deprecated" warning on every delegated goal.
        File globalSettings = session.getRequest().getGlobalSettingsFile();
        File installationSettings = new File(new File(System.getProperty("maven.home"), "conf"), "settings.xml");
        if (globalSettings != null
                && globalSettings.isFile()
                && !globalSettings.getAbsoluteFile().equals(installationSettings.getAbsoluteFile())) {
            args.add("--global-settings");
            args.add(globalSettings.getAbsolutePath());
        }
        for (Map.Entry<Object, Object> property :
                session.getRequest().getUserProperties().entrySet()) {
            String key = String.valueOf(property.getKey());
            // session.* (topDirectory, rootDirectory) are injected into user properties by Maven
            // itself per invocation — the delegated run computes its own.
            if (!key.startsWith("session.")) {
                args.add("-D" + key + "=" + property.getValue());
            }
        }
        List<String> activeProfiles = session.getRequest().getActiveProfiles();
        if (activeProfiles != null && !activeProfiles.isEmpty()) {
            args.add("--activate-profiles");
            args.add(String.join(",", activeProfiles));
        }
        args.add(goalSpec(mojoExecution));
        return args;
    }

    private static String goalSpec(MojoExecution mojoExecution) {
        Plugin plugin = mojoExecution.getPlugin();
        StringBuilder goal =
                new StringBuilder().append(plugin.getGroupId()).append(':').append(plugin.getArtifactId());
        if (plugin.getVersion() != null) {
            goal.append(':').append(plugin.getVersion());
        }
        goal.append(':').append(mojoExecution.getGoal());
        String executionId = mojoExecution.getExecutionId();
        if (executionId != null && !executionId.startsWith("default-cli")) {
            goal.append('@').append(executionId);
        }
        return goal.toString();
    }

    private static synchronized JVM jvm() throws IOException {
        if (bootFailure != null) {
            // JNI_CreateJavaVM is once-per-process: after a failed boot there is no retry.
            throw new IOException("HotSpot JVM fallback unavailable", bootFailure);
        }
        if (jvm == null) {
            try {
                jvm = boot();
            } catch (RuntimeException | IOException e) {
                bootFailure = new IllegalStateException("HotSpot JVM boot failed: " + e, e);
                throw bootFailure;
            }
        }
        return jvm;
    }

    private static synchronized Value hotSpotMavenMain() throws IOException {
        if (clazzHotSpotMavenMain == null) {
            System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
            var jvmOrNull = ImageInfo.inImageCode() ? jvm() : null;
            var loader = OtherJvmClassLoader.create(jvmOrNull);
            if (jvmOrNull == null) {
                var mavenHome = System.getProperty("maven.home");
                if (mavenHome == null) {
                    throw new IOException("java.home/maven.home not set — NmvnLauncher should have set both");
                }
                var classworldsConf = new File(new File(mavenHome, "bin"), "m2.conf");
                System.setProperty("classworlds.conf", classworldsConf.getCanonicalPath());
            }
            clazzHotSpotMavenMain = loader.loadClass(MAIN_CLASS);
        }
        return clazzHotSpotMavenMain;
    }

    /**
     * Boots the in-process HotSpot JVM. {@code java.class.path} is the extracted wrapper jar plus
     * {@code $MAVEN_HOME/boot/*.jar} (classworlds). Maven types load from the m2.conf realm.
     */
    private static JVM boot() throws IOException {
        var javaHome = System.getProperty("java.home");
        var mavenHome = System.getProperty("maven.home");
        if (javaHome == null || mavenHome == null) {
            throw new IOException("java.home/maven.home not set — NmvnLauncher should have set both");
        }
        var classworldsConf = new File(new File(mavenHome, "bin"), "m2.conf");
        var classpath = new StringBuilder();
        appendToCp(new File(mavenHome, "boot"), classpath);
        appendToCp(new File(mavenHome, "nmvn-boot"), classpath);
        var options = new ArrayList<String>();
        options.add("-Djava.class.path=" + classpath);
        options.add("-Dmaven.home=" + mavenHome);
        options.add("-Dclassworlds.conf=" + classworldsConf);
        String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModuleDir != null) {
            options.add("-Dmaven.multiModuleProjectDirectory=" + multiModuleDir);
        }
        LOG.info("nmvn: booting in-process HotSpot JVM from {} for non-baked plugins", javaHome);
        return JVM.create(new File(javaHome), options.toArray(new String[0]));
    }

    private static void appendToCp(File dirWithJars, StringBuilder collectTo) throws IOException {
        var jars = dirWithJars.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IOException("No jars under " + dirWithJars);
        }
        for (var jar : jars) {
            if (!collectTo.isEmpty()) {
                collectTo.append(File.pathSeparatorChar);
            }
            collectTo.append(jar.getAbsolutePath());
        }
    }
}
