<!---
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
Native Maven Binary E2E Tests
========

JUnit 5 end-to-end tests for **already built** nmvn native binaries — the Java
replacement for `test-nmvn-jvm-fallback.sh` and `test-nmvn-examples.sh`. Every
invariant is a named test case, so a red CI run says *which* contract broke
instead of one red shell step. The tests build real projects with the binary:
`examples/jvm-fallback-project` (non-baked plugins in the lifecycle) and every
project under `examples/spring/<version>/`.

The suite tests the whole native stack — sidecar realms, launcher, fallback
plumbing, dist — which is why it is a sibling module of the parts that
produce the image, not part of any one of them. In a plain reactor build
(no `-Dnmvn.binary`) every test aborts (JUnit "skipped"), so the reactor
stays green without an image.

Usage
-----

```
./mvnw -f native/e2e/pom.xml test -Dnmvn.binary=build/nmvn-spring-4.1.0
```

Tests never build images — `-Dnmvn.binary` is **required** (see
`native/BUILDING.md` for how to build one). Without it every test aborts
(JUnit "skipped") with that reason. CI passes one binary per matrix job, the
same way as above.

Everything else is **asked of the binary, never declared**: the suite runs
`--info` once (memoized) and gates tests with `@EnabledIf` conditions on the
self-report — the spring example classes on `flavor` (`spring@4.1.0` enables
`Spring410Test`), the variant-gated assertions on `variant`
(`crema`/`hotspot`). A binary that predates `--info` (PR #44) disables the
gated tests; `InfoTest` skips with the rebuild hint that explains why.

| Property          | Default                                             | Meaning                                                                 |
|-------------------|-----------------------------------------------------|-------------------------------------------------------------------------|
| `nmvn.binary`     | *(none — required)*                                 | Binary to test, absolute or relative to the repo root; without it every test skips |
| `nmvn.maven.home` | `apache-maven/target/apache-maven-4.1.0-SNAPSHOT`   | Maven dist the binary runs against (m2.conf, boot jars)                 |

All tests live in `nmvn.e2e`; variant- and flavor-specific tests carry
`@EnabledIf` gates keyed off the self-report. The fallback-contract classes
(`BuildWithNonBakedPluginsTest`, `FailurePropagationTest`) are non-crema
only: crema would serve the non-baked plugins by runtime class loading, which
currently segfaults the binary (`graal-issue-crema-static-fields.md`). The
mode classes (`NativeModeFailFastTest`, `LegacyModeTest`) are non-crema only
too — `--mode` is a non-crema feature (NATIVEMVN.md "Modes"), and
`CremaModeRejectionTest` proves the crema launcher refuses the flag.

What is tested
--------------

* `InfoTest` — the `--info` introspection contract (flavor derived from the
  bake set, variant crema/hotspot, bare-value topic form, no Maven boot);
  the rest of the suite keys off these answers, so drift fails loudly here.
* `ImageSymbolTripwireTest` (Linux) — the image must not dynamically export the
  statically-linked JDK's `JNU_*` symbols; the fallback child's `libjava.so`
  would bind to them and die booting (see `linux-hide-static-jdk-symbols` in
  `native/launcher/pom.xml`).
* `BuildWithNonBakedPluginsTest` (non-crema) — `--mode=mixed clean package`
  succeeds AND leaves on-disk proof (jar + antrun marker file) that the
  delegated goals really executed; the never-baked plugins (enforcer, antrun)
  AND a version-mismatched baked one (clean, pinned older than any flavor
  bakes) delegated to the HotSpot JVM with the pom's execution ids
  (`goal@id`).
* `FailurePropagationTest` (non-crema) — a failing delegated goal fails the
  `--mode=mixed` build, via the fallback's exit-code plumbing
  (`HotspotGoalFailedException`).
* `NativeModeFailFastTest` (non-crema) — the DEFAULT mode and explicit
  `--mode=native` fail fast on the non-baked plugins with the not-baked error
  that suggests `--mode=mixed`; an unknown `--mode` value dies on the
  launcher's usage error before any Maven machinery.
* `LegacyModeTest` (non-crema) — `--mode=legacy clean package` runs the whole
  build as ONE stock-Maven batch on the HotSpot JVM (jar + marker, the legacy
  short-circuit line, no per-goal delegation); a failing legacy build
  propagates its exit code.
* `CremaModeRejectionTest` (crema) — the crema binary rejects `--mode`
  outright, before any Maven machinery: crema has no modes.
* `Spring410Test` / `Spring407Test` — one hardcoded test per example project
  under `examples/spring/<version>/` (`clean package -DskipTests=true`),
  class-level `@EnabledIf` enables them only when the binary's
  `--info=flavor` says it was baked for that version; per-variant skips are
  method-level `@EnabledIf` annotations.
* `ExamplesLayoutTest` — needs no binary and always runs: fails when an
  `examples/spring/<version>` directory and its test class's hardcoded
  project list drift apart.

Adding a regression test for a new binary-level invariant: one class per
expensive binary invocation (`@BeforeAll` runs the build via
`NmvnBinary.run(...)` once), one named `@Test` per assertion against it.
