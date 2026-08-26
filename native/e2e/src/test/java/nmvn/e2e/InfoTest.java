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

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code --info} introspection contract (NmvnLauncher.printInfo): the binary's self-report
 * of flavor, variant, and baked plugins. The REST OF THIS SUITE keys off these answers — spring
 * example gating on {@code flavor}, {@code @EnabledIf} variant gating on {@code variant} — so a
 * regression here silently mis-routes every other test; this class fails loudly instead.
 */
@DisplayName("--info: the binary's self-report")
class InfoTest {

    @BeforeAll
    static void requireAnInfoCapableBinary() {
        // Aborts the whole class ("skipped", with the rebuild hint) when the binary under test
        // predates --info — its direct invocations below would otherwise fail confusingly.
        NmvnBinary.info();
    }

    @Test
    @DisplayName("flavor is generic or spring@<bootVersion>, consistent with the baked plugins")
    void flavorIsDerivedFromTheBakeSet() {
        NmvnBinary.BinaryInfo info = NmvnBinary.info();
        String bootPrefix = "org.springframework.boot:spring-boot-maven-plugin:";
        List<String> bakedBoot = info.plugins().stream()
                .filter(gav -> gav.startsWith(bootPrefix))
                .toList();
        if (info.flavor().equals("generic")) {
            assertTrue(bakedBoot.isEmpty(), "flavor says generic but spring-boot-maven-plugin is baked: " + bakedBoot);
        } else {
            assertEquals(
                    List.of(bootPrefix + info.flavor().replaceFirst("^spring@", "")),
                    bakedBoot,
                    "flavor '" + info.flavor() + "' must mean exactly that spring-boot-maven-plugin"
                            + " version is baked (flavor is DERIVED from the bake set, never a label)");
        }
    }

    @Test
    @DisplayName("variant is crema or hotspot")
    void variantIsCremaOrHotspot() {
        assertTrue(
                List.of("crema", "hotspot").contains(NmvnBinary.info().variant()),
                "unexpected --info variant: " + NmvnBinary.info().variant());
    }

    @Test
    @DisplayName("--info=<topic> prints the bare value, for scripts")
    void topicFormPrintsBareValue() {
        NmvnBinary.Run run = NmvnBinary.run(NmvnBinary.repoRoot(), "--info=flavor");
        run.assertSucceeded("--info=flavor failed");
        assertEquals(
                NmvnBinary.info().flavor(),
                run.output().trim(),
                "--info=flavor must print exactly the flavor and nothing else");
    }

    @Test
    @DisplayName("an unknown --info topic fails with the usage message")
    void unknownTopicIsRejected() {
        NmvnBinary.Run run = NmvnBinary.run(NmvnBinary.repoRoot(), "--info=bogus");
        run.assertFailed("--info=bogus was accepted");
        run.assertOutputContains("Usage: --info", "unknown --info topic failed without the usage message");
    }

    @Test
    @DisplayName("--info answers without booting Maven (no goals, no project needed)")
    void infoAnswersWithoutMaven() {
        NmvnBinary.Run run = NmvnBinary.run(NmvnBinary.repoRoot(), "--info");
        run.assertSucceeded("--info failed");
        assertFalse(
                run.output().contains("Scanning for projects"),
                "--info booted Maven — it must exit before any Maven machinery");
    }
}
