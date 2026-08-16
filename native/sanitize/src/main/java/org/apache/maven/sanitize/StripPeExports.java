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
package org.apache.maven.sanitize;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Zeroes the PE export directory of the freshly built native image. Windows only: skipped on
 * every other OS, and on Windows a missing {@code .exe} FAILS the build (a silent skip there
 * would ship an image whose JVM fallback crashes).
 *
 * <p>The image statically links the JDK's native libraries (libjava, libzip, libnet, libnio,
 * ...). On Windows their JNIEXPORT functions carry {@code __declspec(dllexport)}, so linking
 * them into the executable puts them into its EXPORT TABLE — including {@code JNI_OnLoad_zip} /
 * {@code JNI_OnLoad_nio} / {@code JNI_OnLoad_net}. When the JVM fallback boots a HotSpot child
 * inside this process (jvm-channel, {@code JNI_CreateJavaVM}), HotSpot's statically-linked-
 * library detection (JEP 178) probes the process for exactly those {@code JNI_OnLoad_<lib>}
 * names, finds them in the .exe, concludes zip/nio/net are "statically linked", and resolves the
 * child's native methods against the IMAGE's SubstrateVM-compiled copies. Those use the image's
 * JNI state, so the child corrupts memory and dies (EXCEPTION_ACCESS_VIOLATION during realm
 * loading; PR #38).
 *
 * <p>An executable needs no exports: nothing {@code GetProcAddress}-es the image (jvm-channel
 * looks symbols up in the CHILD's jvm.dll, and SubstrateVM binds its built-in natives at image
 * build time — proven on Linux, where the same symbols are hidden via the
 * {@code linux-hide-static-jdk-symbols} profile's GNU-ld {@code exclude-libs=ALL} and the image
 * works end to end). MSVC's linker has no equivalent switch, hence this post-link patch — the
 * Windows counterpart of that profile. The WHOLE directory is zeroed rather than renaming
 * individual names: the export name table must stay sorted for GetProcAddress's binary search.
 * Only the header's pointer to the table is cleared; the image's own direct (build-time-bound)
 * calls into that code are unaffected.
 */
@Mojo(name = "strip-pe-exports")
public final class StripPeExports extends AbstractMojo {

    /** The image executable to patch; must exist on Windows, never consulted elsewhere. */
    @Parameter(required = true)
    private File binary;

    @Override
    public void execute() throws MojoExecutionException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            getLog().debug("strip-pe-exports: PE exports exist only on Windows — skipping");
            return;
        }
        // On Windows the image was JUST built, so a missing file means the imageName/path wiring
        // drifted — fail rather than silently shipping an image whose JVM fallback crashes.
        if (!binary.isFile()) {
            throw new MojoExecutionException(
                    "strip-pe-exports: expected the freshly built image at " + binary + " but it does not exist");
        }
        try {
            strip(binary.toPath(), getLog()::info);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to strip PE exports from " + binary, e);
        }
    }

    /** Package-visible core so the logic stays testable without a mojo harness. Idempotent. */
    static void strip(Path exe, Consumer<String> log) throws IOException {
        byte[] bytes = Files.readAllBytes(exe);
        ByteBuffer pe = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 0x40 || pe.getShort(0) != 0x5A4D) { // "MZ"
            throw new IOException(exe + ": not a PE file (no MZ header)");
        }
        int peSig = pe.getInt(0x3C);
        if (pe.getInt(peSig) != 0x00004550) { // "PE\0\0"
            throw new IOException(exe + ": no PE signature at e_lfanew");
        }
        int coff = peSig + 4;
        int numSections = pe.getShort(coff + 2) & 0xFFFF;
        int optSize = pe.getShort(coff + 16) & 0xFFFF;
        int opt = coff + 20;
        int magic = pe.getShort(opt) & 0xFFFF;
        if (magic != 0x20B) {
            throw new IOException(exe + ": not PE32+ (optional header magic 0x" + Integer.toHexString(magic) + ")");
        }
        // PE32+: data directories start 112 bytes into the optional header; entry 0 = exports.
        int exportDir = opt + 112;
        int expRva = pe.getInt(exportDir);
        int expSize = pe.getInt(exportDir + 4);
        if (expRva == 0 && expSize == 0) {
            log.accept("strip-pe-exports: " + exe + " export directory already empty — nothing to do");
            return;
        }

        // Section table, to map RVAs to file offsets: the export names are listed before the
        // directory is dropped — the build log is the audit trail.
        int[][] sections = new int[numSections][3];
        int sec = opt + optSize;
        for (int i = 0; i < numSections; i++) {
            int s = sec + 40 * i;
            int vsize = pe.getInt(s + 8);
            sections[i][0] = pe.getInt(s + 12); // virtual address
            sections[i][1] = Math.max(vsize, pe.getInt(s + 16)); // max(virtual, raw) size
            sections[i][2] = pe.getInt(s + 20); // raw offset
        }
        int exp = rvaToOff(sections, expRva, exe);
        int numNames = pe.getInt(exp + 24);
        int namesOff = numNames == 0 ? 0 : rvaToOff(sections, pe.getInt(exp + 32), exe);
        int onLoad = 0;
        for (int i = 0; i < numNames; i++) {
            int off = rvaToOff(sections, pe.getInt(namesOff + 4 * i), exe);
            int end = off;
            while (bytes[end] != 0) {
                end++;
            }
            String name = new String(bytes, off, end - off, java.nio.charset.StandardCharsets.US_ASCII);
            boolean trigger = name.startsWith("JNI_OnLoad");
            if (trigger) {
                onLoad++;
            }
            log.accept("strip-pe-exports:   dropping export: " + name
                    + (trigger ? "   <- would trigger JEP 178 static-linking detection in the child JVM" : ""));
        }
        pe.putInt(exportDir, 0);
        pe.putInt(exportDir + 4, 0);
        Files.write(exe, bytes);
        log.accept("strip-pe-exports: " + exe + " export directory zeroed (" + numNames + " exports dropped, " + onLoad
                + " JNI_OnLoad_*)");
    }

    private static int rvaToOff(int[][] sections, int rva, Path exe) throws IOException {
        for (int[] s : sections) {
            if (Integer.compareUnsigned(s[0], rva) <= 0 && Integer.compareUnsigned(rva, s[0] + s[1]) < 0) {
                return s[2] + (rva - s[0]);
            }
        }
        throw new IOException(exe + ": RVA 0x" + Integer.toHexString(rva) + " not covered by any section");
    }
}
