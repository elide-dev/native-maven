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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke-builds every example project under {@code examples/spring/407} with the binary under
 * test, which {@code -Dnmvn.spring} must declare as baked for Spring Boot 4.0.7
 * ({@link NmvnBinary#binaryForSpring}) — with any other declaration these tests abort rather
 * than run against the wrong bake. One hardcoded test per project;
 * {@link #everyExampleDirectoryHasATest} fails loudly when the directory and this class drift
 * apart.
 */
@DisplayName("Spring Boot 4.0.7 examples build with the 4.0.7-baked binary")
class Spring407Test {

    private static final String SPRING_VERSION = "4.0.7";
    private static final Path EXAMPLES = NmvnBinary.repoRoot().resolve("examples/spring/407");

    @Test
    void javaHibernate() {
        buildsCleanPackage("java-hibernate");
    }

    @Test
    void javaWebJar() {
        buildsCleanPackage("java-web-jar");
    }

    /** The tests above are hardcoded — this guard fails when the examples directory drifts. */
    @Test
    @DisplayName("every example directory has a test in this class")
    void everyExampleDirectoryHasATest() throws IOException {
        List<String> tested = List.of("java-hibernate", "java-web-jar");
        assertEquals(tested, projectsOnDisk(EXAMPLES),
                EXAMPLES + " changed — add/remove the matching test in " + getClass().getSimpleName());
    }

    private void buildsCleanPackage(String project) {
        Path binary = NmvnBinary.binaryForSpring(SPRING_VERSION);
        NmvnBinary.Run build = NmvnBinary.runBinaryIn(
                binary, EXAMPLES.resolve(project), List.of("-B", "clean", "package", "-DskipTests=true"));
        assertTrue(build.succeeded(), () -> project + " failed (exit " + build.exitCode()
                + ") — last 150 output lines:\n" + build.outputTail(150));
    }

    /** Sorted names of the example projects (subdirectories with a pom.xml). */
    private static List<String> projectsOnDisk(Path examplesDir) throws IOException {
        List<String> projects = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(examplesDir, Files::isDirectory)) {
            for (Path dir : dirs) {
                if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                    projects.add(dir.getFileName().toString());
                }
            }
        }
        Collections.sort(projects);
        return projects;
    }
}
