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
package nmvn.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The nmvn binary under test, configured via system properties (wired through surefire, see
 * pom.xml):
 *
 * <ul>
 *   <li>{@code nmvn.binary} — REQUIRED: path to the built native binary, absolute or relative to
 *       the repo root (e.g. {@code build/nmvn-spring-4.1.0}). Tests never build images; without
 *       this property every test aborts ("skipped") with the reason
 *   <li>{@code nmvn.spring} — the Spring Boot version the binary was baked for; the spring
 *       example tests require it (see {@link #binaryForSpring})
 *   <li>{@code nmvn.variant} — {@code non-crema} (default) or {@code crema}; crema serves
 *       non-baked plugins via runtime class loading, so the delegation-marker tests don't apply
 *   <li>{@code nmvn.maven.home} — Maven dist the binary runs against (default:
 *       {@code apache-maven/target/apache-maven-4.1.0-SNAPSHOT} under the repo root)
 * </ul>
 *
 * The class only resolves that configuration and runs binaries; WHAT to build is the caller's:
 * every {@code run} variant takes the project directory explicitly.
 */
public final class NmvnBinary {

    public static final long BUILD_TIMEOUT_MINUTES = 15;

    private NmvnBinary() {}

    /**
     * The repo root, against which every repo-relative input resolves ({@code -Dnmvn.binary},
     * the fixture and example projects, the default Maven home). Tests do not run there —
     * surefire's working directory is the module dir, an IDE launch may use yet another — so
     * walk up until Maven's own root marker, the {@code .mvn} directory, appears.
     */
    public static Path repoRoot() {
        Path dir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
                .toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(".mvn"))) {
                return candidate;
            }
        }
        throw new AssertionError("could not locate the repo root (no .mvn directory) above " + dir);
    }

    /**
     * The binary under test. {@code -Dnmvn.binary} is required — tests never build images, so
     * without it the calling test aborts (JUnit "skipped") with this reason.
     */
    public static Path binary() {
        String name = property("nmvn.binary");
        if (name == null) {
            throw new TestAbortedException("pass -Dnmvn.binary=<built nmvn image> (absolute or"
                    + " relative to the repo root, e.g. build/nmvn-generic; see native/BUILDING.md"
                    + " for how to build one)");
        }
        Path binary = repoRoot().resolve(name).normalize();
        assertTrue(Files.isRegularFile(binary), "nmvn binary not found: " + binary);
        return binary;
    }

    /**
     * The binary under test, asserted to be baked for the given Spring Boot version:
     * {@code -Dnmvn.spring} declares what {@code -Dnmvn.binary} was baked for, and examples of
     * any other version abort rather than run against the wrong bake.
     */
    public static Path binaryForSpring(String fullVersion) {
        String declared = property("nmvn.spring");
        if (declared == null) {
            throw new TestAbortedException("-Dnmvn.spring is not set — declare which Spring"
                    + " version the binary under test was baked for, e.g. -Dnmvn.spring=" + fullVersion);
        }
        if (!digits(declared).equals(digits(fullVersion))) {
            throw new TestAbortedException("the binary under test is declared for Spring " + declared
                    + " — not running Spring " + fullVersion + " examples against it");
        }
        return binary();
    }

    /** "4.1.0" and "410" are the same version; compare on digits. */
    private static String digits(String version) {
        return version.replace(".", "");
    }

    public static Path mavenHome() {
        String home = property("nmvn.maven.home");
        return home != null
                ? repoRoot().resolve(home).normalize()
                : repoRoot().resolve("apache-maven/target/apache-maven-4.1.0-SNAPSHOT");
    }

    public static String variant() {
        return isCrema() ? "crema" : "non-crema";
    }

    public static boolean isNonCrema() {
        return !"crema".equals(property("nmvn.variant"));
    }

    public static boolean isCrema() {
        return !isNonCrema();
    }

    /** Runs the binary under test ({@link #binary()}) in the given project directory. */
    public static Run run(Path projectDir, String... args) {
        return runBinaryIn(binary(), projectDir, List.of(args));
    }

    /** Runs an arbitrary nmvn binary in an arbitrary directory with MAVEN_HOME set. */
    public static Run runBinaryIn(Path binary, Path workDir, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        // The binary needs MAVEN_HOME at run time (m2.conf, boot jars).
        pb.environment().put("MAVEN_HOME", mavenHome().toString());
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("timed out after " + BUILD_TIMEOUT_MINUTES + " minutes: "
                        + String.join(" ", command) + "\n--- output so far ---\n" + output);
            }
            return new Run(String.join(" ", command), process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("could not run " + String.join(" ", command), e);
        }
    }

    /** The system property's value, or null when unset/blank (surefire wires blanks for unset -D). */
    static String property(String name) {
        String value = System.getProperty(name, "").trim();
        return value.isEmpty() ? null : value;
    }

    /** One invocation of the binary: the command, its exit code, and merged stdout+stderr. */
    public record Run(String command, int exitCode, String output) {

        public boolean succeeded() {
            return exitCode == 0;
        }

        public void assertSucceeded(String why) {
            assertTrue(succeeded(), () -> why + dump());
        }

        public void assertFailed(String why) {
            assertFalse(succeeded(), () -> why + dump());
        }

        public void assertOutputContains(String needle, String why) {
            assertTrue(output.contains(needle),
                    () -> why + " (expected output to contain: \"" + needle + "\")" + dump());
        }

        public void assertOutputLacks(String needle, String why) {
            assertFalse(output.contains(needle),
                    () -> why + " (output must NOT contain: \"" + needle + "\")" + dump());
        }

        /**
         * The delegation marker printed by nmvn.HotspotMavenRunner for every delegated goal:
         * both the human-readable line and the goal@executionId in the delegated command (the
         * child selects the pom's &lt;execution&gt; block by that id).
         */
        public void assertDelegated(String pluginVersionGoal, String executionId) {
            assertOutputContains(
                    "running org.apache.maven.plugins:" + pluginVersionGoal
                            + " {execution: " + executionId + "} on HotSpot JVM",
                    pluginVersionGoal + "@" + executionId
                            + " did not delegate to the HotSpot JVM (marker line missing)");
            assertOutputContains(
                    pluginVersionGoal + "@" + executionId,
                    "delegated command lost the execution id — missing from the goalSpec");
        }

        /** The last {@code lines} lines of the output — Maven build logs are too long to dump whole. */
        public String outputTail(int lines) {
            java.util.List<String> all = output.lines().toList();
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }

        private String dump() {
            return "\n--- " + command + " exited " + exitCode + " ---\n" + output;
        }
    }
}
