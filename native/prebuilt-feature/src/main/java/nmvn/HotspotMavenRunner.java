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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apidesign.jvm.channel.JVM;
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

    /** Binary name of the HotSpot-side wrapper main; its bytecode is baked as an image resource. */
    private static final String MAIN_CLASS = "nmvn.hotspot.HotspotMavenMain";

    /** @GuardedBy("HotspotMavenRunner.class") — see "one JVM per process" above. */
    private static JVM jvm;

    private static IllegalStateException bootFailure;

    private HotspotMavenRunner() {}

    /** Is the JVM fallback active? Only inside a native image, and {@code -Dnmvn.jvm.fallback=false} disables it. */
    static boolean enabled() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))
                && Boolean.parseBoolean(System.getProperty("nmvn.jvm.fallback", "true"));
    }

    /**
     * Executes one mojo through the in-process HotSpot Maven.
     *
     * @throws IOException on plumbing failures (JVM boot, exit-code file)
     * @throws HotspotGoalFailedException when the delegated Maven invocation returns non-zero
     */
    static synchronized void execute(MavenSession session, MojoExecution mojoExecution) throws IOException {
        List<String> args = buildArgs(session, mojoExecution);
        Path exitFile = Files.createTempFile("nmvn-jvm-exit", ".txt");
        try {
            List<String> mainArgs = new ArrayList<>();
            mainArgs.add(exitFile.toString());
            mainArgs.addAll(args);
            LOG.info("nmvn: running {} on HotSpot JVM: mvn {}", mojoExecution, String.join(" ", args));
            try {
                jvm().executeMain(MAIN_CLASS.replace('.', '/'), mainArgs.toArray(new String[0]));
            } catch (Exception e) {
                throw new IOException("HotSpot-side wrapper not found — bad java.class.path?", e);
            }
            int exitCode = readExitCode(exitFile);
            if (exitCode != 0) {
                throw new HotspotGoalFailedException(
                        "Goal " + goalSpec(mojoExecution) + " failed on the HotSpot JVM (exit code " + exitCode + ")");
            }
        } finally {
            Files.deleteIfExists(exitFile);
        }
    }

    /** Non-zero exit of the delegated Maven run — the plugin itself failed, not the plumbing. */
    static final class HotspotGoalFailedException extends RuntimeException {
        HotspotGoalFailedException(String message) {
            super(message);
        }
    }

    private static int readExitCode(Path exitFile) throws IOException {
        String content = Files.readString(exitFile).trim();
        if (content.isEmpty()) {
            // The wrapper writes the file even on Throwable; nothing at all means the JVM-side
            // main never ran to completion (e.g. a JNI-level failure executeMain cannot report).
            throw new IOException("HotSpot JVM reported no exit code — wrapper main did not complete");
        }
        try {
            return Integer.parseInt(content);
        } catch (NumberFormatException e) {
            throw new IOException("Unparseable exit code from HotSpot JVM: '" + content + "'");
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

    /**
     * Boots the HotSpot JVM with the same shape the {@code mvn} script uses: classpath =
     * {@code $MAVEN_HOME/boot/*.jar} (classworlds) plus the extracted wrapper, m2.conf as the
     * classworlds configuration, and the maven.home/maven.multiModuleProjectDirectory system
     * properties NmvnLauncher already established for the native side.
     */
    private static JVM boot() throws IOException {
        String javaHome = System.getProperty("java.home");
        String mavenHome = System.getProperty("maven.home");
        if (javaHome == null || mavenHome == null) {
            throw new IOException("java.home/maven.home not set — NmvnLauncher should have set both");
        }
        File bootDir = new File(mavenHome, "boot");
        File[] bootJars = bootDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (bootJars == null || bootJars.length == 0) {
            throw new IOException("No classworlds jar under " + bootDir);
        }
        StringBuilder classpath = new StringBuilder(extractWrapper().toString());
        for (File jar : bootJars) {
            classpath.append(File.pathSeparatorChar).append(jar.getAbsolutePath());
        }
        List<String> options = new ArrayList<>();
        options.add("-Djava.class.path=" + classpath);
        options.add("-Dmaven.home=" + mavenHome);
        options.add("-Dclassworlds.conf=" + new File(new File(mavenHome, "bin"), "m2.conf"));
        String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModuleDir != null) {
            options.add("-Dmaven.multiModuleProjectDirectory=" + multiModuleDir);
        }
        LOG.info("nmvn: booting in-process HotSpot JVM from {} for non-baked plugins", javaHome);
        return JVM.create(new File(javaHome), options.toArray(new String[0]));
    }

    /**
     * Writes the wrapper main's bytecode (baked into the image as a resource — see the
     * IncludeResources pattern in build-nmvn-prebuilt.sh) into a temp directory that goes on the
     * HotSpot classpath. The wrapper is a SINGLE class file by contract (see its javadoc).
     */
    private static Path extractWrapper() throws IOException {
        String resource = MAIN_CLASS.replace('.', '/') + ".class";
        Path dir = Files.createTempDirectory("nmvn-jvm-boot");
        Path classFile = dir.resolve(resource);
        Files.createDirectories(classFile.getParent());
        try (InputStream in = HotspotMavenRunner.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Resource " + resource + " not baked into the image"
                        + " — missing -H:IncludeResources='nmvn/hotspot/.*' in the build script?");
            }
            Files.copy(in, classFile);
        }
        return dir;
    }
}
