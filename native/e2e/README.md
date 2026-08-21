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

This module is standalone by design: it is **not** part of the reactor (there
is nothing to build until a binary exists) and is invoked explicitly.

Usage
-----

```
mvn -f native/e2e/pom.xml test -Dnmvn.binary=build/nmvn-spring-4.1.0 -Dnmvn.spring=4.1.0
```

Tests never build images — `-Dnmvn.binary` is **required** (see
`native/BUILDING.md` for how to build one). Without it every test aborts
(JUnit "skipped") with that reason. CI passes one binary per matrix job, the
same way as above.

| Property          | Default                                             | Meaning                                                                 |
|-------------------|-----------------------------------------------------|-------------------------------------------------------------------------|
| `nmvn.binary`     | *(none — required)*                                 | Binary to test, absolute or relative to the repo root; without it every test skips |
| `nmvn.spring`     | *(none)*                                            | Spring Boot version the binary was baked for, e.g. `4.1.0` or `410`; required by the spring example tests, and narrows them to that version |
| `nmvn.variant`    | `non-crema`                                         | `non-crema` or `crema`; crema skips the delegation-marker tests         |
| `nmvn.maven.home` | `apache-maven/target/apache-maven-4.1.0-SNAPSHOT`   | Maven dist the binary runs against (m2.conf, boot jars)                 |

All tests live in `nmvn.e2e` and run on both variants; variant-specific
assertions are gated per method with `@EnabledIf` (e.g. the delegation
markers are non-crema-only).

What is tested
--------------

* `ImageSymbolTripwireTest` (Linux) — the image must not dynamically export the
  statically-linked JDK's `JNU_*` symbols; the fallback child's `libjava.so`
  would bind to them and die booting (see `linux-hide-static-jdk-symbols` in
  `native/launcher/pom.xml`).
* `BuildWithNonBakedPluginsTest` — `clean package` succeeds AND leaves on-disk
  proof (jar + antrun marker file) that the delegated goals really executed;
  non-crema: the never-baked plugins (enforcer, antrun) AND a
  version-mismatched baked one (clean, pinned older than any flavor bakes)
  delegated to the HotSpot JVM with the pom's execution ids (`goal@id`).
* `FailurePropagationTest` — a failing delegated goal fails the nmvn build,
  via the fallback's exit-code plumbing (`HotspotGoalFailedException`) on
  non-crema.
* `Spring410Test` / `Spring407Test` — one hardcoded test per example project
  under `examples/spring/<version>/` (`clean package -DskipTests=true`),
  enabled only when `-Dnmvn.spring` declares that version; per-variant skips
  are `@EnabledIf` annotations, and a guard test fails when the examples
  directory and the hardcoded list drift apart.

Adding a regression test for a new binary-level invariant: one class per
expensive binary invocation (`@BeforeAll` runs the build via
`NmvnBinary.run(...)` once), one named `@Test` per assertion against it.
