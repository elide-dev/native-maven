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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code --mode=legacy} contract (NATIVEMVN.md "Modes"): the WHOLE build runs as one
 * stock-Maven batch on the in-process HotSpot JVM — the launcher short-circuits before the baked
 * world boots, so no per-goal delegation happens at all. The build must succeed with the same
 * on-disk proof the mixed-mode test demands (jar + antrun marker), and a FAILING legacy build
 * must fail the process: the batch's exit code travels
 * {@code HotspotMavenMain.run -> runFullBuild -> System.exit}.
 *
 * <p>Non-crema only: crema has no modes ({@link CremaModeRejectionTest}).
 */
@EnabledIf(
        value = "nmvn.e2e.NmvnBinary#isNonCrema",
        disabledReason = "crema has no modes — CremaModeRejectionTest covers its --mode rejection")
@DisplayName("--mode=legacy: the whole build as one stock-Maven batch on the HotSpot JVM")
class LegacyModeTest {

    private static final Path PROJECT = NmvnBinary.repoRoot().resolve("examples/jvm-fallback-project");

    private static NmvnBinary.Run failing;
    private static NmvnBinary.Run build;

    @BeforeAll
    static void cleanPackageTheFixtureProjectInLegacyMode() {
        // the failing build FIRST: its `clean` wipes target/, so the on-disk proof the file
        // assertions check must come from the run after it
        failing = NmvnBinary.run(PROJECT, "--mode=legacy", "clean", "package", "-Pimpossible-rule");
        build = NmvnBinary.run(PROJECT, "--mode=legacy", "clean", "package");
    }

    @Test
    @DisplayName("the build succeeds")
    void buildSucceeds() {
        build.assertSucceeded("--mode=legacy clean package failed");
    }

    @Test
    @DisplayName("the jar is produced")
    void jarIsProduced() throws IOException {
        // glob, not an exact name: CI strips -SNAPSHOT from the fixture pom's version
        try (DirectoryStream<Path> jars =
                Files.newDirectoryStream(PROJECT.resolve("target"), "jvm-fallback-sample-*.jar")) {
            assertTrue(jars.iterator().hasNext(), "the legacy build succeeded but produced no jar");
        }
    }

    @Test
    @DisplayName("antrun's marker file exists — the goals really executed")
    void antrunMarkerFileExists() {
        assertTrue(
                Files.isRegularFile(PROJECT.resolve("target/fallback-marker.txt")),
                "the legacy build's antrun marker file is missing");
    }

    @Test
    @DisplayName("the launcher took the legacy short-circuit")
    void launcherTookTheLegacyShortCircuit() {
        build.assertOutputContains(
                "legacy mode — running the whole build",
                "legacy marker line missing — the launcher did not take the legacy short-circuit");
    }

    @Test
    @DisplayName("no per-goal delegation happened — the build was one batch")
    void noPerGoalDelegationHappened() {
        build.assertOutputLacks(
                "on HotSpot JVM: mvn",
                "legacy mode delegated per-goal — the whole build should have been one batch,"
                        + " so the launcher missed the short-circuit and Maven booted natively");
    }

    @Test
    @DisplayName("a failing legacy build propagates its exit code")
    void failingLegacyBuildPropagatesItsExitCode() {
        failing.assertFailed("the impossible enforcer rule did not fail the legacy build —"
                + " legacy exit-code propagation is broken");
    }
}
