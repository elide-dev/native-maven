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
 * The {@code --mode=native} contract (NATIVEMVN.md "Modes", the DEFAULT mode): a non-baked
 * plugin in the lifecycle ends the build with the not-baked error that suggests follow-up
 * actions ({@code --mode=mixed} among them), instead of delegating silently or dying on a raw
 * ClassNotFoundException. Both spellings must fail identically: the baked default (no flag) and
 * the explicit {@code --mode=native} (which also pins the launcher's {@code --mode} parsing).
 * An unknown {@code --mode} value dies on the launcher's usage error before any Maven machinery.
 *
 * <p>Non-crema only: crema has no modes — its runtime class loading serves non-baked plugins
 * natively under the one behavior it has, and its launcher rejects the flag outright
 * ({@link CremaModeRejectionTest}).
 */
@EnabledIf(
        value = "nmvn.e2e.NmvnBinary#isNonCrema",
        disabledReason = "crema has no modes — CremaModeRejectionTest covers its --mode rejection")
@DisplayName("--mode=native (the default) fails fast on non-baked plugins")
class NativeModeFailFastTest {

    private static final Path PROJECT = NmvnBinary.repoRoot().resolve("examples/jvm-fallback-project");

    private static NmvnBinary.Run defaultMode;
    private static NmvnBinary.Run explicitNative;

    @BeforeAll
    static void cleanPackageWithoutOptingIntoTheFallback() {
        defaultMode = NmvnBinary.run(PROJECT, "clean", "package");
        explicitNative = NmvnBinary.run(PROJECT, "--mode=native", "clean", "package");
    }

    @Test
    @DisplayName("the default mode fails the build with the not-baked error")
    void defaultModeFailsWithTheNotBakedError() {
        assertFailsFastOnTheNonBakedPlugins(defaultMode, "the default mode");
    }

    @Test
    @DisplayName("explicit --mode=native fails identically")
    void explicitNativeModeFailsIdentically() {
        assertFailsFastOnTheNonBakedPlugins(explicitNative, "--mode=native");
    }

    private static void assertFailsFastOnTheNonBakedPlugins(NmvnBinary.Run run, String spelling) {
        run.assertFailed(spelling + " built with non-baked plugins in the lifecycle — the fail-fast is gone");
        run.assertOutputContains(
                "not baked into this Native Maven binary",
                spelling + " failed, but without the not-baked error message");
        run.assertOutputContains("--mode=mixed", "the not-baked error lost its --mode=mixed suggestion");
    }

    @Test
    @DisplayName("an unknown --mode value dies on the usage error, before any Maven machinery")
    void unknownModeValueIsRejectedWithTheUsageError() {
        NmvnBinary.Run run = NmvnBinary.run(PROJECT, "--mode=turbo", "clean", "package");
        run.assertFailed("an unknown --mode value was accepted");
        run.assertOutputContains(
                "supported: --mode=native, --mode=mixed, --mode=legacy",
                "unknown --mode value failed, but without the usage message listing supported values");
        run.assertOutputLacks(
                "Scanning for projects", "an unknown --mode value booted Maven — the launcher must reject it first");
    }
}
