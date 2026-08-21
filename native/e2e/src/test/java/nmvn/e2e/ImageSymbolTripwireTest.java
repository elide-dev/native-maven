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
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guard for the linux-hide-static-jdk-symbols profile (native/launcher/pom.xml): if the image
 * dynamically exports the statically-linked JDK's JNU_* symbols again, the fallback child's
 * libjava.so binds to them and dies during boot — report the real reason here instead of the
 * downstream boot NPE.
 */
@EnabledOnOs(OS.LINUX)
@DisplayName("image symbol tripwire (Linux)")
class ImageSymbolTripwireTest {

    @Test
    @DisplayName("the image does not dynamically export the static JDK's JNU_* symbols")
    void imageDoesNotExportJnuSymbols() throws IOException, InterruptedException {
        Process nm = new ProcessBuilder(List.of("nm", "-D", NmvnBinary.binary().toString()))
                .redirectErrorStream(true)
                .start();
        String symbols = new String(nm.getInputStream().readAllBytes());
        assumeTrue(nm.waitFor() == 0, "nm -D not usable on this machine — skipping the tripwire");

        List<String> jnu = symbols.lines().filter(line -> line.contains(" JNU_")).toList();
        assertTrue(jnu.isEmpty(),
                () -> "image exports JNU_* symbols — exclude-libs,ALL stopped working, the"
                        + " fallback child JVM will crash booting:\n" + String.join("\n", jnu));
    }
}
