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
# Plugins to bake can be passed as arguments (one g:a:v each, optionally with |canonical-deps
# from build-nmvn-for-pom.sh); without arguments the default list below is used.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/apache-maven/target"
MAVEN_HOME="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT"

# ---------------------------------------------------------------------------------------------------
# 0a) PIN THE TOOLCHAIN. This image needs DEVELOPMENT options — -H:+RuntimeClassLoading (Crema) and
#     -H:+GraalJITCompileAtRuntime — that exist only in a GraalVM built from source. A stock release
#     does not have them, and neither does a newer dev build where they were renamed, so inheriting
#     whatever happens to be on PATH silently decides whether the build can work at all.
#
#     Pinning here buys two things: the produced image no longer depends on which shell launched the
#     build (it must not — a per-pom build service has to be reproducible), and a wrong toolchain
#     fails IMMEDIATELY with an explanation instead of surfacing as "Unrecognized option
#     '-H:+GraalJITCompileAtRuntime'" after the plugin-resolution and link-probe phases have already
#     run. JAVA_HOME/PATH are exported so native-image, javac and java (SanitizeRealmJars needs
#     java.lang.classfile plus JVMCI) all come from the same JDK as the image builder.
#
#     Override with: NMVN_GRAALVM_HOME=/path/to/graalvm/Contents/Home
# ---------------------------------------------------------------------------------------------------
NMVN_GRAALVM_HOME="${NMVN_GRAALVM_HOME:-$HOME/Developer/graal/sdk/mxbuild/darwin-aarch64/GRAALVM_COMMUNITY_JAVA25/graalvm-community-25.3.4-dev+7.1/Contents/Home}"

if [ ! -x "$NMVN_GRAALVM_HOME/bin/native-image" ]; then
  echo "Error: no native-image at $NMVN_GRAALVM_HOME/bin/native-image"
  echo "       Set NMVN_GRAALVM_HOME to a GraalVM built from source, e.g."
  echo "       ~/Developer/graal/sdk/mxbuild/<platform>/GRAALVM_COMMUNITY_JAVA25/graalvm-community-*/Contents/Home"
  exit 1
fi

export JAVA_HOME="$NMVN_GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

EXPERT_OPTIONS="$(native-image --expert-options-all 2>&1 || true)"
# Matched with a shell 'case', NOT 'printf ... | grep -q': grep -q exits on the first match and closes
# the pipe, printf then dies of SIGPIPE, and under 'set -o pipefail' the pipeline reports THAT failure
# — so a successful match would look like a missing option and every toolchain would be rejected.
for required in RuntimeClassLoading GraalJITCompileAtRuntime; do
  if case "$EXPERT_OPTIONS" in *"$required"*) false ;; *) true ;; esac; then
    echo "Error: this toolchain does not support -H:±$required, which nmvn's baked realms depend on:"
    echo "         $(native-image --version 2>&1 | head -1)"
    echo "         NMVN_GRAALVM_HOME=$NMVN_GRAALVM_HOME"
    echo "       Point NMVN_GRAALVM_HOME at a GraalVM with Crema/runtime-class-loading support."
    exit 1
  fi
done

echo ">>> Toolchain: $(native-image --version 2>&1 | head -1)"
echo ">>>            $JAVA_HOME"

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

# Drop plugins listed in NMVN_SKIP_PLUGINS (comma-separated artifactId or g:a), same as for-pom.
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
if [ ! -d "$MAVEN_HOME" ]; then
  TARBALL="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT-bin.tar.gz"
  if [ ! -f "$TARBALL" ]; then
    echo "Error: $TARBALL not found. Build Maven first:"
    echo "  mvn clean package -DskipTests -Drat.skip=true"
    exit 1
  fi
  echo "Extracting $TARBALL ..."
  tar -xzf "$TARBALL" -C "$TARGET_DIR"
fi

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
WORK="$SCRIPT_DIR/.prebuilt-work"
mkdir -p "$WORK"

EXPORTED_ARTIFACTS=$(unzip -p "$MAVEN_HOME"/lib/maven-core-*.jar META-INF/maven/extension.xml \
  | sed -n 's/.*<exportedArtifact>\(.*\)<\/exportedArtifact>.*/\1/p')

PREBUILT_SPEC=""
for ENTRY in "${PLUGINS[@]}"; do
  # Each entry is "groupId:artifactId:version" optionally followed by "|<canonical dependencies>"
  # (emitted by build-nmvn-for-pom.sh; see PrebuiltPluginRealms.dependencyKey for the encoding).
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
  RESOLVE_MVN="$MAVEN_HOME/bin/mvn"
  [ -x "$RESOLVE_MVN" ] || RESOLVE_MVN=mvn
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
      case ":$PLUGIN_JARS:" in
        *":$ser_jar:"*) ;;
        *) PLUGIN_JARS="$PLUGIN_JARS:$ser_jar"
           echo "    + $ser_jar" ;;
      esac
    done <<< "$(tr ':' '\n' < "$SER_CP")"
  fi

  echo ">>> $A realm jars: $PLUGIN_JARS"

  # The spec consumed by PrebuiltPluginRealms (';'-separated entries, each
  # g:a:v[|canonical-dependencies]=jar1:jar2:...). The dependency key travels with the entry so the
  # runtime can require an exact match before serving the baked realm.
  PREBUILT_SPEC="${PREBUILT_SPEC:+$PREBUILT_SPEC;}$GAV${DEP_KEY:+|$DEP_KEY}=$PLUGIN_JARS"
done

# ---------------------------------------------------------------------------------------------------
# 2) Image classpath: boot/ (classworlds) + lib/ (Maven core + deps). The plugin jars are NOT added
#    here — they are loaded by the prebuilt realm at build time (which is enough to make their classes
#    reachable and AOT-compiled, verified empirically).
# ---------------------------------------------------------------------------------------------------
CLASSPATH=""
for jar in "$MAVEN_HOME"/boot/*.jar "$MAVEN_HOME"/lib/*.jar; do
  CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
done

# ---------------------------------------------------------------------------------------------------
# 2b) Compile the build-time reflection Feature. JSON reachability metadata cannot register the
#     plugin's classes (they are not on the image classpath, only inside the build-time realm), so
#     the metadata parser never resolves their names and the registration silently does not attach.
#     A Feature runs on the builder JVM and registers the actual realm-loaded Class objects.
# ---------------------------------------------------------------------------------------------------
FEATURE_OUT="$SCRIPT_DIR/prebuilt-feature/classes"
rm -rf "$FEATURE_OUT" && mkdir -p "$FEATURE_OUT"
# Build-time-ONLY tools, compiled OUTSIDE $FEATURE_OUT so they can never reach the sidecar jar and
# with it the image classpath. SanitizeRealmJars calls JVMCI on purpose; -H:Preserve=package=nmvn.*
# would make those methods reachable as image roots, the static jdk.vm.ci.runtime.JVMCI.runtime
# field would be read during analysis, and the build dies with "JVMCIRuntime should not appear in
# the image" — which is exactly what running the probe in a throwaway JVM is meant to avoid.
TOOLS_OUT="$SCRIPT_DIR/prebuilt-feature/tool-classes"
rm -rf "$TOOLS_OUT" && mkdir -p "$TOOLS_OUT"
# Runtime classes at --release 17 so the SAME jar also works on plain JVM Maven (running on 17+);
# builder/image-only classes (Feature needs the org.graalvm.nativeimage module) compile separately.
javac --release 17 -cp "$CLASSPATH" -d "$FEATURE_OUT" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginRealms.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginDescriptorCache.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginRealmCache.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltPluginConfigurationModule.java"
javac --add-modules org.graalvm.nativeimage -cp "$CLASSPATH:$FEATURE_OUT" -d "$FEATURE_OUT" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/PrebuiltReflectionFeature.java" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/NmvnLauncher.java"
# No --release here: this tool uses java.lang.classfile (JDK 24+) and only ever runs on the
# builder JDK, so it has no 17-compatibility obligation like the runtime classes above.
javac -cp "$CLASSPATH:$FEATURE_OUT" -d "$TOOLS_OUT" \
  "$SCRIPT_DIR/prebuilt-feature/src/nmvn/SanitizeRealmJars.java"
# Ship as a REAL jar in lib/ (with the sisu index declaring the @Priority cache overrides): lib
# jars are what the image classpath glob, IncludeResources embedding, Preserve, and the container's
# index scanning all handle canonically — and the same jar makes the sidecar work on JVM Maven.
cp -R "$SCRIPT_DIR/prebuilt-feature/resources/." "$FEATURE_OUT/"
(cd "$FEATURE_OUT" && jar cf "$MAVEN_HOME/lib/nmvn-sidecar.jar" nmvn META-INF)
CLASSPATH="$CLASSPATH:$MAVEN_HOME/lib/nmvn-sidecar.jar"

# ---------------------------------------------------------------------------------------------------
# 3) Sanitize realm jars: strip EnclosingMethod/Signature attributes whose reflective parsing
#    throws InternalError (ancient/odd bytecode, e.g. gson anonymous classes). SVM parses generic
#    signatures of every heap-reachable class and treats InternalError as fatal; the queries
#    already throw identically on JVM Maven, so stripping is behavior-preserving. Jars are
#    rewritten into $WORK/sanitized (originals in ~/.m2 untouched) and the spec repointed.
# ---------------------------------------------------------------------------------------------------
echo "$PREBUILT_SPEC" > "$WORK/spec.txt"
echo ">>> Sanitizing realm jars + link probe ..."
# JVMCI flags: the tool runs the SAME ResolvedJavaType.link() SVM runs on registered classes,
# against an exact replica of each baked realm; failures land in unlinkable.txt and are dropped
# from the baked maps (see PrebuiltPluginRealms.loadAllClasses). Runs in this throwaway JVM
# because JVMCI touched from image-baked code leaks the JVMCIRuntime singleton into the heap.
PREBUILT_SPEC=$(java \
  -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
  --add-exports=jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED \
  --add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED \
  -cp "$CLASSPATH:$TOOLS_OUT" \
  nmvn.SanitizeRealmJars "$WORK/spec.txt" "$WORK/sanitized" "$WORK/unlinkable.txt")
echo "$PREBUILT_SPEC" > "$WORK/spec.txt"

# ---------------------------------------------------------------------------------------------------
# 4) Build the image.
#    Key prebuilt flags:
#      -Dnmvn.prebuilt.plugins=...   feeds the realm spec to PrebuiltPluginRealms' static initializer
#      --initialize-at-build-time=PrebuiltPluginRealms,...classworlds
#                                    runs that initializer at build time and snapshots the realms
#      -H:IncludeResources=...clean/.* embeds any runtime resources the mojo reads from its jar
#                                    (the frozen realm cannot open the jar at runtime)
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

native-image \
  -J-XX:MaxRAMPercentage="$NMVN_MAX_RAM_PERCENTAGE" \
  -classpath "$CLASSPATH" \
  -Dnmvn.prebuilt.plugins="$PREBUILT_SPEC" \
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
  nmvn-native

echo ">>> Done: $SCRIPT_DIR/nmvn-native"
