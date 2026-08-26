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
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke-builds every example project under {@code examples/spring/410} with the binary under
 * test — enabled only when that binary reports itself ({@code --info=flavor}) as baked for
 * Spring Boot 4.1.0, so these tests can never run against the wrong bake. One hardcoded test
 * per project; {@link ExamplesLayoutTest} fails loudly when the directory and this class drift
 * apart.
 */
@EnabledIf(
        value = "nmvn.e2e.NmvnBinary#isSpring410",
        disabledReason = "the binary under test does not report flavor spring@4.1.0 (--info=flavor)")
@DisplayName("Spring Boot 4.1.0 examples build with the 4.1.0-baked binary")
class Spring410Test {

    private static final Path EXAMPLES = NmvnBinary.repoRoot().resolve("examples/spring/410");

    @Test
    void javaJpaJar() {
        buildsCleanPackage("java-jpa-jar");
    }

    @Test
    void javaNoDepsJar() {
        buildsCleanPackage("java-no-deps-jar");
    }

    @Test
    void javaWebJar() {
        buildsCleanPackage("java-web-jar");
    }

    @Test
    @EnabledIf(
            value = "nmvn.e2e.NmvnBinary#isCrema",
            disabledReason = "needs runtime class loading — cannot build on a hotspot-variant image")
    void javaNativeJpaJar() {
        buildsCleanPackage("java-native-jpa-jar");
    }

    @Test
    @EnabledIf(
            value = "nmvn.e2e.NmvnBinary#isCrema",
            disabledReason = "needs runtime class loading — cannot build on a hotspot-variant image")
    void javaVaadim() {
        buildsCleanPackage("java-vaadim");
    }

    @Test
    @EnabledIf(
            value = "nmvn.e2e.NmvnBinary#isCrema",
            disabledReason = "maven-war-plugin's Servlet 3.0 probe needs runtime class loading"
                    + " (non-crema-war-servlet-probe.md)")
    void javaWebWar() {
        buildsCleanPackage("java-web-war");
    }

    private void buildsCleanPackage(String project) {
        NmvnBinary.Run build = NmvnBinary.runBinaryIn(
                NmvnBinary.binary(), EXAMPLES.resolve(project), List.of("-B", "clean", "package", "-DskipTests=true"));
        assertTrue(
                build.succeeded(),
                () -> project + " failed (exit " + build.exitCode() + ") — last 150 output lines:\n"
                        + build.outputTail(150));
    }
}
