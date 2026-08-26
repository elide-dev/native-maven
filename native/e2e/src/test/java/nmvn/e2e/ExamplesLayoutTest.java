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

/**
 * The spring test classes hardcode one test per example project, and their flavor gate means
 * they don't even run without a matching binary — so directory drift needs a guard that ALWAYS
 * runs. This class needs no binary at all: it only compares each {@code examples/spring}
 * directory against the corresponding test class's hardcoded list.
 */
@DisplayName("examples/spring directories match the hardcoded spring test classes")
class ExamplesLayoutTest {

    @Test
    @DisplayName("examples/spring/410 matches Spring410Test")
    void spring410ExamplesAllHaveTests() throws IOException {
        List<String> tested = List.of(
                "java-jpa-jar",
                "java-native-jpa-jar",
                "java-no-deps-jar",
                "java-vaadim",
                "java-web-jar",
                "java-web-war");
        assertDirectoryMatches("examples/spring/410", tested, Spring410Test.class);
    }

    @Test
    @DisplayName("examples/spring/407 matches Spring407Test")
    void spring407ExamplesAllHaveTests() throws IOException {
        assertDirectoryMatches("examples/spring/407", List.of("java-hibernate", "java-web-jar"), Spring407Test.class);
    }

    private static void assertDirectoryMatches(String examplesDir, List<String> tested, Class<?> testClass)
            throws IOException {
        Path dir = NmvnBinary.repoRoot().resolve(examplesDir);
        assertEquals(
                tested,
                projectsOnDisk(dir),
                dir + " changed — add/remove the matching test in " + testClass.getSimpleName());
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
