#!/usr/bin/env python3
"""
Pick the nmvn binary for a pom.xml.

Reads the pom as XML and nothing else: no Maven invocation, no dependency resolution, no network.
That is a hard requirement -- this runs BEFORE a Maven exists to run, so anything that needed Maven
to answer would be circular.

Two facts make it a pure text read:

  1. start.spring.io materializes every plugin into <build><plugins> verbatim. There is no
     dependency -> plugin inference to do; the pom already lists what the build will request. (The one
     thing a dependency changes implicitly is hibernate-maven-plugin under native+data-jpa, and
     initializr writes that plugin out explicitly too.)

  2. Selection needs only groupId:artifactId, never versions. Versions are fixed by the Boot line,
     which <parent><version> gives directly -- so ${vaadin.version} and pluginManagement-managed
     entries need no interpolation to be classified.

Selection rule: among binaries whose baked feature set is a SUPERSET of what the pom needs, take the
smallest. A subset match is not good enough -- an unbaked plugin silently falls back to dynamic
resolution, which is the cost we are buying our way out of. If nothing is a superset (a pom outside
the modelled space), fall back to the largest binary and say so on stderr, because that is the
best-effort answer rather than a wrong one.

Usage:
    ./nmvn_select.py path/to/pom.xml [--catalog catalog.json] [--explain]
    ./nmvn_select.py --validate 'dir/*.xml'     # classify a corpus, report the distribution
"""

import argparse
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
POM_NS = "{http://maven.apache.org/POM/4.0.0}"


def _kids(elem, name):
    return [c for c in elem if c.tag.split("}", 1)[-1] == name]


def _text(elem, name, default=None):
    k = _kids(elem, name)
    return k[0].text.strip() if k and k[0].text and k[0].text.strip() else default


def pom_facts(path):
    """(bootVersion or None, packaging, set of declared plugin g:a) -- no resolution anywhere."""
    root = ET.parse(path).getroot()

    boot = None
    for parent in _kids(root, "parent"):
        if _text(parent, "groupId") == "org.springframework.boot" \
                and _text(parent, "artifactId") == "spring-boot-starter-parent":
            boot = _text(parent, "version")

    packaging = _text(root, "packaging", "jar")

    plugins = set()
    for build in _kids(root, "build"):
        for plugins_el in _kids(build, "plugins"):
            for p in _kids(plugins_el, "plugin"):
                g = _text(p, "groupId", "org.apache.maven.plugins")
                a = _text(p, "artifactId")
                if a:
                    plugins.add(f"{g}:{a}")
    return boot, packaging, plugins


def needed_features(plugins, catalog):
    """Which catalog features this pom's plugin list demands.

    A feature is demanded when any of its plugins is declared. Unbakeable plugins
    (native-maven-plugin, spring-cloud-contract-maven-plugin) are ignored outright: they carry
    <extensions>true</extensions> and are served dynamically in EVERY binary, so letting them
    influence selection would only push users to a bigger image for no gain.
    """
    ignored = set(catalog["unbakeable"])
    core_ga = {":".join(p.split(":")[:2]) for p in catalog["corePlugins"]}

    feature_of = {}
    for feature, pluginlist in catalog["featurePlugins"].items():
        for p in pluginlist:
            feature_of[":".join(p.split(":")[:2])] = feature

    needed, unknown = set(), set()
    for ga in plugins:
        if ga in ignored or ga in core_ga:
            continue
        if ga in feature_of:
            needed.add(feature_of[ga])
        else:
            unknown.add(ga)
    return needed, unknown


def select(path, catalog):
    boot, packaging, plugins = pom_facts(path)
    needed, unknown = needed_features(plugins, catalog)

    notes = []
    if boot is None:
        notes.append("no spring-boot-starter-parent; catalog may not apply")
    elif boot != catalog["bootVersion"]:
        notes.append(f"pom targets Boot {boot}, catalog is for {catalog['bootVersion']}")
    if unknown:
        # A plugin outside the model: every binary serves it dynamically, so it costs resolution time
        # but never a wrong pick. Surfaced because a recurring one is a signal to extend the model.
        notes.append("not in model, will resolve dynamically: " + ", ".join(sorted(unknown)))

    covering = [b for b in catalog["binaries"] if needed <= set(b["features"])]
    if covering:
        chosen = min(covering, key=lambda b: b["estimatedMb"])
    else:
        chosen = max(catalog["binaries"], key=lambda b: b["estimatedMb"])
        notes.append("no binary covers this pom; falling back to the largest")

    return chosen, sorted(needed), packaging, notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pom", nargs="?")
    ap.add_argument("--catalog", default=os.path.join(HERE, "catalog.json"))
    ap.add_argument("--explain", action="store_true")
    ap.add_argument("--validate")
    args = ap.parse_args()

    with open(args.catalog) as fh:
        catalog = json.load(fh)

    if args.validate:
        counts, feature_hits, failures = {}, {}, []
        paths = sorted(glob.glob(args.validate))
        for p in paths:
            try:
                chosen, needed, _, notes = select(p, catalog)
            except ET.ParseError:
                failures.append(os.path.basename(p))
                continue
            counts[chosen["name"]] = counts.get(chosen["name"], 0) + 1
            for f in needed:
                feature_hits.setdefault(f, []).append(os.path.basename(p)[:-4])
        total = sum(counts.values())
        print(f"classified {total} poms ({len(failures)} unparseable)\n")
        for name, n in sorted(counts.items(), key=lambda kv: -kv[1]):
            mb = next(b["estimatedMb"] for b in catalog["binaries"] if b["name"] == name)
            print(f"  {n:>4} ({n / total * 100:>5.1f}%)  {mb:>5}MB  {name}")
        print("\nfeature demand:")
        for f, poms in sorted(feature_hits.items(), key=lambda kv: -len(kv[1])):
            shown = ", ".join(sorted(poms)[:6])
            print(f"  {f:<14} {len(poms):>3}  {shown}")
        return 0

    if not args.pom:
        ap.error("give a pom.xml or --validate")

    chosen, needed, packaging, notes = select(args.pom, catalog)
    if args.explain:
        print(f"packaging : {packaging}")
        print(f"features  : {', '.join(needed) if needed else '(none)'}")
        for n in notes:
            print(f"note      : {n}")
        print(f"binary    : {chosen['name']}  (~{chosen['estimatedMb']}MB)")
    else:
        for n in notes:
            print(f"nmvn-select: {n}", file=sys.stderr)
        print(chosen["name"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
