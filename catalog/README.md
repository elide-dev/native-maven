# nmvn catalog — resolve Boot version, then build the specialized image

## Product pipeline (local == CI)

```text
  Spring Boot version + language  (build tool = Maven always)
              │
              ▼
  resolve_boot_catalog.py
              │
              ▼
  build/catalogs/nmvn-spring-<boot>.json
              │
              ▼
  build-nmvn-catalog.sh  →  build-nmvn-prebuilt.sh
              │
              ▼
  build/nmvn-spring-<boot>
  build/work/          (scratch)
```

**Maven dist stays in** `apache-maven/target/` (not under `build/`).

**One specialized binary** per Spring Boot version: **`nmvn-spring-<bootVersion>`**.

---

## Prerequisites

1. **Maven dist** (version fidelity with the baked image):
   ```bash
   ./mvnw clean package -DskipTests -Drat.skip=true
   # → apache-maven/target/apache-maven-4.1.0-SNAPSHOT/
   ```
   (Maven Wrapper: `mvnw` / `mvnw.cmd` + `.mvn/wrapper/` — same as CI.)

2. **Resolvable** `spring-boot-starter-parent:<boot-version>` (network or local `.m2`).

3. **`native-image` on PATH** with Crema flags:
   - `-H:+RuntimeClassLoading`
   - `-H:+GraalJITCompileAtRuntime`  
   CI downloads **GraalVM Community 25.2.4** (`graal-25.2.4`) per platform and verifies those flags.
   Locally use the same or another Crema-capable Graal on `PATH`.

---

## Local build (same as CI)

```bash
# From repo root
./catalog/resolve_boot_catalog.py --boot-version 4.1.0 --language java
# → build/catalogs/nmvn-spring-4.1.0.json
# → build/work/catalog-probe-…  (scratch)

./build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json --dry-run
./build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json
# → build/nmvn-spring-4.1.0
# → build/nmvn-spring-4.1.0.plugins
# → build/work/…  (scratch)
```

Multiple Boot versions:

```bash
./catalog/resolve_boot_catalog.py --boot-version 4.1.0 --language java
./catalog/resolve_boot_catalog.py --boot-version 4.0.7 --language java
./build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.1.0.json
./build-nmvn-catalog.sh build/catalogs/nmvn-spring-4.0.7.json
# → build/nmvn-spring-4.1.0
# → build/nmvn-spring-4.0.7
```

| Flag | Meaning |
|------|---------|
| `--boot-version` | Spring Boot version |
| `--language` | Metadata only (`java` / `kotlin` / `groovy`) |
| `--emit PATH` | Optional catalog path (default: `build/catalogs/nmvn-spring-<boot>.json`) |
| `--plugins-only` / `--print-only` | Debug only |

`build-nmvn-catalog.sh` requires the catalog path; output always under **`build/`** by default.

---

## Layout

| Path | Role |
|------|------|
| **`build/catalogs/`** | Generated catalogs |
| **`build/work/`** | Scratch (probes, realms, javac, sanitize) |
| **`build/nmvn-spring-*`** | Specialized native binaries |
| **`apache-maven/target/`** | Maven distribution used for bake (unchanged) |

No env vars required. Optional overrides still exist (`NMVN_OUT_DIR`, `NMVN_WORK_DIR`, `NMVN_MAVEN_HOME`) if you need them.

---

## Product policy (short)

| | |
|--|--|
| **Binary** | `nmvn-spring-<bootVersion>` under `build/` |
| **Baked** | Lifecycle + `spring-boot-maven-plugin` (jar + war) |
| **outOfScope** | Not currently prebaked (Vaadin, native, hibernate enhance, …) |
| **Elide fallback** | Hard-coded `nmvn-native` if specialized binary is missing |
