#!/usr/bin/env python3
"""
Resolve a Spring Boot version into an nmvn catalog used to build the specialized native image.

Pipeline
--------
    Spring Boot version + language  (+ always Maven)
            │
            ▼
    probe POM (spring-boot-starter-parent) + effective-pom via dist Maven
            │
            ▼
    build/catalogs/nmvn-spring-<version>.json
            │
            ▼
    mvnw -Pnative[,crema] package -pl native/launcher -am -Dnmvn.pluginsFile=<.plugins> ...
            │
            ▼
    native/launcher/target/nmvn-spring-<version>

Maven dist used for baking stays in apache-maven/target/ (not under build/).

Product policy (current)
------------------------
**One specialized binary** per Spring Boot version (`nmvn-spring-<version>`).

Usage
-----
    ./resolve_boot_catalog.py --boot-version 4.1.0 --language java
    # then build with the Maven native profile (see the hint the script prints)
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent

# Core plugins declared on the probe POM without versions — Boot parent pluginManagement pins them.
# jar + war both listed so packaging is not a catalog axis.
CORE_PLUGIN_GA = [
    ("org.springframework.boot", "spring-boot-maven-plugin"),
    ("org.apache.maven.plugins", "maven-clean-plugin"),
    ("org.apache.maven.plugins", "maven-resources-plugin"),
    ("org.apache.maven.plugins", "maven-compiler-plugin"),
    ("org.apache.maven.plugins", "maven-surefire-plugin"),
    ("org.apache.maven.plugins", "maven-jar-plugin"),
    ("org.apache.maven.plugins", "maven-war-plugin"),
    ("org.apache.maven.plugins", "maven-install-plugin"),
    ("org.apache.maven.plugins", "maven-deploy-plugin"),
    ("org.apache.maven.plugins", "maven-site-plugin"),
]

# Preferred order in catalog output (stable, human-readable).
CORE_ORDER = [f"{g}:{a}" for g, a in CORE_PLUGIN_GA]

# Single list of plugins not currently prebaked. This is product scope, not a permanent technical
# ban: extensions=true (native, spring-cloud-contract) needs more realm work later; others are
# simply not in the specialized image yet. All resolve dynamically if present.
OUT_OF_SCOPE = {
    "_comment": [
        "Not currently prebaked into the specialized image (same category for every entry).",
        "Not dropped and not permanently unbakeable — including extensions=true plugins,",
        "which need more work for realm baking later but are not impossible forever.",
        "Dynamic resolution (Crema) if a POM declares them.",
        "Runtime Hibernate via spring-boot-starter-data-jpa needs no hibernate-maven-plugin.",
    ],
    "vaadin": ["com.vaadin:vaadin-maven-plugin"],
    "restdocs": ["org.asciidoctor:asciidoctor-maven-plugin"],
    "grpc": ["io.github.ascopes:protobuf-maven-plugin"],
    "sbom": ["org.cyclonedx:cyclonedx-maven-plugin"],
    "codegen-dgs": [
        "io.github.deweyjose:graphqlcodegen-maven-plugin",
        "org.codehaus.mojo:build-helper-maven-plugin",
    ],
    "native": ["org.graalvm.buildtools:native-maven-plugin"],
    "spring-cloud-contract": [
        "org.springframework.cloud:spring-cloud-contract-maven-plugin"
    ],
    "hibernate-maven-plugin": ["org.hibernate.orm:hibernate-maven-plugin"],
    "notes": {
        "native": (
            "extensions=true today → different realm model; not prebaked yet, not forever impossible."
        ),
        "spring-cloud-contract": (
            "extensions=true today → different realm model; not prebaked yet, not forever impossible."
        ),
        "hibernate-maven-plugin": (
            "Enhance goal; Initializr adds it for native+data-jpa. Runtime JPA uses hibernate-core "
            "as a library without this plugin."
        ),
    },
}

PLUGIN_INFRASTRUCTURE = {
    "org.springframework.boot:spring-boot-maven-plugin": [
        "org.apache.commons.logging.",
    ],
}

LANGUAGES = ("java", "kotlin", "groovy")


def die(msg: str, code: int = 1) -> None:
    print(f"Error: {msg}", file=sys.stderr)
    sys.exit(code)


def _mvn_launcher(home: Path) -> Path | None:
    """Return a runnable Maven launcher under home/bin.

    On Windows, bin/mvn is a Unix shell script (CreateProcess → WinError 193). Prefer bin/mvn.cmd.
    """
    bin_dir = home / "bin"
    if os.name == "nt":
        for name in ("mvn.cmd", "mvn.bat"):
            p = bin_dir / name
            if p.is_file():
                return p
        return None
    p = bin_dir / "mvn"
    return p if p.is_file() else None


def find_mvn() -> Path:
    """Prefer the repo's Apache Maven dist (version fidelity with baked binary), else PATH.

    Dist dir may be apache-maven-4.1.0-SNAPSHOT (local) or apache-maven-4.1.0 (CI strips SNAPSHOT).
    """
    target = REPO / "apache-maven" / "target"

    def dist_mvn() -> Path | None:
        for name in ("apache-maven-4.1.0-SNAPSHOT", "apache-maven-4.1.0"):
            found = _mvn_launcher(target / name)
            if found:
                return found
        for home in sorted(target.glob("apache-maven-*")):
            if home.is_dir():
                found = _mvn_launcher(home)
                if found:
                    return found
        return None

    found = dist_mvn()
    if found:
        return found

    for tarball in sorted(target.glob("apache-maven-*-bin.tar.gz")):
        print(f">>> Extracting {tarball} ...", file=sys.stderr)
        subprocess.check_call(["tar", "-xzf", str(tarball), "-C", str(target)])
        found = dist_mvn()
        if found:
            return found

    # PATH: which("mvn") may return mvn.cmd on Windows.
    path_mvn = shutil.which("mvn.cmd" if os.name == "nt" else "mvn") or shutil.which("mvn")
    if path_mvn:
        print(
            ">>> Warning: dist Maven not found; using PATH mvn "
            f"({path_mvn}). Lifecycle plugin versions may differ from the baked image.",
            file=sys.stderr,
        )
        return Path(path_mvn)
    die(
        "no Maven found. Build the dist first:\n"
        "  mvn clean package -DskipTests -Drat.skip=true\n"
        "or put mvn on PATH."
    )


def run_mvn(mvn: Path, args: list[str]) -> None:
    """Run Maven so Windows .cmd launchers work (not via CreateProcess on the shell script)."""
    mvn_s = str(mvn)
    if os.name == "nt":
        # .cmd/.bat need cmd.exe; Unix bin/mvn is not a Win32 app.
        cmd = ["cmd", "/c", mvn_s, *args]
    else:
        cmd = [mvn_s, *args]
    subprocess.check_call(cmd)


def local(tag: str) -> str:
    return tag.split("}", 1)[-1]


def kids(elem: ET.Element, name: str) -> list[ET.Element]:
    return [c for c in elem if local(c.tag) == name]


def text(elem: ET.Element, name: str, default=None):
    k = kids(elem, name)
    return k[0].text.strip() if k and k[0].text and k[0].text.strip() else default


def write_probe_pom(path: Path, boot_version: str, language: str) -> None:
    """Minimal multi-plugin probe: Boot parent pins versions via pluginManagement."""
    plugins_xml = []
    for g, a in CORE_PLUGIN_GA:
        plugins_xml.append(
            f"""      <plugin>
        <groupId>{g}</groupId>
        <artifactId>{a}</artifactId>
      </plugin>"""
        )
    # Language is a catalog axis; core still bakes the Java/Maven lifecycle set.
    # Kotlin/Groovy compile plugins are outOfScope for prebaking today.
    body = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>{boot_version}</version>
    <relativePath/>
  </parent>
  <groupId>nmvn.catalog</groupId>
  <artifactId>boot-probe-{language}</artifactId>
  <version>1</version>
  <packaging>jar</packaging>
  <description>nmvn catalog probe for Boot {boot_version} language={language} buildTool=maven</description>
  <build>
    <plugins>
{chr(10).join(plugins_xml)}
    </plugins>
  </build>
</project>
"""
    path.write_text(body, encoding="utf-8")


def resolve_plugins(mvn: Path, boot_version: str, language: str) -> list[str]:
    """Return ordered g:a:v list for core plugins of this Boot line."""
    # Probe POMs under build/work/ (same layout as build-nmvn-prebuilt scratch).
    work_root = Path(os.environ.get("NMVN_WORK_DIR") or (REPO / "build" / "work"))
    work = work_root / f"catalog-probe-{language}-{boot_version}"
    work.mkdir(parents=True, exist_ok=True)
    pom = work / "pom.xml"
    eff = work / "effective-pom.xml"
    write_probe_pom(pom, boot_version, language)

    print(
        f">>> Resolving plugins for Boot {boot_version} language={language} buildTool=maven ...",
        file=sys.stderr,
    )
    print(f">>> Using {mvn}", file=sys.stderr)
    try:
        run_mvn(
            mvn,
            [
                "-q",
                "-f",
                str(pom),
                "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom",
                f"-Doutput={eff}",
            ],
        )
    except subprocess.CalledProcessError as e:
        die(
            f"effective-pom failed for spring-boot-starter-parent:{boot_version} "
            f"(is that Boot version available?). exit={e.returncode}"
        )

    if not eff.is_file():
        die(f"effective-pom not written: {eff}")

    root = ET.parse(eff).getroot()
    projects = (
        [root]
        if local(root.tag) == "project"
        else [c for c in root if local(c.tag) == "project"]
    )

    # ga -> gav (first wins; should be unique)
    found: dict[str, str] = {}
    for project in projects:
        for build in kids(project, "build"):
            for plugins in kids(build, "plugins"):
                for plugin in kids(plugins, "plugin"):
                    g = text(plugin, "groupId", "org.apache.maven.plugins")
                    a = text(plugin, "artifactId")
                    v = text(plugin, "version")
                    if not a or not v:
                        continue
                    if text(plugin, "extensions") == "true":
                        continue
                    ga = f"{g}:{a}"
                    found[ga] = f"{g}:{a}:{v}"

    missing = [ga for ga in CORE_ORDER if ga not in found]
    if missing:
        # Fall back: scan pluginManagement for pins (some parents only manage, not bind).
        for project in projects:
            for build in kids(project, "build"):
                for pm in kids(build, "pluginManagement"):
                    for plugins in kids(pm, "plugins"):
                        for plugin in kids(plugins, "plugin"):
                            g = text(plugin, "groupId", "org.apache.maven.plugins")
                            a = text(plugin, "artifactId")
                            v = text(plugin, "version")
                            if not a or not v:
                                continue
                            ga = f"{g}:{a}"
                            if ga in missing:
                                found[ga] = f"{g}:{a}:{v}"
        missing = [ga for ga in CORE_ORDER if ga not in found]

    if missing:
        die(
            "could not resolve versions for core plugins from Boot parent / effective pom:\n  "
            + "\n  ".join(missing)
        )

    # Stable product order; drop any extras from the probe that we did not ask for.
    return [found[ga] for ga in CORE_ORDER]


def binary_name(boot_version: str) -> str:
    """One specialized binary per Spring Boot version: nmvn-spring-<version>."""
    return f"nmvn-spring-{boot_version}"


def build_catalog(boot_version: str, language: str, plugins: list[str]) -> dict:
    name = binary_name(boot_version)
    return {
        "bootVersion": boot_version,
        "language": language,
        "buildTool": "maven",
        "binary": name,
        "plugins": plugins,
        "outOfScope": OUT_OF_SCOPE,
        "pluginInfrastructure": {
            k: v for k, v in PLUGIN_INFRASTRUCTURE.items() if not str(k).startswith("_")
        },
        "binaries": [
            {
                "name": name,
                "plugins": plugins,
            }
        ],
    }


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")
    print(f">>> wrote {path}", file=sys.stderr)


def write_plugins_files(catalog_path: Path, data: dict) -> None:
    """One <binary-name>.plugins next to the catalog JSON per binary entry: the newline-separated
    GAV list the Maven native profiles consume directly via -Dnmvn.pluginsFile (LF-only — a CR
    inside a coordinate corrupts the realm spec downstream)."""
    for binary in data["binaries"]:
        path = catalog_path.parent / f"{binary['name']}.plugins"
        with path.open("w", encoding="utf-8", newline="\n") as fh:
            for plugin in binary["plugins"]:
                fh.write(plugin)
                fh.write("\n")
        print(f">>> wrote {path}", file=sys.stderr)


def main() -> None:
    ap = argparse.ArgumentParser(
        description=(
            "From Spring Boot version + language, resolve core Maven plugin versions "
            "and emit an nmvn catalog for building the specialized native image."
        )
    )
    ap.add_argument(
        "--boot-version",
        required=True,
        help="Spring Boot version, e.g. 4.1.0 (must resolve as spring-boot-starter-parent)",
    )
    ap.add_argument(
        "--language",
        default="java",
        choices=LANGUAGES,
        help="Project language axis for the catalog binary name (default: java)",
    )
    ap.add_argument(
        "--build-tool",
        default="maven",
        choices=["maven"],
        help="Always maven for this catalog (only supported value)",
    )
    ap.add_argument(
        "--emit",
        default=None,
        help=(
            "Write catalog JSON here. "
            "Default: <repo>/build/catalogs/nmvn-spring-<bootVersion>.json"
        ),
    )
    ap.add_argument(
        "--print-only",
        action="store_true",
        help="Print catalog JSON to stdout; do not write files unless --emit is set",
    )
    ap.add_argument(
        "--plugins-only",
        action="store_true",
        help="Print resolved core g:a:v lines and exit (no catalog file)",
    )
    args = ap.parse_args()

    if args.build_tool != "maven":
        die("only build tool 'maven' is supported")

    language = args.language.lower()
    boot = args.boot_version
    mvn = find_mvn()
    core_plugins = resolve_plugins(mvn, boot, language)

    if args.plugins_only:
        for p in core_plugins:
            print(p)
        return

    catalog = build_catalog(boot, language, core_plugins)

    print(">>> Baked plugins:", file=sys.stderr)
    for p in core_plugins:
        print(f"    {p}", file=sys.stderr)
    print(
        f">>> Binary: {catalog['binary']}  (language={language}, boot={boot}, buildTool=maven)",
        file=sys.stderr,
    )

    default_emit = REPO / "build" / "catalogs" / f"nmvn-spring-{boot}.json"

    if args.print_only and not args.emit:
        json.dump(catalog, sys.stdout, indent=2)
        sys.stdout.write("\n")
    else:
        emit_path = Path(args.emit) if args.emit else default_emit
        if not emit_path.is_absolute():
            emit_path = (Path.cwd() / emit_path).resolve()
        write_json(emit_path, catalog)
        write_plugins_files(emit_path, catalog)

    if not args.print_only:
        if args.emit:
            cat_path = Path(args.emit)
            if not cat_path.is_absolute():
                cat_path = (Path.cwd() / cat_path).resolve()
        else:
            cat_path = default_emit
        try:
            cat_hint = str(cat_path.resolve().relative_to(REPO))
        except ValueError:
            cat_hint = str(cat_path)
        plugins_hint = str(Path(cat_hint).parent / f"{catalog['binary']}.plugins")
        print(
            "\nNext: build the native image from this catalog (from the repo root):\n"
            f"  ./mvnw -Pnative package -pl native/launcher -am -DskipTests \\\n"
            f"    -Dnmvn.pluginsFile={plugins_hint} -Dnmvn.imageName={catalog['binary']}\n"
            f"  # add ,crema to -P for the crema variant; binary → native/launcher/target/{catalog['binary']}\n"
            f"  # (pass -Dnmvn.pluginsFile as an ABSOLUTE path when not running from the repo root)",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
