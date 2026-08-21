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

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * A FAILING delegated goal must fail the nmvn build. The {@code impossible-rule} profile of
 * {@code examples/jvm-fallback-project} adds an enforcer rule no Maven can satisfy
 * ({@code requireMavenVersion [99,)}), and enforcer is non-baked — so the failure must travel
 * back through the fallback's exit-code plumbing (temp file, readExitCode,
 * HotspotGoalFailedException). If that plumbing regresses, nmvn reports BUILD SUCCESS for
 * builds whose delegated goals failed, and no green CI would notice.
 */
@DisplayName("a failing delegated goal fails the build")
class FailurePropagationTest {

    private static final Path PROJECT = NmvnBinary.repoRoot().resolve("examples/jvm-fallback-project");

    private static NmvnBinary.Run build;

    @BeforeAll
    static void cleanPackageTheFixtureProjectWithItsImpossibleRule() {
        build = NmvnBinary.run(PROJECT, "clean", "package", "-Pimpossible-rule");
    }

    @Test
    @DisplayName("the impossible enforcer rule fails the build")
    void impossibleRuleFailsTheBuild() {
        build.assertFailed(
                "the impossible enforcer rule did not fail the build — exit-code propagation is broken");
    }

    @Test
    @EnabledIf(value = "nmvn.e2e.NmvnBinary#isNonCrema",
            disabledReason = "crema runs the goal natively — no fallback exit-code plumbing involved")
    @DisplayName("non-crema: the failure came through the fallback's exit-code plumbing")
    void failureCameThroughHotspotExitCodePlumbing() {
        build.assertOutputContains("failed on the HotSpot JVM (exit code",
                "build failed, but not via the fallback exit-code plumbing"
                        + " (HotspotGoalFailedException marker missing)");
    }
}
