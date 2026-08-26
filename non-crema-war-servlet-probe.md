# non-crema: maven-war-plugin fails on the Servlet 3.0 probe

Status: **open decision** — problem fully diagnosed, several solutions analyzed, one verified.
This is the last failing case of the non-crema example suite (3/4 passing, `java-web-war` fails).

## Context

The non-crema variant (`build-scripts/non-crema/build-nmvn-prebuilt.sh`) builds a native Maven
image with plugins baked as prebuilt realms and **no runtime class loading** — no
`-H:+RuntimeClassLoading` / `-H:+GraalJITCompileAtRuntime`, so it runs on a stock GraalVM.

Two problems were already fixed on this branch (both were things Crema provided implicitly):

1. **`-H:+ClassForNameRespectsClassLoader`** — `-H:+RuntimeClassLoading` force-enables this
   option (see `RuntimeClassLoading.Options.onValueUpdate` in SVM); it defaults to *false*.
   Without it, `Class.forName`/`loadClass`/`getResources` resolve through ONE global namespace
   for every loader, which destroys per-realm class identity (observed: core's sisu scan bound
   the war realm's `FileLockNamedLockFactory` into `plexus.core` → `ClassCastException`; with
   all ten plugins baked it surfaced as a null `Injector` in sisu's `AbstractDeferredClass`).
   The flag is now set explicitly in the non-crema script.

2. **Predefined sisu clones** — sisu generates `<impl>$__sisuN` classes with ASM and
   `defineClass()`es them whenever one implementation class is registered under two role-hints
   in a legacy `META-INF/plexus/components.xml`. The Maven distribution needs exactly two
   (`HttpWagon$__sisu1` from wagon-http's http/https, `DefaultArtifactHandler$__sisu1` from
   maven-compat). Their bytes are shipped as SVM predefined classes in `reflection-non-crema/`
   (`predefined-classes-config.json` + `agent-extracted-predefined-classes/*.classdata`).
   These are required by EVERY non-crema image regardless of plugins/projects — removing them
   brings back `UnsupportedFeatureError: ... Tried to define class: HttpWagon$__sisu1` at
   container creation.

The remaining failure is different in kind: it needs a class from the **project being built**,
not from Maven or a plugin.

## The problem

`WarMojo` (maven-war-plugin 3.5.1) decides whether a missing `WEB-INF/web.xml` should fail the
build. Since Servlet 3.0 a webapp may legally omit it, so when `failOnMissingWebXml` is unset
the mojo probes whether the project uses Servlet 3.0+ — **by loading the class**
(`WarMojo.java:288-305`):

```java
// isProjectUsingAtLeastServlet30()
List<String> classpath = project.getCompileClasspathElements();   // the PROJECT's jars
ClassLoader cl = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());

// hasWebServletAnnotationClassInClasspath(cl) — javax checked FIRST, then jakarta
try { Class.forName("javax.servlet.annotation.WebServlet", false, cl); return true; }
catch (ClassNotFoundException e) { /* fall through to jakarta */ }
```

Two compounding failures in a non-crema image:

1. **The class is not in the image.** `jakarta.servlet.annotation.WebServlet` comes from the
   project's dependency tree — in a Spring Boot/Tomcat project from
   `tomcat-embed-core-<ver>.jar` in the local repo. It was never on the image classpath, so
   `Class.forName` bottoms out in `URLClassLoader.defineClass` with bytes SVM has no compiled
   code for → `UnsupportedFeatureError`. Classloader isolation cannot help: there is no
   existing class to resolve *to*. (Crema handled this by loading/interpreting at run time.)

2. **The error escapes the plugin's catch.** The mojo catches `ClassNotFoundException`;
   `UnsupportedFeatureError` is an `Error` and kills the whole build. (If SVM threw CNFE the
   probe would just return false and the build would continue.)

The probe only wants a boolean about a *name* — checking for the jar entry
`jakarta/servlet/annotation/WebServlet.class` would have answered it without loading anything.
The plugin loads instead; fine on HotSpot, fatal here. Not patchable from our side without
modifying upstream plugin bytecode (see solution E).

### Failure signature

```
--- war:3.5.1:war (default-war) @ ... ---
Exception in thread "main" com.oracle.svm.core.jdk.UnsupportedFeatureError:
  Class jakarta.servlet.annotation.WebServlet with hash vmwibuJRq45eCQbCqlbeVC was not provided
  during the image build via the 'predefined-classes-config.json' file.
    at org.apache.maven.plugins.war.WarMojo.hasClassInClasspath(WarMojo.java:305)
    at org.apache.maven.plugins.war.WarMojo.isProjectUsingAtLeastServlet30(WarMojo.java:288)
```

### Key facts established (bytecode / SVM source / experiment)

- The probe is **conditional**: bytecode at `WarMojo:214` is `getfield failOnMissingWebXml;
  ifnonnull → skip`. Any explicit `<failOnMissingWebXml>` value (true or false) skips the
  probe entirely.
- The probe's `URLClassLoader` parent is the **TCCL**, which during mojo execution is the war
  plugin's (baked) realm. `URLClassLoader` is parent-first, so anything resolvable from the
  realm short-circuits the project-classpath lookup and `defineClass` is never reached.
- The probe result only feeds `warArchiver.setExpectWebXml(false)` — i.e. only decides whether
  a **missing** web.xml is an error. If `web.xml` exists it is packaged identically either way.
- SVM **predefined classes** match by hash of the exact class bytes. The same class name has
  independently compiled copies in different artifacts: Tomcat compiles its own copy into
  `tomcat-embed-core`; Jetty-based projects get it from `jakarta.servlet-api`. Observed in this
  repo: two Spring Boot 4.1.0 example projects produced **different hashes** for
  `jakarta.servlet.annotation.WebServlet` (`vmwibuJRq45…` vs `PnMMvZHSMHC…`).
- There is **no SVM option** to soften a missing predefined class into a
  `ClassNotFoundException` — it is always a hard `UnsupportedFeatureError`.
- `javax` is probed **before** `jakarta`, which matters for any fix that bakes only one API.

## Solutions

### A — pom-side: `<failOnMissingWebXml>false</failOnMissingWebXml>`  ✅ verified

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-war-plugin</artifactId>
  <configuration><failOnMissingWebXml>false</failOnMissingWebXml></configuration>
</plugin>
```

Skips the probe via the `ifnonnull` gate. **Verified end-to-end** on a copy of the war example:
war built, `spring-boot:repackage` ran successfully afterwards. Exact stock semantics (it is
the value the user chose). Cost: every war project must add one line; nothing image-side is
fixed — this is a documented workaround, not a fix. There is no user-property/CLI form of this
parameter (checked plugin.xml: no `<expression>`), so it cannot be passed as `-D...`.

### B — image-side: bake servlet-api jars into the war plugin's realm

Add `javax.servlet:javax.servlet-api` **and** `jakarta.servlet:jakarta.servlet-api` to the
war plugin's realm classpath in the non-crema script (same pattern as the existing
kotlin-compiler-embeddable special case). The probe then resolves `WebServlet` from the realm
via parent delegation and never touches the project's bytes. Both APIs because javax is probed
first: with only jakarta baked, a javax-era project would still hit `defineClass` on probe one.

Divergence analysis — B never changes any build that succeeds on stock Maven:

| war project | stock Maven | with B |
|---|---|---|
| has servlet-api dep (every Spring Boot war) | builds | builds (same output) |
| no servlet dep, **has** web.xml | builds | builds (identical war) |
| sets `failOnMissingWebXml` explicitly | probe skipped | probe skipped |
| no servlet dep, **no** web.xml, nothing set | **fails** (early warning) | **builds** (drift) |

The one divergent row converts a stock-failing build into a succeeding one — the author loses
the build-time warning and finds out at deploy time. Unreachable for Spring Boot projects
(starters always pull the jakarta API). Zero maintenance; never hard-fails.

Variant: gate it behind an env var (e.g. `NMVN_WAR_SERVLET_PROBE_BAKE=1`) so the default image
keeps exact stock semantics and the catalog/CI opts in.

A draft implementation existed (block in the per-plugin resolution loop, keyed on
`/maven-war-plugin-`, resolving both APIs via a throwaway pom and appending only the two API
jars) — not applied; see session history around 2026-08-10.

### C-catalog — predefine the BOM-pinned copies of `WebServlet`  (zero semantic drift)

The catalog image is per-Boot-version and Boot's BOM pins the dependency tree, so the candidate
byte-copies of `WebServlet` are **enumerable**: Tomcat's copy (`tomcat-embed-core-11.0.22` for
Boot 4.1.0) plus `jakarta.servlet-api-6.1.0` (Jetty path). Capture each copy's classdata with
the agent and ship them in `reflection-non-crema/` next to the sisu clones (~200 bytes each).
At runtime the probe loads the *project's own* bytes; SVM matches by hash; the class
materializes; the probe answers **truthfully**.

| war project | stock Maven | C-catalog |
|---|---|---|
| BOM-faithful, has servlet dep | builds | builds |
| no servlet dep, no web.xml | **fails** (early warning) | **fails identically** — real CNFE, probe false |
| overrides Tomcat/Jetty version | builds | fails `UnsupportedFeatureError` (hash mismatch) — same as today |

Zero drift; the weakness is the last row's hard cliff for off-BOM projects (not a regression —
today every war fails). Maintenance: classdata is tied to exact jar bytes, so every catalog
regeneration for a new Boot version needs a re-capture — scriptable in the catalog pipeline
(build one sample war per container under the agent, keep the `WebServlet` entries).

Capture command (also documented in the non-crema script for the sisu clones):

```bash
MAVEN_OPTS="-agentlib:native-image-agent=config-output-dir=/tmp/cfg,experimental-class-define-support" \
  apache-maven/target/apache-maven-*/bin/mvn -B clean package -DskipTests   # in a war project
# keep only the jakarta/servlet .../WebServlet entries from predefined-classes-config.json
# (the agent also records ~4900 plugin classes loaded from realms — those are baked, never predefine them)
```

### C-per-pom — agent capture in `build-nmvn-for-pom.sh`

For the pom-specialized variant the target project IS known at image build time, so the agent
can capture whatever live classes that project needs (this also covers annotation processors —
e.g. lombok, which is ~800 runtime-defined classes from `SCL.lombok` resources via its own
`ShadowClassLoader`, far beyond the war probe). Exact semantics; per-project image. Does not
help catalog images.

### E — patch `WarMojo` bytecode during the bake  (rejected for now)

`SanitizeRealmJars` already rewrites realm jars, so a seam exists to patch
`hasClassInClasspath` (catch `Throwable`, or check the jar entry instead of loading).
Semantically perfect and generic — but ships modified upstream plugin code and must be
re-validated on every war-plugin version bump. Bigger hammer than the problem deserves.

## Comparison

| | semantics | covers | maintenance | failure mode |
|---|---|---|---|---|
| A pom-side | exact (user-chosen) | project that sets it | none | n/a (verified) |
| B realm-bake | tiny drift (one stock-failing case now builds) | ALL war projects | none | never fails |
| B + env flag | user of the *image build* chooses | opt-in | none | as B when on |
| C-catalog | **exact** | BOM-faithful projects | re-capture per Boot version | hard error on version override |
| C-per-pom | exact | one project | agent run per image | n/a |
| E bytecode patch | exact | all | per plugin version | fragile |

## Recommendation (as of 2026-08-10)

**C-catalog** for the catalog image — it matches the catalog's existing promise (an image for
one pinned Boot version), keeps stock semantics exact, and the re-capture is automatable.
**B (possibly behind the flag)** is the zero-maintenance fallback if the capture pipeline is
more trouble than it is worth. **A** should be documented regardless, as the user-side escape
hatch that always works.

## How to reproduce / verify

```bash
# build the non-crema catalog image (~20 min)
NMVN_OUT_DIR="$PWD/build-noncrema" NMVN_VARIANT=non-crema \
  ./build-scripts/build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json

# reproduce: build the war example directly with the binary (the e2e suite skips it on
# non-crema via java-web-war's @EnabledIf, exactly because of this probe failure)
(cd examples/spring/410/java-web-war && ../../../../build-noncrema/nmvn-spring-4.1.0 -B clean package -DskipTests=true)
```

Do not run two image builds concurrently — both run `mvn -pl native/... package -am` against
the same `target/` dirs and corrupt each other (looks like an image failure but is not).
