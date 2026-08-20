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

There is a special directory `nmvn-boot` in the Apache distribution created when
```
native-maven$ mvn install -DdistributionTargetDir="$PWD/apache-maven/target/apache-maven-4.1.0-SNAPSHOT"
```
is invoked. This directory contains additional JAR files that should be put on classpath when
launching HotSpot when `--mode=mixed` is used. `HotspotMavenRunner` makes sure the JARs in this
directory are put on classpath when launching

| Class | Role |
|---|---|
| `nmvn.JvmFallbackBuildPluginManager` | `@Priority(10)` override of `DefaultBuildPluginManager` (same seam pattern as the descriptor/realm cache overrides). In `executeMojo`, re-uses `PrebuiltPluginRealms.route(...)`: baked → stock path (served from the image heap); anything else → delegate. Runs the delegation at the mojo's exact slot in the lifecycle plan, interleaved with baked mojos. |
| `nmvn.HotspotMavenRunner` | Boots the HotSpot JVM **lazily, once per process** (HotSpot allows a single `JNI_CreateJavaVM`; no re-create after failure) and builds the delegated command line. Forwards user properties (minus Maven-injected `session.*`), user settings, non-default global settings, local repo, offline flag, active profiles. |
| `nmvn.hotspot.HotspotMavenMain` | The HotSpot-side entry point. Calls classworlds `Launcher.launch()` — **not** `main`, because Maven's exiting entry points would `System.exit` the shared process — and reports the exit code through return value.
The `Launcher` is configured once from `m2.conf` and reused across goals. Lives in `nmvn-hotspot-main.jar` so it can grow past a single class file. |

Boot shape: classpath = extracted wrapper jar + `$MAVEN_HOME/boot/*.jar` (classworlds),
`-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf`, `maven.home` /
`maven.multiModuleProjectDirectory` carried over from the values NmvnLauncher already
established. Maven types load from the m2.conf realm (`lib/`), not the app loader. The
wrapper jar must stay off that realm: classworlds is parent-first.

### Toggle

- Active only at image runtime (`org.graalvm.nativeimage.imagecode=runtime`);
  completely inert on plain-JVM runs and in tests.
- Selected by the launcher's `--mode` flag (`nmvn.NmvnMode`, spec in
  NATIVEMVN.md "Modes"), which replaced the old boolean `-Dnmvn.jvm.fallback`:
  `--mode=mixed` delegates non-baked goals one by one (this document);
  `--mode=native` — the default — fails fast on a non-baked plugin with
  follow-up suggestions instead; `--mode=legacy` never reaches the per-goal
  seam — the launcher short-circuits the WHOLE command line onto the
  in-process HotSpot JVM (`HotspotMavenRunner.runFullBuild`) before the baked
  world boots.
- The default mode is baked per image (`PrebuiltPluginRealms.MODE_DEFAULT`,
  from the builder's `-Dnmvn.mode.default`; `native` when unset).
- `--mode` is a NON-CREMA feature. The crema capability is DERIVED at image
  build time from the builder's actual `-H:+RuntimeClassLoading` state
  (`PrebuiltPluginRealms.RUNTIME_CLASS_LOADING`, the same answer `--info=variant`
  reports), and with it the whole mode machinery is disabled: crema has one behavior — baked plugins from prebuilt
  realms, everything else natively via runtime class loading (crema IS the
  JVM) — so its launcher rejects any `--mode` with a usage error, the
  execution seam stays inert (the sidecar is on both variants' classpaths),
  and the HotSpot fallback never runs there.
- `-Dnmvn.mode=...` also works on non-crema (the launcher mirrors `-D` args
  into system properties); an explicit `--mode` flag wins over it.

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
- the jvm-channel jar reaches the image classpath as an `--extra-cp` argument
  of the generate-realm-spec mojo (see the launcher pom's `native` profile);
  its bundled `native-image.properties` self-registers everything else.
- `native/hotspot-main` is a provided dependency of the sidecar (reactor
  order only). The sidecar copies that module's `target/*.jar` — not the
  `~/.m2` artifact — to `nmvn/hotspot/nmvn-hotspot-main.jar` so a
  `-pl native/prebuilt-feature` without `-am` fails instead of baking a
  stale wrapper.

## Running and Debugging

Use the `exec:exec` plugin to execute the _native launcher_ code in a HotSpot
JVM. Control the application arguments via `exec.appArgs` property. Control the
HotSpot JVM arguments via `exec.vmArgs` property:
```bash
native-maven$ ./mvnw -f native/launcher exec:exec \
  -Dexec.vmArgs=-agentlib:jdwp=transport=dt_socket,address=8000,server=y,suspend=n \
  -Dexec.appArgs="-f $PWD/examples/java-maven-sample-project/ clean package"
```
The previous example asks for a clean build of the `examples/java-maven-sample-project`
and instructs the JVM to start in a debug mode listening on port 8000.

## Mock Mode

In order to simplify development, there is so called [Mock Dual JVM Mode](https://github.com/elide-dev/native-maven/pull/45).
It allows one to write tests like [mockDualJvmCleanOnly](https://github.com/elide-dev/native-maven/pull/45/changes#diff-1fe55b4a16e72712528866e14e3de604b9a1f08685d6ff61932321ab4967f2f7R139)
that are running _completely in HotSpot JVM_, but can use properties to
control which plugins get executed _"directly"_ and which _"dynamically"_:

```java
System.getProperties().setProperty("nmvn.plugins.maven-clean-plugin", "dynamic");
System.getProperties().setProperty("nmvn.plugins.maven-resources-plugin", "direct");
```

The same properties can be used for [running](#running-and-debugging), hence the
following code executes the `clean` pluging _dynamically_ while all other plugins
are executed directly:

```bash
native-maven$ ./mvnw -f native/launcher exec:exec \
  -Dexec.appArgs="-Dnmvn.plugins.maven-clean-plugin=dynamic -f $PWD/examples/java-maven-sample-project/ clean package"
```

That way one can use HotSpot [JVM debugger](#running-and-debugging) and step
by step inspect the execution that mimics the behavior of final _Native Maven_
application.

<img width="892" height="1143" alt="Stacktrace"
src="https://github.com/user-attachments/assets/a0ce1186-fb43-4334-9d47-84a61dd42835"
/>


## End to End Test

```bash
# image with the example project's default plugin versions, MINUS maven-jar-plugin
./mvnw -Pnative package -pl native/launcher -am -DskipTests -Dnmvn.plugins="\
org.apache.maven.plugins:maven-clean-plugin:3.4.0,\
org.apache.maven.plugins:maven-resources-plugin:3.3.1,\
org.apache.maven.plugins:maven-compiler-plugin:3.13.0,\
org.apache.maven.plugins:maven-surefire-plugin:3.5.2,\
org.apache.maven.plugins:maven-install-plugin:3.1.3"

cd examples/java-maven-sample-project
MAVEN_HOME=$PWD/../../apache-maven/target/apache-maven-4.1.0-SNAPSHOT \
  ../../native/launcher/target/nmvn-native --mode=mixed clean package
```

(Without `--mode=mixed` the default `--mode=native` applies and the same run
must instead fail fast on `jar:jar` with the not-baked error.)

Expected: routing logs show clean/resources/compiler/surefire → BAKED and
jar → DYNAMIC; one `booting in-process HotSpot JVM` line; `jar:jar` runs
delegated; jar produced; BUILD SUCCESS. Baking a version *different* from what
the project resolves is also a valid test — routing reports a version
mismatch and delegates (verified: whole default lifecycle delegated through
one shared JVM, ~0.2 s per goal after boot).

Verified scenarios (2026-08-11): mixed baked/delegated run; multiple
delegations reusing the single JVM; pom `<execution>` configuration honored
across the boundary via `goal@executionId` (antrun echo test); non-zero exit
of a delegated goal fails the build; the off-gate (then
`-Dnmvn.jvm.fallback=false`, now the default `--mode=native`).

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
