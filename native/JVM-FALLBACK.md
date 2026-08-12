# JVM Fallback for Non-Baked Plugins (non-crema)

*Status: implemented and verified 2026-08-11; uncommitted prototype on `develop`.*

## Problem

The non-crema nmvn image cannot load classes at run time. Every plugin a build
needs must be baked into the image as a prebuilt realm — any plugin that is
not baked (or is baked at a different version, or with different per-plugin
`<dependencies>`) failed the build hard. Crema solves this with runtime class
loading, but Crema is not stable yet.

## Solution

When routing decides a mojo's plugin is not served from the baked registry,
the mojo execution is delegated to a **HotSpot JVM booted inside the same
process** (no fork), which runs the goal through **stock Maven** from the
already-required `MAVEN_HOME` distribution:

```
mvn --batch-mode --non-recursive --file <current project pom> \
    -Dmaven.repo.local=... [--settings ...] [--offline] [-Duser.props...] \
    <groupId>:<artifactId>:<version>:<goal>@<executionId>
```

The in-process JVM comes from
[graalvm-native-libs](https://github.com/jtulach/graalvm-native-libs)
(`org.apidesign.graalvm:jvm-channel`): `JVM.create` dlopens `$JAVA_HOME`'s
libjvm and calls `JNI_CreateJavaVM`; `executeMain` invokes a static main via
JNI. Related prototype: branch `jtulach/AlterBuild` (execution-plan
alteration; superseded by the plugin-manager override below, which preserves
lifecycle ordering).

## How it works

Three classes in the `prebuilt-feature` sidecar:

| Class | Role |
|---|---|
| `nmvn.JvmFallbackBuildPluginManager` | `@Priority(10)` override of `DefaultBuildPluginManager` (same seam pattern as the descriptor/realm cache overrides). In `executeMojo`, re-uses `PrebuiltPluginRealms.route(...)`: baked → stock path (served from the image heap); anything else → delegate. Runs the delegation at the mojo's exact slot in the lifecycle plan, interleaved with baked mojos. |
| `nmvn.HotspotMavenRunner` | Boots the HotSpot JVM **lazily, once per process** (HotSpot allows a single `JNI_CreateJavaVM`; no re-create after failure) and builds the delegated command line. Forwards user properties (minus Maven-injected `session.*`), user settings, non-default global settings, local repo, offline flag, active profiles. |
| `nmvn.hotspot.HotspotMavenMain` | The HotSpot-side entry point. Calls classworlds `Launcher.launch()` — **not** `main`, because Maven's exiting entry points would `System.exit` the shared process — and reports the exit code through a temp file (a JNI void call has no return channel; `executeMain` swallows JVM-side exceptions). The `Launcher` is configured once from `m2.conf` and reused across goals. **Must remain a single class file**: its bytecode is baked into the image as a *resource* and extracted to a temp directory that goes on the HotSpot classpath. |

Boot shape mirrors the `mvn` script: classpath = `$MAVEN_HOME/boot/*.jar` +
extracted wrapper, `-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf`,
`maven.home`/`maven.multiModuleProjectDirectory` carried over from the values
NmvnLauncher already established.

### Toggle

- Active only at image runtime (`org.graalvm.nativeimage.imagecode=runtime`);
  completely inert on plain-JVM runs and in tests.
- `-Dnmvn.jvm.fallback=false` disables it (restores the old hard-fail).

## Supporting changes

- `native/prebuilt-feature/pom.xml` — `org.apidesign.graalvm:jvm-channel:1.1`
  dependency (released on Maven Central; resolved automatically, no local
  checkout needed). The code targets the 1.1 API; upgrading to the 2.x line
  will require catching the `ClassNotFoundException` it adds to
  `JVM.executeMain`.
- `native/prebuilt-feature/src/main/resources/META-INF/sisu/javax.inject.Named`
  — the sisu index is a checked-in file; the new `@Named` component needs its line.
- `nmvn.PrebuiltReflectionFeature.SIDECAR_COMPONENTS` — every sidecar `@Named`
  class must be listed or Guice cannot instantiate it in the image
  (MissingReflectionRegistrationError).
- `native/pom.xml` — enforcer skipped for the `native/*` modules: the
  `enforceBytecodeVersion` rule bans jvm-channel (JDK 25 bytecode vs the
  dist's JDK 17 ceiling); these modules are not part of the shipped dist and
  hard-require GraalVM 25+ anyway.
- `build-scripts/non-crema/build-nmvn-prebuilt.sh` — appends the jvm-channel
  jar (from the local repo, where the sidecar Maven build resolves it; version
  via `NMVN_JVM_CHANNEL_VERSION`, exact jar via `NMVN_JVM_CHANNEL_JAR`; its
  bundled `native-image.properties` self-registers everything else) and adds
  `-H:IncludeResources='nmvn/hotspot/.*'` for the wrapper bytecode.

## How to test

```bash
# image with the example project's default plugin versions, MINUS maven-jar-plugin
./build-scripts/non-crema/build-nmvn-prebuilt.sh \
  org.apache.maven.plugins:maven-clean-plugin:3.4.0 \
  org.apache.maven.plugins:maven-resources-plugin:3.3.1 \
  org.apache.maven.plugins:maven-compiler-plugin:3.13.0 \
  org.apache.maven.plugins:maven-surefire-plugin:3.5.2 \
  org.apache.maven.plugins:maven-install-plugin:3.1.3

cd examples/java-maven-sample-project
MAVEN_HOME=$PWD/../../apache-maven/target/apache-maven-4.1.0-SNAPSHOT \
  ../../build/nmvn-native clean package
```

Expected: routing logs show clean/resources/compiler/surefire → BAKED and
jar → DYNAMIC; one `booting in-process HotSpot JVM` line; `jar:jar` runs
delegated; jar produced; BUILD SUCCESS. Baking a version *different* from what
the project resolves is also a valid test — routing reports a version
mismatch and delegates (verified: whole default lifecycle delegated through
one shared JVM, ~0.2 s per goal after boot).

Verified scenarios (2026-08-11): mixed baked/delegated run; multiple
delegations reusing the single JVM; pom `<execution>` configuration honored
across the boundary via `goal@executionId` (antrun echo test); non-zero exit
of a delegated goal fails the build; `-Dnmvn.jvm.fallback=false` gate.

## Gaps / known limitations

1. **No shared reactor/session state (the big one).** Each delegated goal is
   a fresh Maven invocation: the pom is re-read (so pom config and
   `@executionId` work), but nothing flows back into the native session. A
   delegated packaging goal (`jar:jar`) writes the jar to disk without
   attaching it to the native session's `MavenProject`, so a downstream baked
   `install`/`deploy` in the same run fails to find the artifact. A delegated
   `install:install` cannot work either when packaging was delegated — its
   own fresh session has no packaged artifact. **Consequence: if the
   packaging plugin is not baked, test with `package`, not `install`.**
   Fix candidates: heuristic re-attach (scan `target/` after a delegated
   packaging goal), or a jvm-channel `Channel` reporting attached artifacts
   back for replay.

2. **`@executionId` must exist in the delegated session's effective pom.**
   Holds for pom-declared and default-lifecycle-bound executions; an
   execution injected dynamically by an extension on the native side would
   not match (falls back to the goal's default config).

3. **CLI-side plugin configuration is forwarded only as `-D` user
   properties.** Mojo parameters without an expression cannot be carried
   across; the resolved-at-native-side configuration XML is not serialized.

4. **Per-goal granularity means per-goal overhead.** ~0.2 s per delegated
   goal after a one-time JVM boot. Fine as a safety net; a mostly-unbaked
   build effectively runs everything through the fallback (correct, but no
   longer "native" performance).

5. **One JVM, no retry.** If the first boot fails (bad `JAVA_HOME`, no
   libjvm), the failure is remembered and all subsequent delegations fail
   fast — `JNI_CreateJavaVM` cannot be attempted twice per process.

6. **Extensions plugins** (`<extensions>true</extensions>`) are routed to the
   fallback if they reach mojo execution, but extension *loading* happens
   earlier (project building) and is not covered — unchanged from before.

7. **MojoExecutionListeners / MojoExecutionScope are not entered for
   delegated mojos** on the native side (the listener events fire inside the
   delegated JVM's session instead). No observed breakage; noted for
   completeness.

8. **Mixed log formats.** Delegated output appears as the inner Maven's plain
   `[INFO]` lines between the native side's logger lines. Cosmetic.

9. ~~**SNAPSHOT dependency.**~~ *Resolved 2026-08-12*: pinned to the released
   `jvm-channel:1.1` from Maven Central. The only delta vs the 2.0 snapshot
   line is error-reporting polish in jvm-channel itself (`executeMain` asserts
   instead of throwing on a missing class) — the runner's empty-exit-file
   check covers that case, and the wrapper extraction already fails loudly
   before `executeMain` can be reached with a missing class.
