#!/bin/bash
#
# Builds a SPECIALIZED nmvn native image for one project: reads the project's pom.xml, computes
# the set of plugins its build will request, and delegates to build-nmvn-prebuilt.sh to bake
# exactly those plugins.
#
# The plugin set comes from the EFFECTIVE pom as computed by the SAME Maven snapshot that ships
# in the image — so it contains both the explicit <build><plugins> entries and the default
# lifecycle bindings (resolved to concrete versions by this snapshot). That version fidelity
# matters: prebuilt routing falls back to dynamic resolution on any version skew, so a baked
# version that this Maven would never request is dead weight in the image.
#
# Usage:
#   ./build-nmvn-for-pom.sh <pom.xml | project-dir> [--plugins-only]
#
#   --plugins-only  print the derived g:a:v list (one per line) and exit without building;
#                   useful as the analysis step of a build-service pipeline.
#
# Notes / limitations:
#  - Profiles: only plugins of profiles active during extraction are seen. Pass a representative
#    activation environment if the project's build relies on profile-added plugins.
#  - Plugins declaring extra per-plugin <dependencies> or <extensions>true</extensions> are
#    excluded: the runtime deliberately serves those dynamically (see the routing predicate in
#    PrebuiltPluginDescriptorCache/PrebuiltPluginRealmCache), so baking them is wasted image size.
#  - Mojo jar resources are baked into each realm by PrebuiltPluginRealms (BakedClassLoader
#    serves them from memory), so no per-plugin -H:IncludeResources patterns are needed.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/apache-maven/target"
MAVEN_HOME="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT"

if [ $# -lt 1 ]; then
  echo "Usage: $0 <pom.xml | project-dir> [--plugins-only]"
  exit 2
fi

POM="$1"
[ -d "$POM" ] && POM="$POM/pom.xml"
if [ ! -f "$POM" ]; then
  echo "Error: pom not found: $POM"
  exit 1
fi

PLUGINS_ONLY=0
[ "${2:-}" = "--plugins-only" ] && PLUGINS_ONLY=1

# The dist's own mvn resolves the effective pom, so default lifecycle binding versions are the
# ones THIS snapshot will request at runtime (a PATH mvn of another version would skew them).
MVN="$MAVEN_HOME/bin/mvn"
if [ ! -x "$MVN" ]; then
  TARBALL="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT-bin.tar.gz"
  if [ ! -f "$TARBALL" ]; then
    echo "Error: $TARBALL not found. Build Maven first:"
    echo "  mvn clean package -DskipTests -Drat.skip=true"
    exit 1
  fi
  echo "Extracting $TARBALL ..."
  tar -xzf "$TARBALL" -C "$TARGET_DIR"
fi

WORK="$SCRIPT_DIR/.prebuilt-work"
mkdir -p "$WORK"
EFF="$WORK/effective-pom.xml"
rm -f "$EFF"

echo ">>> Computing effective pom for $POM ..." >&2
"$MVN" -q -f "$POM" \
  org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom \
  -Doutput="$EFF" >&2

# Collect g:a:v of every <build><plugins><plugin> across all modules (the aggregated effective
# pom wraps multi-module output in <projects>). pluginManagement-only and <reporting> entries are
# ignored — they are not executed unless bound, in which case they also appear under plugins.
python3 - "$EFF" > "$WORK/plugins.list" <<'PY'
import sys
import xml.etree.ElementTree as ET

def local(tag):
    return tag.split('}', 1)[-1]

def kids(e, name):
    return [c for c in e if local(c.tag) == name]

def text(e, name, default=None):
    k = kids(e, name)
    return k[0].text.strip() if k and k[0].text and k[0].text.strip() else default

root = ET.parse(sys.argv[1]).getroot()
projects = [root] if local(root.tag) == 'project' else [c for c in root if local(c.tag) == 'project']

seen = set()
for project in projects:
    for build in kids(project, 'build'):
        for plugins in kids(build, 'plugins'):
            for plugin in kids(plugins, 'plugin'):
                g = text(plugin, 'groupId', 'org.apache.maven.plugins')
                a = text(plugin, 'artifactId')
                v = text(plugin, 'version')
                gav = f"{g}:{a}:{v}"
                if gav in seen:
                    continue
                seen.add(gav)
                if v is None:
                    print(f"skip {g}:{a}: no resolved version", file=sys.stderr)
                    continue
                if any(kids(deps, 'dependency') for deps in kids(plugin, 'dependencies')):
                    print(f"skip {gav}: per-plugin <dependencies> force dynamic resolution", file=sys.stderr)
                    continue
                if text(plugin, 'extensions') == 'true':
                    print(f"skip {gav}: extensions plugins are served dynamically", file=sys.stderr)
                    continue
                print(gav)
PY

GAVS=()
while IFS= read -r line; do
  [ -n "$line" ] && GAVS+=("$line")
done < "$WORK/plugins.list"

if [ ${#GAVS[@]} -eq 0 ]; then
  echo "Error: no bakeable plugins derived from $POM"
  exit 1
fi

echo ">>> Baking ${#GAVS[@]} plugins:" >&2
printf '    %s\n' "${GAVS[@]}" >&2

if [ "$PLUGINS_ONLY" -eq 1 ]; then
  printf '%s\n' "${GAVS[@]}"
  exit 0
fi

exec "$SCRIPT_DIR/build-nmvn-prebuilt.sh" "${GAVS[@]}"