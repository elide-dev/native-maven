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
# This is a single-plugin proof of concept. Run it; do not expect it to be tuned.
#
# Usage:
#   ./build-nmvn-prebuilt.sh [groupId:artifactId:version ...]
#
# Plugins to bake can be passed as arguments (one g:a:v each); without arguments the default
# list below is used. See build-nmvn-for-pom.sh for deriving the list from a project's pom.xml.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/apache-maven/target"
MAVEN_HOME="$TARGET_DIR/apache-maven-4.1.0-SNAPSHOT"

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
for GAV in "${PLUGINS[@]}"; do
  G="${GAV%%:*}"; REST="${GAV#*:}"; A="${REST%%:*}"; V="${REST#*:}"
  CP_FILE="$WORK/$A.cp"

  cat > "$WORK/pom.xml" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>nmvn</groupId>
  <artifactId>prebuilt-cp</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>$G</groupId>
      <artifactId>$A</artifactId>
      <version>$V</version>
    </dependency>
  </dependencies>
</project>
POM

  echo ">>> Resolving runtime classpath for $GAV ..."
  mvn -q -f "$WORK/pom.xml" \
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
  echo ">>> $A realm jars: $PLUGIN_JARS"

  # The spec consumed by PrebuiltPluginRealms (';'-separated g:a:v=jar1:jar2:... entries).
  PREBUILT_SPEC="${PREBUILT_SPEC:+$PREBUILT_SPEC;}$GAV=$PLUGIN_JARS"
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
# Ship as a REAL jar in lib/ (with the sisu index declaring the @Priority cache overrides): lib
# jars are what the image classpath glob, IncludeResources embedding, Preserve, and the container's
# index scanning all handle canonically — and the same jar makes the sidecar work on JVM Maven.
cp -R "$SCRIPT_DIR/prebuilt-feature/resources/." "$FEATURE_OUT/"
(cd "$FEATURE_OUT" && jar cf "$MAVEN_HOME/lib/nmvn-sidecar.jar" nmvn META-INF)
CLASSPATH="$CLASSPATH:$MAVEN_HOME/lib/nmvn-sidecar.jar"

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

native-image \
  -classpath "$CLASSPATH" \
  -Dnmvn.prebuilt.plugins="$PREBUILT_SPEC" \
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
  '--initialize-at-build-time=nmvn.PrebuiltPluginRealms,nmvn.PrebuiltPluginRealms$Prebuilt,nmvn.PrebuiltPluginRealms$BakedClassLoader,org.apache.maven.plugin.descriptor,org.apache.maven.artifact,org.codehaus.plexus.component.repository,org.codehaus.plexus.configuration,org.codehaus.plexus.classworlds,org.apache.maven.internal.xml,com.ctc.wstx.stax.WstxInputFactory,com.ctc.wstx.util,com.ctc.wstx.api,org.apache.maven.api.xml,org.slf4j,org.apache.maven.slf4j,org.apache.maven.logging,com.sun.tools.javac.api.JavacTool' \
  --initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm.CLibrary,jdk.internal.jrtfs.SystemImage \
  --features=nmvn.PrebuiltReflectionFeature \
  nmvn.NmvnLauncher \
  nmvn-native

echo ">>> Done: $SCRIPT_DIR/nmvn-native"
