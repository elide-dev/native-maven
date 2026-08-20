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
 * {@code --mode} is a NON-CREMA feature (NATIVEMVN.md "Modes"): a crema image has one behavior
 * only — baked plugins from their realms, everything else natively via runtime class loading —
 * so its launcher must refuse the flag outright, before any Maven machinery, instead of silently
 * accepting a policy it cannot honor.
 */
@EnabledIf(
        value = "nmvn.e2e.NmvnBinary#isCrema",
        disabledReason = "--mode is a real flag on non-crema — the rejection is the crema contract")
@DisplayName("crema has no modes: --mode is rejected outright")
class CremaModeRejectionTest {

    private static final Path PROJECT = NmvnBinary.repoRoot().resolve("examples/jvm-fallback-project");

    private static NmvnBinary.Run run;

    @BeforeAll
    static void invokeTheCremaBinaryWithMode() {
        run = NmvnBinary.run(PROJECT, "--mode=mixed", "clean", "package");
    }

    @Test
    @DisplayName("the invocation fails")
    void modeIsRejected() {
        run.assertFailed("the crema binary accepted --mode — the flag must be non-crema-only");
    }

    @Test
    @DisplayName("the error says --mode is not supported by this binary")
    void errorSaysModeIsNotSupported() {
        run.assertOutputContains(
                "--mode is not supported", "--mode failed on crema, but without the not-supported error message");
    }

    @Test
    @DisplayName("the rejection happens before any Maven machinery")
    void rejectionHappensBeforeMaven() {
        run.assertOutputLacks(
                "Scanning for projects", "--mode booted Maven on crema — the launcher must reject the flag first");
    }
}
