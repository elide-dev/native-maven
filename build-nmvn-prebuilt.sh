#!/bin/bash
#
# Builds a GraalVM native image of Maven (nmvn) with maven-clean-plugin baked in as a
# PREBUILT, ISOLATED ClassRealm — snapshotted into the image heap at build time.
#
# Unlike the "flatten" approach (all baked plugins share the flat core realm, isolation lost),
# the prebuilt plugin gets its OWN ClassRealm, constructed in PrebuiltPluginRealms' static
# initializer at IMAGE BUILD TIME and frozen into the image (see PrebuiltPluginRealms.java).
# Every other plugin still resolves dynamically at runtime via Crema (RuntimeClassLoading).
#
# Usage:
#   ./build-nmvn-prebuilt.sh [groupId:artifactId:version[|deps] ...]
#
# Plugins to bake are passed as arguments (one g:a:v each, optionally with |canonical-deps for
# per-plugin <dependencies>). Callers:
#   - build-nmvn-catalog.sh  (product: GAVs from catalog.json)
#   - build-nmvn-for-pom.sh   (optional: effective-pom of one project)
# Without arguments the default list below is used.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/apache-maven/target"

# Layout (same local and CI — no env required):
#   build/           — image binary + .plugins
#   build/work/      — scratch (realms, javac, sidecar, sanitize)
# Maven dist stays in apache-maven/target/ (not under build/).
# Optional overrides: NMVN_OUT_DIR, NMVN_WORK_DIR, NMVN_MAVEN_HOME
NMVN_OUT_DIR="${NMVN_OUT_DIR:-$SCRIPT_DIR/build}"
NMVN_WORK_DIR="${NMVN_WORK_DIR:-$SCRIPT_DIR/build/work}"
mkdir -p "$NMVN_OUT_DIR" "$NMVN_WORK_DIR"

# Resolve Maven home: explicit override, else any packaged apache-maven-* under target/
# (CI strips -SNAPSHOT from poms → apache-maven-4.1.0; local SNAPSHOT builds keep -SNAPSHOT).
if [ -n "${NMVN_MAVEN_HOME:-}" ]; then
  MAVEN_HOME="$NMVN_MAVEN_HOME"
else
  MAVEN_HOME=""
  for d in \
      "$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT" \
      "$TARGET_DIR/apache-maven-4.1.0" \
      "$TARGET_DIR"/apache-maven-*; do
    if [ -d "$d" ] && { [ -f "$d/bin/mvn" ] || [ -f "$d/bin/mvn.cmd" ]; }; then
      MAVEN_HOME="$d"
      break
    fi
  done
  # Fallback path for the error/unpack message even if not built yet.
  [ -n "$MAVEN_HOME" ] || MAVEN_HOME="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT"
fi

# ---------------------------------------------------------------------------------------------------
# 0a) TOOLCHAIN = JAVA_HOME/bin if set, else PATH.
#
#     Needs native-image with DEVELOPMENT options used by baked realms:
#       -H:+RuntimeClassLoading (Crema)
#       -H:+GraalJITCompileAtRuntime
#     Those are not on every GraalVM release; fail fast with a clear message if missing so we do
#     not burn tens of minutes resolving plugins before native-image rejects the flags.
#
#     Windows ships native-image.cmd (not a bare "native-image" Unix binary); resolve both.
# ---------------------------------------------------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ]; then
  # Normalize Windows paths for Git Bash if cygpath is available.
  if command -v cygpath >/dev/null 2>&1; then
    case "$JAVA_HOME" in
      [A-Za-z]:*|/*) JAVA_HOME="$(cygpath -u "$JAVA_HOME" 2>/dev/null || echo "$JAVA_HOME")" ;;
    esac
  fi
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

find_native_image() {
  # Prefer explicit JAVA_HOME first (CI downloads Graal there).
  if [ -n "${JAVA_HOME:-}" ]; then
    for c in \
        "$JAVA_HOME/bin/native-image" \
        "$JAVA_HOME/bin/native-image.cmd" \
        "$JAVA_HOME/bin/native-image.exe"; do
      if [ -f "$c" ] || [ -x "$c" ]; then
        printf '%s\n' "$c"
        return 0
      fi
    done
  fi
  # PATH: Unix binary or Windows cmd wrapper (Git Bash / command -v).
  if command -v native-image >/dev/null 2>&1; then
    command -v native-image
    return 0
  fi
  if command -v native-image.cmd >/dev/null 2>&1; then
    command -v native-image.cmd
    return 0
  fi
  return 1
}

# Run native-image (handles .cmd under Windows Git Bash).
run_native_image() {
  case "$NATIVE_IMAGE" in
    *.cmd|*.bat|*.CMD|*.BAT)
      cmd.exe //c "$NATIVE_IMAGE" "$@"
      ;;
    *)
      "$NATIVE_IMAGE" "$@"
      ;;
  esac
}

if ! NATIVE_IMAGE="$(find_native_image)"; then
  echo "Error: native-image not found."
  echo "       Install a GraalVM with native-image and either:"
  echo "         export JAVA_HOME=/path/to/graalvm"
  echo "         export PATH=\"\$JAVA_HOME/bin:\$PATH\""
  echo "       On Windows the launcher is usually bin/native-image.cmd"
  if [ -n "${JAVA_HOME:-}" ]; then
    echo "       JAVA_HOME=$JAVA_HOME"
    ls -la "$JAVA_HOME/bin"/native-image* 2>/dev/null || true
  fi
  exit 1
fi

# Prefer a real path for the log line (symlinks on some installs).
if command -v realpath >/dev/null 2>&1; then
  NATIVE_IMAGE="$(realpath "$NATIVE_IMAGE" 2>/dev/null || echo "$NATIVE_IMAGE")"
elif command -v readlink >/dev/null 2>&1; then
  NATIVE_IMAGE="$(readlink -f "$NATIVE_IMAGE" 2>/dev/null || echo "$NATIVE_IMAGE")"
fi

if ! command -v java >/dev/null 2>&1 && ! command -v java.exe >/dev/null 2>&1; then
  echo "Error: java not found on PATH (needed for realm sanitize / link probe)."
  echo "       Put the same JDK that provides native-image on PATH (or set JAVA_HOME)."
  exit 1
fi

EXPERT_OPTIONS="$(run_native_image --expert-options-all 2>&1 || true)"
# Matched with a shell 'case', NOT 'printf ... | grep -q': grep -q exits on the first match and closes
# the pipe, printf then dies of SIGPIPE, and under 'set -o pipefail' the pipeline reports THAT failure
# — so a successful match would look like a missing option and every toolchain would be rejected.
for required in RuntimeClassLoading GraalJITCompileAtRuntime; do
  if case "$EXPERT_OPTIONS" in *"$required"*) false ;; *) true ;; esac; then
    echo "Error: this native-image does not support -H:±$required"
    echo "       (required for nmvn prebaked plugin realms / Crema):"
    echo "         path:    $NATIVE_IMAGE"
    echo "         version: $(run_native_image --version 2>&1 | head -1)"
    echo "       Use a GraalVM build that includes RuntimeClassLoading and"
    echo "       GraalJITCompileAtRuntime, and put its bin/ first on PATH."
    exit 1
  fi
done

echo ">>> native-image: $NATIVE_IMAGE"
echo ">>>               $(run_native_image --version 2>&1 | head -1)"
echo ">>> java:         $(command -v java 2>/dev/null || command -v java.exe 2>/dev/null || true)"
if [ -n "${JAVA_HOME:-}" ]; then
  echo ">>> JAVA_HOME:    $JAVA_HOME"
fi

# Baked ("supported") plugins. Versions MUST match what builds will request (explicit pins or the
# default lifecycle bindings of this Maven snapshot) — prebuiltFor falls back to dynamic resolution
# on any version skew.
PLUGINS=(
  "org.apache.maven.plugins:maven-clean-plugin:3.5.0"
  "org.apache.maven.plugins:maven-compiler-plugin:3.13.0"
)
if [ "$#" -gt 0 ]; then
  PLUGINS=("$@")
fi

# Drop plugins listed in NMVN_SKIP_PLUGINS (comma-separated artifactId or g:a).
if [ -n "${NMVN_SKIP_PLUGINS:-}" ] && [ "${#PLUGINS[@]}" -gt 0 ]; then
  FILTERED=()
  for entry in "${PLUGINS[@]}"; do
    gav="${entry%%|*}"
    art="${gav#*:}"; art="${art%%:*}"
    ga="${gav%:*}"
    skip=0
    IFS=',' read -r -a skips <<< "$NMVN_SKIP_PLUGINS"
    for s in "${skips[@]}"; do
      s=$(echo "$s" | tr -d ' ')
      [ -z "$s" ] && continue
      if [ "$s" = "$art" ] || [ "$s" = "$ga" ] || [ "$s" = "$gav" ]; then
        skip=1
        echo ">>> skip bake (NMVN_SKIP_PLUGINS): $entry"
        break
      fi
    done
    [ "$skip" -eq 0 ] && FILTERED+=("$entry")
  done
  PLUGINS=("${FILTERED[@]+"${FILTERED[@]}"}")
fi

# ---------------------------------------------------------------------------------------------------
# 0) Ensure the Maven distribution is unpacked (provides boot/ + lib/ for the image classpath).
# ---------------------------------------------------------------------------------------------------
if [ ! -d "$MAVEN_HOME" ] || { [ ! -f "$MAVEN_HOME/bin/mvn" ] && [ ! -f "$MAVEN_HOME/bin/mvn.cmd" ]; }; then
  TARBALL=""
  for t in "$TARGET_DIR"/apache-maven-*-bin.tar.gz; do
    if [ -f "$t" ]; then
      TARBALL="$t"
      break
    fi
  done
  if [ -z "$TARBALL" ]; then
    echo "Error: Maven distribution not found under $TARGET_DIR"
    echo "       Build Maven first:"
    echo "         mvn clean package -DskipTests -Drat.skip=true"
    exit 1
  fi
  echo ">>> Extracting $TARBALL ..."
  tar -xzf "$TARBALL" -C "$TARGET_DIR"
  MAVEN_HOME=""
  for d in "$TARGET_DIR"/apache-maven-*; do
    if [ -d "$d" ] && { [ -f "$d/bin/mvn" ] || [ -f "$d/bin/mvn.cmd" ]; }; then
      MAVEN_HOME="$d"
      break
    fi
  done
  if [ -z "$MAVEN_HOME" ]; then
    echo "Error: unpack of $TARBALL did not produce a usable Maven home under $TARGET_DIR"
    exit 1
  fi
fi
echo ">>> Maven dist: $MAVEN_HOME"

# ---------------------------------------------------------------------------------------------------
# 1) Resolve each plugin's runtime classpath SEPARATELY (plugin jar + its transitive RUNTIME deps).
#    Each classpath becomes the constituents of that plugin's isolated realm — per-plugin resolution
#    keeps the realms honest (no cross-plugin dependency pollution). 'runtime' scope excludes
#    'provided' deps (maven-plugin-api, maven core): at runtime those come from the realm's parent,
#    the baked plexus.core.
#
#    Additionally, jars that maven-core EXPORTS to plugin realms are dropped, exactly like
#    DefaultClassRealmManager.isProvidedArtifact does for dynamic realms. Maven-2/3-era plugins
#    declare maven core artifacts as compile-scope deps; letting them into a baked realm bakes
#    foreign core components (e.g. an old maven-core components.xml whose components require
#    LifecycleStarter), which breaks injector creation at publication time.
# ---------------------------------------------------------------------------------------------------
WORK="$NMVN_WORK_DIR"
mkdir -p "$WORK"

EXPORTED_ARTIFACTS=$(unzip -p "$MAVEN_HOME"/lib/maven-core-*.jar META-INF/maven/extension.xml \
  | sed -n 's/.*<exportedArtifact>\(.*\)<\/exportedArtifact>.*/\1/p')

# Platform classpath / jar-list separator for javac/java -cp and for realm jar lists.
# On Windows Git Bash, Windows javac/java need ';' and drive-letter paths (cygpath -m), not /c/...
CP_SEP=':'
WINDOWS_BUILD=false
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*)
    CP_SEP=';'
    WINDOWS_BUILD=true
    ;;
esac

# Convert a path for use in a Windows-native -cp list (Git Bash → mixed Windows path).
to_cp_path() {
  local p="$1"
  if $WINDOWS_BUILD && command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$p" 2>/dev/null || printf '%s' "$p"
  else
    printf '%s' "$p"
  fi
}

# Append one path to CLASSPATH with the correct separator / Windows path form.
cp_append() {
  local p
  p="$(to_cp_path "$1")"
  if [ -z "$CLASSPATH" ]; then
    CLASSPATH="$p"
  else
    CLASSPATH="${CLASSPATH}${CP_SEP}${p}"
  fi
}

# Realm spec: one complete "g:a:v[|deps]=jar${CP_SEP}jar..." line per plugin (never ';' as entry
# separator — on Windows CP_SEP is ';'). Written via Python so bash cannot mangle paths/`=`.
SPEC_FILE="$WORK/spec.txt"
: > "$SPEC_FILE"
append_spec_line() {
  # $1 = full line (may contain ';', '\', spaces). Appended as one UTF-8 line.
  NMVN_SPEC_LINE="$1" NMVN_SPEC_FILE="$SPEC_FILE" python3 - <<'PY'
import os
line = os.environ["NMVN_SPEC_LINE"]
path = os.environ["NMVN_SPEC_FILE"]
# Normalize Windows paths to forward slashes so nothing treats '\' as escape later.
line = line.replace("\\", "/")
with open(path, "a", encoding="utf-8", newline="\n") as f:
    f.write(line)
    f.write("\n")
PY
}

for ENTRY in "${PLUGINS[@]}"; do
  # Each entry is "groupId:artifactId:version" optionally followed by "|<canonical dependencies>"
  # (see PrebuiltPluginRealms.dependencyKey for the encoding).
  GAV="${ENTRY%%|*}"
  DEP_KEY=""
  [ "$ENTRY" != "$GAV" ] && DEP_KEY="${ENTRY#*|}"
  G="${GAV%%:*}"; REST="${GAV#*:}"; A="${REST%%:*}"; V="${REST#*:}"
  CP_FILE="$WORK/$A.cp"

  # Per-plugin <dependencies> are declared BOTH as dependencies (so added jars land in the realm) and
  # in <dependencyManagement> (so they override versions inside the plugin's own tree) — the two
  # effects Maven's plugin dependency resolution gives them. Decoded from the canonical key, whose
  # per-entry form is g:a:v:type:classifier:scope with ^-separated exclusions appended.
  DEP_XML=""
  DEP_MGMT_XML=""
  if [ -n "$DEP_KEY" ]; then
    while IFS= read -r dep; do
      [ -z "$dep" ] && continue
      COORDS="${dep%%^*}"
      EXCLS=""
      if [ "$dep" != "$COORDS" ]; then
        REMAINDER="${dep#*^}"
        while [ -n "$REMAINDER" ]; do
          ONE="${REMAINDER%%^*}"
          EXCLS="$EXCLS
          <exclusion><groupId>${ONE%%:*}</groupId><artifactId>${ONE#*:}</artifactId></exclusion>"
          [ "$REMAINDER" = "$ONE" ] && break
          REMAINDER="${REMAINDER#*^}"
        done
        EXCLS="<exclusions>$EXCLS
        </exclusions>"
      fi
      IFS=':' read -r dg da dv dtype dclass dscope <<< "$COORDS"
      # ${x:+...} rather than $([ -n "$x" ] && echo ...): the latter exits non-zero when the
      # classifier is empty, which under 'set -e' aborts the whole build.
      BLOCK="    <dependency>
      <groupId>$dg</groupId><artifactId>$da</artifactId><version>$dv</version>
      <type>${dtype:-jar}</type>${dclass:+<classifier>$dclass</classifier>}
      <scope>${dscope:-compile}</scope>$EXCLS
    </dependency>"
      DEP_XML="$DEP_XML
$BLOCK"
      DEP_MGMT_XML="$DEP_MGMT_XML
$BLOCK"
    done <<< "$(printf '%s' "$DEP_KEY" | tr ',' '\n')"
  fi

  cat > "$WORK/pom.xml" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>nmvn</groupId>
  <artifactId>prebuilt-cp</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <dependencyManagement>
    <dependencies>$DEP_MGMT_XML
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>$G</groupId>
      <artifactId>$A</artifactId>
      <version>$V</version>
    </dependency>$DEP_XML
  </dependencies>
</project>
POM

  echo ">>> Resolving runtime classpath for $GAV ${DEP_KEY:+(+ per-plugin deps: $DEP_KEY)}..."
  # Prefer the dist's mvn (same snapshot the image embeds) over whatever is on PATH.
  # Windows: bin/mvn is a Unix shell script; use mvn.cmd (same as resolve_boot_catalog.py).
  RESOLVE_MVN="$MAVEN_HOME/bin/mvn"
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
      if [ -f "$MAVEN_HOME/bin/mvn.cmd" ]; then
        RESOLVE_MVN="$MAVEN_HOME/bin/mvn.cmd"
      fi
      ;;
  esac
  if [ ! -f "$RESOLVE_MVN" ] && [ ! -x "$RESOLVE_MVN" ]; then
    RESOLVE_MVN=mvn
  fi
  "$RESOLVE_MVN" -q -f "$WORK/pom.xml" \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
      -Dmdep.outputFile="$CP_FILE" \
      -DincludeScope=runtime

  PLUGIN_JARS=$(NMVN_EXPORTED="$EXPORTED_ARTIFACTS" python3 - "$CP_FILE" <<'PY'
import os
import sys

exported = set(os.environ["NMVN_EXPORTED"].split())
kept = []
for jar in open(sys.argv[1]).read().strip().split(os.pathsep):
    if not jar:
        continue
    # local-repo layout: .../repository/<group dirs>/<artifactId>/<version>/<file>.jar
    parts = jar.split(os.sep)
    ga = None
    if "repository" in parts:
        i = len(parts) - 1 - parts[::-1].index("repository")
        if len(parts) - i >= 4:
            ga = ".".join(parts[i + 1 : -3]) + ":" + parts[-3]
    if ga in exported:
        print(f"    excluded (provided by core): {ga}", file=sys.stderr)
        continue
    kept.append(jar)
print(os.pathsep.join(kept))
PY
)
  if [ -z "$PLUGIN_JARS" ]; then
    echo "Error: could not resolve runtime classpath for $GAV"
    exit 1
  fi

  # kotlin-compiler-embeddable ships IntelliJ XML DOM types (e.g. XmlElement) that are compiled
  # against kotlinx.serialization (KSerializer[] in member signatures) but does NOT declare that
  # library as a Maven dependency and does not shade it. On HotSpot the plugin still works because
  # those members are never reflected; at bake time PrebuiltPluginRealms walks getDeclaredMethods
  # of every realm class, hits NoClassDefFoundError: KSerializer, poisons XmlElement, and the
  # poison cascades to K2JVMCompileMojo — the whole plugin is then SKIPPED->dynamic. Under Crema
  # that fallback fails kapt with IncompatibleClassChangeError on kotlin-reflect enum bodies.
  # Inject kotlinx-serialization-core-jvm so the bake gate keeps the mojos.
  if printf '%s' "$PLUGIN_JARS" | tr ':' '\n' | grep -q '/kotlin-compiler-embeddable-'; then
    SER_VER="${KOTLINX_SERIALIZATION_VERSION:-1.9.0}"
    SER_POM="$WORK/kotlinx-serialization-pom.xml"
    SER_CP="$WORK/kotlinx-serialization.cp"
    cat > "$SER_POM" <<SERPOM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>nmvn</groupId>
  <artifactId>prebuilt-ser</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>org.jetbrains.kotlinx</groupId>
      <artifactId>kotlinx-serialization-core-jvm</artifactId>
      <version>$SER_VER</version>
    </dependency>
  </dependencies>
</project>
SERPOM
    echo ">>> Adding kotlinx-serialization-core-jvm:$SER_VER (required by kotlin-compiler-embeddable bake)..."
    "$RESOLVE_MVN" -q -f "$SER_POM" \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.outputFile="$SER_CP" \
        -DincludeScope=runtime
    # Only the serialization artifact itself — not its transitive kotlin-stdlib, which may be an
    # older version than the one already on the kotlin realm and would reintroduce version mixing.
    while IFS= read -r ser_jar; do
      [ -z "$ser_jar" ] && continue
      case "$ser_jar" in
        *kotlinx-serialization*) ;;
        *) continue ;;
      esac
      case "${CP_SEP}${PLUGIN_JARS}${CP_SEP}" in
        *"${CP_SEP}${ser_jar}${CP_SEP}"*) ;;
        *) PLUGIN_JARS="${PLUGIN_JARS}${PLUGIN_JARS:+$CP_SEP}${ser_jar}"
           echo "    + $ser_jar" ;;
      esac
    done <<< "$(tr "$CP_SEP" '\n' < "$SER_CP")"
  fi

  echo ">>> $A realm jars: $PLUGIN_JARS"

  # One complete realm line per plugin (newline-separated file). See append_spec_line.
  LINE="$GAV"
  [ -n "$DEP_KEY" ] && LINE="$LINE|$DEP_KEY"
  LINE="$LINE=$PLUGIN_JARS"
  append_spec_line "$LINE"
done

# ---------------------------------------------------------------------------------------------------
# 2) Image classpath: boot/ (classworlds) + lib/ (Maven core + deps). The plugin jars are NOT added
#    here — they are loaded by the prebuilt realm at build time (which is enough to make their classes
#    reachable and AOT-compiled, verified empirically).
# ---------------------------------------------------------------------------------------------------
CLASSPATH=""
for jar in "$MAVEN_HOME"/boot/*.jar "$MAVEN_HOME"/lib/*.jar; do
  [ -f "$jar" ] || continue
  cp_append "$jar"
done
if [ -z "$CLASSPATH" ]; then
  echo "Error: no jars under $MAVEN_HOME/boot or $MAVEN_HOME/lib — is the Maven dist complete?"
  ls -la "$MAVEN_HOME" 2>/dev/null || true
  exit 1
fi
echo ">>> Image/javac classpath: $(echo "$CLASSPATH" | tr "$CP_SEP" '\n' | wc -l | tr -d ' ') jars (sep='$CP_SEP')"

# ---------------------------------------------------------------------------------------------------
# 2b) Compile the build-time reflection Feature. JSON reachability metadata cannot register the
#     plugin's classes (they are not on the image classpath, only inside the build-time realm), so
#     the metadata parser never resolves their names and the registration silently does not attach.
#     A Feature runs on the builder JVM and registers the actual realm-loaded Class objects.
# ---------------------------------------------------------------------------------------------------
# Compile under NMVN_WORK_DIR (not prebuilt-feature/ in the repo) so source tree stays clean.
FEATURE_OUT="$NMVN_WORK_DIR/prebuilt-feature-classes"
rm -rf "$FEATURE_OUT" && mkdir -p "$FEATURE_OUT"
# Build-time-ONLY tools, compiled OUTSIDE $FEATURE_OUT so they can never reach the sidecar jar and
# with it the image classpath. SanitizeRealmJars calls JVMCI on purpose; -H:Preserve=package=nmvn.*
# would make those methods reachable as image roots, the static jdk.vm.ci.runtime.JVMCI.runtime
# field would be read during analysis, and the build dies with "JVMCIRuntime should not appear in
# the image" — which is exactly what running the probe in a throwaway JVM is meant to avoid.
TOOLS_OUT="$NMVN_WORK_DIR/prebuilt-feature-tools"
rm -rf "$TOOLS_OUT" && mkdir -p "$TOOLS_OUT"
# Runtime classes at --release 17 so the SAME jar also works on plain JVM Maven (running on 17+);
# builder/image-only classes (Feature needs the org.graalvm.nativeimage module) compile separately.
javac --release 17 -cp "$CLASSPATH" -d "$FEATURE_OUT" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginRealms.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginDescriptorCache.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginRealmCache.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginConfigurationModule.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltRoutingLog.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltReflectionDemand.java"
FEATURE_CP="$(to_cp_path "$FEATURE_OUT")"
javac --add-modules org.graalvm.nativeimage -cp "${CLASSPATH}${CP_SEP}${FEATURE_CP}" -d "$FEATURE_OUT" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltReflectionFeature.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/NmvnLauncher.java"
# No --release here: this tool uses java.lang.classfile (JDK 24+) and only ever runs on the
# builder JDK, so it has no 17-compatibility obligation like the runtime classes above.
javac -cp "${CLASSPATH}${CP_SEP}${FEATURE_CP}" -d "$TOOLS_OUT" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/SanitizeRealmJars.java"
# Sidecar jar (sisu index + nmvn classes) lives under NMVN_WORK_DIR — not written into the Maven
# dist tree — and is appended to the image classpath like any other lib jar.
cp -R "$SCRIPT_DIR/prebuilt-feature/resources/." "$FEATURE_OUT/"
SIDECAR_JAR="$NMVN_WORK_DIR/nmvn-sidecar.jar"
(cd "$FEATURE_OUT" && jar cf "$SIDECAR_JAR" nmvn META-INF)
cp_append "$SIDECAR_JAR"

# ---------------------------------------------------------------------------------------------------
# 3) Sanitize realm jars: strip EnclosingMethod/Signature attributes whose reflective parsing
#    throws InternalError (ancient/odd bytecode, e.g. gson anonymous classes). SVM parses generic
#    signatures of every heap-reachable class and treats InternalError as fatal; the queries
#    already throw identically on JVM Maven, so stripping is behavior-preserving. Jars are
#    rewritten into $WORK/sanitized (originals in ~/.m2 untouched) and the spec repointed.
# ---------------------------------------------------------------------------------------------------
echo ">>> Realm spec ($SPEC_FILE):"
wc -l "$SPEC_FILE" | awk '{print ">>>   " $1 " plugin realm(s)"}'
echo ">>> Sanitizing realm jars + link probe ..."
# JVMCI flags: the tool runs the SAME ResolvedJavaType.link() SVM runs on registered classes,
# against an exact replica of each baked realm; failures land in unlinkable.txt and are dropped
# from the baked maps (see PrebuiltPluginRealms.loadAllClasses). Runs in this throwaway JVM
# because JVMCI touched from image-baked code leaks the JVMCIRuntime singleton into the heap.
# Write sanitized spec to a file (not stdout→shell) so newlines / Windows paths are preserved.
TOOLS_CP="$(to_cp_path "$TOOLS_OUT")"
java \
  -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
  --add-exports=jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED \
  --add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED \
  -cp "${CLASSPATH}${CP_SEP}${TOOLS_CP}" \
  nmvn.SanitizeRealmJars "$SPEC_FILE" "$WORK/sanitized" "$WORK/unlinkable.txt" "$SPEC_FILE"

# ---------------------------------------------------------------------------------------------------
# 4) Build the image.
#    Key prebuilt flags:
#      -Dnmvn.prebuilt.pluginsFile=... feeds newline-separated realm specs to PrebuiltPluginRealms
#      --initialize-at-build-time=PrebuiltPluginRealms,...classworlds
#                                    runs that initializer at build time and snapshots the realms
# ---------------------------------------------------------------------------------------------------
echo ">>> Building native image ..."
#native-image \
#  -classpath "$CLASSPATH" \
#  -Dnmvn.prebuilt.plugins="$PREBUILT_SPEC" \
#  -Dguice_bytecode_gen_option=DISABLED \
#  -march=native \
#  --no-fallback \
#  -H:+UnlockExperimentalVMOptions \
#  -H:+ReportExceptionStackTraces \
#  -H:+AllowJRTFileSystem \
#  -H:+RuntimeClassLoading \
#  -H:+GraalJITCompileAtRuntime \
#  -H:EnableURLProtocols=jar \
#  -H:IncludeResources='META-INF/(maven|sisu|services|plexus)/.*' \
#  -H:IncludeResources='org/apache/maven/plugins/clean/.*' \
#  -H:ConfigurationFileDirectories=prebuilt-reflection,reflection-crema \
#  -H:Preserve=all \
#  -H:Preserve=module=java.compiler \
#  -H:Preserve=module=jdk.compiler \
#  --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
#  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
#  --add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
#  --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
#  '--initialize-at-build-time=nmvn.PrebuiltPluginRealms,nmvn.PrebuiltPluginRealms$Prebuilt,nmvn.PrebuiltPluginRealms$BakedClassLoader,org.apache.maven.plugin.descriptor,org.codehaus.plexus.component.repository,org.codehaus.plexus.configuration,org.codehaus.plexus.classworlds,org.apache.maven.internal.xml,com.ctc.wstx.stax.WstxInputFactory,com.ctc.wstx.util,com.ctc.wstx.api,org.apache.maven.api.xml,org.slf4j,org.apache.maven.slf4j,org.apache.maven.logging,com.sun.tools.javac.api.JavacTool' \
#  --initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm.CLibrary,jdk.internal.jrtfs.SystemImage \
#  org.codehaus.plexus.classworlds.launcher.Launcher \
#  nmvn-native

# Heap for the builder JVM. Default 80% of machine RAM; override if the box is shared or larger.
# Analysis of embabel (kotlin+spotbugs+site fully reflected) OOMs around 50GiB — prefer skipping
# bulk plugins / bulk reflection over only raising this.
NMVN_MAX_RAM_PERCENTAGE="${NMVN_MAX_RAM_PERCENTAGE:-80.0}"

# pluginsFile (not inline -D) so Windows jar paths using ';' do not collide with an entry
# separator, and so the huge multi-line spec is not jammed onto the command line.
run_native_image \
  -J-XX:MaxRAMPercentage="$NMVN_MAX_RAM_PERCENTAGE" \
  -classpath "$CLASSPATH" \
  -Dnmvn.prebuilt.pluginsFile="$SPEC_FILE" \
  -Dnmvn.prebuilt.unlinkable="$WORK/unlinkable.txt" \
  -Dguice_bytecode_gen_option=DISABLED \
  -march=native \
  --no-fallback \
  -H:+UnlockExperimentalVMOptions \
  -H:+ReportExceptionStackTraces \
  -H:+AllowJRTFileSystem \
  -H:+RuntimeClassLoading \
  -H:+GraalJITCompileAtRuntime \
  -H:EnableURLProtocols=jar \
  -H:IncludeResources='META-INF/(maven|sisu|services|plexus)/.*' \
  -H:IncludeResources='org/apache/maven/plugins/clean/.*' \
  -H:ConfigurationFileDirectories=reflection-min \
  -H:Preserve=module=java.base \
  -H:Preserve=module=java.logging \
  -H:Preserve=module=java.xml \
  -H:Preserve=module=java.desktop \
  -H:Preserve=module=java.compiler \
  -H:Preserve=module=jdk.compiler \
  -H:Preserve=package=org.apache.maven.* \
  -H:Preserve=package=com.ctc.wstx.* \
  -H:Preserve=package=org.apache.commons.logging.impl.* \
  -H:Preserve=package=org.eclipse.aether.* \
  -H:Preserve=package=org.slf4j.* \
  -H:Preserve=package=org.codehaus.plexus.* \
  -H:Preserve=package=nmvn.* \
  -H:Preserve=package=com.google.inject.* \
  -H:Preserve=package=com.google.common.* \
  -H:Preserve=package=org.eclipse.sisu.* \
  -H:Preserve=package=org.sonatype.* \
  -H:Preserve=package=org.fusesource.* \
  -H:Preserve=package=org.jline.* \
  -H:Preserve=package=javax.inject.* \
  -H:Preserve=module=jdk.unsupported \
  --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  '--initialize-at-build-time=nmvn.PrebuiltPluginRealms,nmvn.PrebuiltPluginRealms$Prebuilt,nmvn.PrebuiltPluginRealms$BakedClassLoader,nmvn.PrebuiltPluginRealms$SelfFirstRealm,org.apache.maven.plugin.descriptor,org.apache.maven.artifact,org.codehaus.plexus.component.repository,org.codehaus.plexus.configuration,org.codehaus.plexus.classworlds,org.apache.maven.internal.xml,com.ctc.wstx.stax.WstxInputFactory,com.ctc.wstx.util,com.ctc.wstx.api,org.apache.maven.api.xml,org.slf4j,org.apache.maven.slf4j,org.apache.maven.logging,com.sun.tools.javac.api.JavacTool' \
  --initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm.CLibrary,jdk.internal.jrtfs.SystemImage \
  --features=nmvn.PrebuiltReflectionFeature \
  nmvn.NmvnLauncher \
  "$NMVN_OUT_DIR/nmvn-native"

echo ">>> Done: $NMVN_OUT_DIR/nmvn-native"
