# Building nmvn Native Images

*The bash build scripts under `build-scripts/` were removed after full migration to the Maven
native profiles (see git history for the originals). This document is how images are built now,
plus the operational runbooks that used to live in the scripts' comments.*

## Building

```bash
# 0. once, and again whenever core Maven code changes (the dist is the image classpath):
./mvnw clean install -B -DskipTests

# non-crema image (baked plugins native, everything else on the in-process JVM fallback):
./mvnw -Pnative package -pl native/launcher -am -DskipTests \
  -Dnmvn.plugins="org.apache.maven.plugins:maven-clean-plugin:3.4.0,..."

# crema image (runtime class loading; needs a Crema-enabled GraalVM):
./mvnw -Pnative,crema package -pl native/launcher -am -DskipTests -Dnmvn.plugins="..."
```

Output: `native/launcher/target/${nmvn.imageName}` (default `nmvn-native`).

Plugin selection, one of:

- `-Dnmvn.plugins="g:a:v,g:a:v,..."` — comma/whitespace separated; entries carrying a
  `|canonical-deps` key must be whitespace-separated (the dep encoding uses commas)
- `-Dnmvn.pluginsFile=<ABSOLUTE path>` — newline-separated entries;
  `resolve_boot_catalog.py` emits these next to its catalog JSON
- `-Dnmvn.plugins=@default-lifecycle` — the dist's default lifecycle plugin set (clean,
  resources, compiler, surefire, jar, install, deploy), versions read from the dist
  maven-core's compiled constants by the generate-realm-spec mojo (the generic image)
- nothing — baseline image: no baked plugins, every goal on the JVM fallback (non-crema)

Other knobs (all `-D`, defaults in the launcher pom's `native` profile): `nmvn.imageName`,
`nmvn.mavenHome` (pass an ABSOLUTE path when the dist dir name does not match
`apache-maven-${project.version}` — CI strips `-SNAPSHOT` from poms but keeps the dist dir name),
`nmvn.maxRamPercentage` (builder heap, default 80), `nmvn.configDir` (reflection metadata dir).

NOTE: relative paths in `-Dnmvn.*` Path parameters resolve against the MODULE basedir
(`native/launcher/`), not your invocation directory — the mojo fails loudly on a missing
pluginsFile for exactly this reason. Pass absolute paths.

Where the flags live (three layers):
1. **Invariant** flags travel inside the sidecar jar:
   `native/prebuilt-feature/src/main/resources/META-INF/native-image/org.apache.maven/nmvn-sidecar/native-image.properties`
2. **Parameterized** flags are `<buildArg>` entries in the launcher pom's `native`/`crema` profiles
3. **Computed** classpath is the `@target/nmvn/native-image.args` argfile written by the
   generate-realm-spec mojo (dist `boot/*` + `lib/*` + sidecar + jvm-channel)

## Builder heap

Default `-J-XX:MaxRAMPercentage=80.0` (`-Dnmvn.maxRamPercentage`). Analysis of very large bake
sets (kotlin + spotbugs + site fully reflected) has OOM'd around 50 GiB — prefer skipping bulk
plugins over only raising the limit.

## Image size: -Os

Measured on the Spring catalog (2026-08-10, A/B otherwise identical non-crema builds):

| | with -Os | without |
|---|---|---|
| image size | 171.3 MiB | 195.4 MiB (-12%) |
| code area | 42.0 MiB | 62.9 MiB (-33%) |
| example clean package | ~1.32 s | ~1.16 s (+14% wall) |
| startup (--version) | 65 ms | 62 ms |

Add `-Os` as a buildArg if image size matters more than users' build time.

## RUNBOOK: re-capturing reflection metadata (reflection-non-crema/)

`reflection-non-crema/reachability-metadata.json` holds agent-captured entries (~1.2k) for
image-classpath classes (core Guice/sisu DI, guice circular-dependency JDK proxies, jline FFM
downcalls, JDK types plexus config converters look up). Coverage caveat: agent metadata covers
what the capture workload exercised — a core path no example touches (deploy goal wiring, exotic
mojo parameter types, error paths) can throw MissingReflectionRegistrationError at run time.
Re-capture (APPEND, don't replace):

```bash
MAVEN_OPTS="-agentlib:native-image-agent=config-merge-dir=reflection-non-crema" \
  apache-maven/target/apache-maven-*/bin/mvn -B <goals>   # on a representative project
```

Then harden captured JDK types to allDeclaredConstructors/allPublicMethods/allDeclaredFields
(plexus config converters look up members like Long.parseLong on paths the agent run did not
take). Realm (plugin) classes need no entries here — PrebuiltReflectionFeature registers them
from the build-time realms; JSON could not resolve those names anyway.

## RUNBOOK: re-capturing predefined classes (non-crema)

`reflection-non-crema/` also carries `predefined-classes-config.json` +
`agent-extracted-predefined-classes/`. Sisu CLONES a component implementation whenever one class
is registered under more than one role-hint in a legacy `META-INF/plexus/components.xml`:
PlexusTypeRegistry asks CloningClassSpace for `<impl>$__sisuN`, whose CloningClassLoader GENERATES
that class with ASM and defineClass()es it at run time. Crema allows that; non-crema aborts with
"Classes cannot be defined at runtime". The whole Maven distribution needs exactly TWO of these —
wagon-http declares HttpWagon under both 'http' and 'https', and maven-compat does the same for
DefaultArtifactHandler — so their bytes ship as SVM predefined classes instead.

Re-capture, should sisu/wagon/ASM ever change the generated bytes (the lookup is by hash):

```bash
MAVEN_OPTS="-agentlib:native-image-agent=config-output-dir=/tmp/cfg,experimental-class-define-support" \
  apache-maven/target/apache-maven-*/bin/mvn -B clean package -DskipTests
```

then keep ONLY the `$__sisu` entries: the agent also records every plugin class the JVM loaded
from a plugin realm (~4900 of them), which the image bakes into realms and must not predefine.

## Known CI flake

The native-image builder can die with "NativeImageForkJoinWorkerThread not available in this
platform" — a GraalVM builder race, not caused by any change under test. Re-run the job.
