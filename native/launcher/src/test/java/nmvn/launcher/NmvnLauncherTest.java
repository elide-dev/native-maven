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
package nmvn.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;

import nmvn.PrebuiltRoutingLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NmvnLauncherTest {

    private static File javaMavenSampleProject;
    private ByteArrayOutputStream stdOutErr;

    public NmvnLauncherTest() {}

    @BeforeAll
    public static void findSampleProjects() throws Exception {
        var url = NmvnLauncherTest.class.getProtectionDomain().getCodeSource().getLocation();
        var file = new File(url.toURI());
        var dir = file.getParentFile();
        while (dir != null) {
            var prj = child(dir, "examples", "java-maven-sample-project");
            if (prj.isDirectory()) {
                javaMavenSampleProject = prj;
                break;
            }
            dir = dir.getParentFile();
        }
        assertNotNull(javaMavenSampleProject, "Sample project found");
    }

    @BeforeAll
    public static void unsetSomeMavenProperties() throws Exception {
        System.getProperties().remove("test");
    }

    @AfterAll
    public static void tearDownClass() {}

    @BeforeEach
    public void setUp() {
        System.getProperties().remove("org.graalvm.nativeimage.imagecode");
        PrebuiltRoutingLog.reset();
        this.stdOutErr = new ByteArrayOutputStream();
    }

    @AfterEach
    public void tearDown() {}

    @Test
    public void cleanAndBuildTheSampleProject() throws Exception {
        String[] args = new String[] { //
            "-f",
            javaMavenSampleProject.getCanonicalPath(), //
            "clean",
            "package" //
        };
        var exitCode = NmvnLauncher.runMain(args, null, stdOutErr, stdOutErr);
        assertEquals(0, exitCode, "Executes without issues");

        var nmvnLog = stdOutErr
                .toString()
                .lines()
                .filter(l -> l.contains("nmvn: plugin"))
                .toList();

        assertEquals(5, nmvnLog.size(), "Five lines");
        // nmvn: plugin org.apache.maven.plugins:maven-clean-plugin:3.4.0 → DIRECT (no other jvm)
        for (var l : nmvnLog) {
            assertTrue(l.contains("DIRECT (no other jvm)"), "Unexpected: " + l);
        }
    }

    @Test
    public void mockDualJvmCleanAndBuildTheSampleProject() throws Exception {
        // pretend we are running in a native image
        System.getProperties().setProperty("org.graalvm.nativeimage.imagecode", "runtime");

        String[] args = new String[] { //
            "-f",
            javaMavenSampleProject.getCanonicalPath(), //
            "clean",
            "package" //
        };
        var exitCode = NmvnLauncher.runMain(args, null, stdOutErr, stdOutErr);
        assertEquals(0, exitCode, "Executes without issues");

        var nmvnLog = stdOutErr
                .toString()
                .lines()
                .filter(l -> l.contains("nmvn: plugin"))
                .toList();

        assertEquals(5, nmvnLog.size(), "Five lines");
        // nmvn: plugin org.apache.maven.plugins:maven-clean-plugin:3.4.0 → DIRECT (no other jvm)
        for (var l : nmvnLog) {
            assertTrue(l.contains("DYNAMIC (no prebuilt plugins in this image)"), "Unexpected: " + l);
        }
    }

    private static File child(File dir, String... children) {
        var f = dir;
        for (var ch : children) {
            f = new File(f, ch);
        }
        return f;
    }
}
