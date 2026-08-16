#!/usr/bin/env python3
"""Zero the PE export directory of a native-image executable (Windows only).

Why this exists (see PR #38): the nmvn image statically links the JDK's native
libraries (libjava, libzip, libnet, libnio, ...). On Windows their JNIEXPORT
functions carry __declspec(dllexport), so linking them into the .exe puts them
into the executable's EXPORT TABLE - including JNI_OnLoad_zip / JNI_OnLoad_nio /
JNI_OnLoad_net. When the JVM fallback boots a HotSpot child inside this process
(jvm-channel / JNI_CreateJavaVM), HotSpot's statically-linked-library detection
(JEP 178) probes the process for exactly those JNI_OnLoad_<lib> names, finds
them in the .exe, concludes zip/nio/net are "statically linked", and resolves
the child's native methods against the IMAGE's SubstrateVM-compiled copies.
Those copies use the image's JNI state, so the child corrupts memory and dies
(EXCEPTION_ACCESS_VIOLATION during Maven realm loading).

An executable needs no exports: nothing GetProcAddress-es nmvn.exe. jvm-channel
looks symbols up in the CHILD's jvm.dll, and SubstrateVM binds its built-in
natives at image build time - proven on Linux, where the same symbols are
hidden via the linux-hide-static-jdk-symbols profile (GNU ld exclude-libs=ALL,
see native/launcher/pom.xml) and the image works end to end. MSVC's linker has
no equivalent switch, hence this post-link patch. The whole directory is zeroed
rather than renaming individual names: the export name table must stay sorted
for GetProcAddress's binary search, and removing the directory sidesteps that
entirely. The table's bytes remain in the file; only the header pointer to it
is cleared, so the image's own direct (build-time-bound) calls are unaffected.

Usage: strip_pe_exports.py <path-to-exe>   (idempotent)
"""

import struct
import sys


def fail(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 2:
        fail(f"usage: {sys.argv[0]} <path-to-exe>")
    path = sys.argv[1]
    with open(path, "rb") as f:
        data = bytearray(f.read())

    if data[:2] != b"MZ":
        fail(f"{path}: not a PE file (no MZ header)")
    e_lfanew = struct.unpack_from("<I", data, 0x3C)[0]
    if data[e_lfanew : e_lfanew + 4] != b"PE\0\0":
        fail(f"{path}: no PE signature at e_lfanew")

    coff = e_lfanew + 4
    num_sections = struct.unpack_from("<H", data, coff + 2)[0]
    opt_header_size = struct.unpack_from("<H", data, coff + 16)[0]
    opt = coff + 20
    magic = struct.unpack_from("<H", data, opt)[0]
    if magic != 0x20B:
        fail(f"{path}: not PE32+ (optional header magic {magic:#x})")

    # PE32+: the data directories start 112 bytes into the optional header;
    # entry 0 is the export table (RVA, size).
    export_dir = opt + 112
    exp_rva, exp_size = struct.unpack_from("<II", data, export_dir)
    if exp_rva == 0 and exp_size == 0:
        print(f"{path}: export directory already empty - nothing to do")
        return

    # Map RVAs through the section table so the export names can be listed
    # before the directory is dropped (the log is the audit trail).
    sections = []
    sec = opt + opt_header_size
    for i in range(num_sections):
        s = sec + 40 * i
        vsize, vaddr, rsize, roff = struct.unpack_from("<IIII", data, s + 8)
        sections.append((vaddr, max(vsize, rsize), roff))

    def rva_to_off(rva):
        for vaddr, size, roff in sections:
            if vaddr <= rva < vaddr + size:
                return roff + (rva - vaddr)
        fail(f"{path}: RVA {rva:#x} not in any section")

    exp = rva_to_off(exp_rva)
    num_names = struct.unpack_from("<I", data, exp + 24)[0]
    names_rva = struct.unpack_from("<I", data, exp + 32)[0]
    names_off = rva_to_off(names_rva) if num_names else 0
    onload = 0
    for i in range(num_names):
        name_rva = struct.unpack_from("<I", data, names_off + 4 * i)[0]
        off = rva_to_off(name_rva)
        name = bytes(data[off : data.index(b"\0", off)]).decode("ascii", "replace")
        marker = ""
        if name.startswith("JNI_OnLoad"):
            onload += 1
            marker = "   <- would trigger JEP 178 static-linking detection in the child JVM"
        print(f"  dropping export: {name}{marker}")

    struct.pack_into("<II", data, export_dir, 0, 0)
    with open(path, "wb") as f:
        f.write(data)
    print(f"{path}: export directory zeroed ({num_names} exports dropped, {onload} JNI_OnLoad_*)")


if __name__ == "__main__":
    main()
