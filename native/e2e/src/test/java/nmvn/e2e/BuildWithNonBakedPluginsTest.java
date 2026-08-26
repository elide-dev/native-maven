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
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@code clean package} of {@code examples/jvm-fallback-project}, run with the binary
 * under test ({@code -Dnmvn.binary}). That fixture's pom binds two plugins that are never baked
 * into an nmvn image — enforcer's {@code enforce@require-maven} and antrun's
 * {@code run@write-marker} — plus a VERSION-MISMATCHED baked plugin (clean, pinned to 3.1.0
 * where every flavor bakes newer), so all three MUST run outside the baked realms: via the
 * per-goal HotSpot delegation on non-crema, via runtime class loading on crema (the pom's
 * comments explain the plugin choices). One build in {@link BeforeAll}, every invariant a named
 * test against it.
 */
@DisplayName("clean package with non-baked plugins in the lifecycle")
class BuildWithNonBakedPluginsTest {

    private static final Path PROJECT = NmvnBinary.repoRoot().resolve("examples/jvm-fallback-project");

    private static NmvnBinary.Run build;

    @BeforeAll
    static void cleanPackageTheFixtureProject() {
        build = NmvnBinary.run(PROJECT, "clean", "package");
    }

    @Test
    @DisplayName("the build succeeds")
    void buildSucceeds() {
        build.assertSucceeded("clean package failed — the fallback (or crema runtime loading) is broken");
    }

    // The build's word is not enough: the jar and the antrun marker file prove the delegated
    // goals actually EXECUTED, not just reported success.

    @Test
    @DisplayName("the jar is produced")
    void jarIsProduced() throws IOException {
        // glob, not an exact name: CI strips -SNAPSHOT from the fixture pom's version
        try (DirectoryStream<Path> jars =
                Files.newDirectoryStream(PROJECT.resolve("target"), "jvm-fallback-sample-*.jar")) {
            assertTrue(jars.iterator().hasNext(), "clean package succeeded but produced no jar");
        }
    }

    @Test
    @DisplayName("antrun's marker file exists — the delegated goal really executed")
    void antrunMarkerFileExists() {
        assertTrue(
                Files.isRegularFile(PROJECT.resolve("target/fallback-marker.txt")),
                "antrun's marker file is missing — the delegated goal reported success without executing");
    }

    @Test
    @EnabledIf(
            value = "nmvn.e2e.NmvnBinary#isNonCrema",
            disabledReason = "crema serves non-baked plugins natively — no delegation markers")
    @DisplayName("non-crema: enforcer:enforce delegated to the HotSpot JVM with its execution id")
    void enforcerDelegatesWithExecutionId() {
        build.assertDelegated("maven-enforcer-plugin:3.6.3:enforce", "require-maven");
    }

    @Test
    @EnabledIf(
            value = "nmvn.e2e.NmvnBinary#isNonCrema",
            disabledReason = "crema serves non-baked plugins natively — no delegation markers")
    @DisplayName("non-crema: antrun:run delegated to the HotSpot JVM with its execution id")
    void antrunDelegatesWithExecutionId() {
        build.assertDelegated("maven-antrun-plugin:3.1.0:run", "write-marker");
    }

    @Test
    @EnabledIf(
            value = "nmvn.e2e.NmvnBinary#isNonCrema",
            disabledReason = "crema serves non-baked plugins natively — no delegation markers")
    @DisplayName("non-crema: a version-mismatched BAKED plugin (clean, pinned older) delegates too")
    void versionMismatchedCleanDelegates() {
        build.assertDelegated("maven-clean-plugin:3.1.0:clean", "default-clean");
    }

    /**
     * Premise guard for {@link #versionMismatchedCleanDelegates}: that test only proves the
     * version gate if this image really bakes a clean version OTHER than the fixture's pinned
     * 3.1.0 — checked against the binary's own {@code --info=plugins} answer, so a bake-set
     * change surfaces here as one clear failure instead of a confusing missing-marker one.
     */
    @Test
    @DisplayName("the fixture's pinned clean 3.1.0 really mismatches this image's baked clean")
    void pinnedCleanVersionMismatchesTheBake() {
        List<String> bakedClean = NmvnBinary.info().plugins().stream()
                .filter(gav -> gav.startsWith("org.apache.maven.plugins:maven-clean-plugin:"))
                .toList();
        assertFalse(
                bakedClean.contains("org.apache.maven.plugins:maven-clean-plugin:3.1.0"),
                "this image bakes clean 3.1.0 — the fixture's pin no longer mismatches; pick an"
                        + " older pin in examples/jvm-fallback-project/pom.xml");
        assertFalse(
                bakedClean.isEmpty(),
                "this image bakes no clean plugin at all — versionMismatchedCleanDelegates no"
                        + " longer tests the version gate, only plain non-baked delegation");
    }
}
