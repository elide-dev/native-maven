# nmvn catalog — resolve Boot version, then build the specialized image

## Product pipeline

```text
  Spring Boot version + language  (build tool = Maven always)
              │
              ▼
  resolve_boot_catalog.py   →  catalog.json
              │
              ▼
  build-nmvn-catalog.sh     →  build-nmvn-prebuilt.sh  →  nmvn-spring-<bootVersion>
```

**One specialized binary** per Spring Boot version: **`nmvn-spring-<bootVersion>`**  
(e.g. `nmvn-spring-4.1.0`). No core/full matrix, no separate Initializr image.

---

## Prerequisites

1. **Maven for resolution** (pins plugin versions from the Boot parent):
   ```bash
   # Prefer the repo dist (version fidelity with the baked image)
   mvn clean package -DskipTests -Drat.skip=true
   # → apache-maven/target/apache-maven-4.1.0-SNAPSHOT/bin/mvn
   ```
   Or any `mvn` on `PATH` (works; may differ slightly from the baked image).

2. **Resolvable** `spring-boot-starter-parent:<boot-version>` (network or local `.m2`).

3. **Native-image build:** GraalVM toolchain as required by `build-nmvn-prebuilt.sh`
   (large RAM; tens of minutes per image).

---

## Step 1 — Generate the catalog

Keep **one catalog file per Spring Boot version** (recommended naming):

```bash
# From repo root
./catalog/resolve_boot_catalog.py \
  --boot-version 4.1.0 \
  --language java \
  --emit catalog/nmvn-spring-4.1.0.json
```

Default emit (if you omit `--emit`) is `catalog/nmvn-spring-<bootVersion>.json`.

| Flag | Meaning |
|------|---------|
| `--boot-version` | Spring Boot version (`spring-boot-starter-parent`) |
| `--language` | `java` / `kotlin` / `groovy` — metadata; **does not** change the binary name |
| `--build-tool` | Always `maven` |
| `--emit PATH` | Where to write the catalog JSON |
| `--plugins-only` | Print resolved plugin GAVs only |
| `--print-only` | Print catalog JSON to stdout |

### What the resolver does

1. Writes a probe POM under `spring-boot-starter-parent:<version>` with the baked plugins (no versions).
2. Runs Maven `help:effective-pom` to pin versions from the parent (and super POM where needed).
3. Writes the catalog: `binary`, `plugins`, `outOfScope`, `binaries[]`.

---

## Step 2 — Build the specialized native image

**Catalog path is required** (no default — you may have many Boot-line catalogs):

```bash
./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json --dry-run
./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json
# → ./nmvn-spring-4.1.0
# → ./nmvn-spring-4.1.0.plugins
```

| Argument / flag | Meaning |
|-----------------|---------|
| **`<catalog.json>`** | **Required.** Path to a catalog for one Boot version |
| `--only NAME` | Build only that binary name |
| `--dry-run` | List plugins; no native-image |
| `-h` / `--help` | Usage + create-catalog example |

### End-to-end

```bash
# From native-maven repo root
./catalog/resolve_boot_catalog.py \
  --boot-version 4.1.0 \
  --language java \
  --emit catalog/nmvn-spring-4.1.0.json

./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json --dry-run
./build-nmvn-catalog.sh catalog/nmvn-spring-4.1.0.json
```

Multiple Boot versions:

```bash
./catalog/resolve_boot_catalog.py --boot-version 3.5.0 --language java \
  --emit catalog/nmvn-spring-3.5.0.json
./build-nmvn-catalog.sh catalog/nmvn-spring-3.5.0.json
```

---

## What remains in the tree (product)

| Path | Role |
|------|------|
| **`catalog/resolve_boot_catalog.py`** | Boot version → `catalog.json` |
| **`catalog/nmvn-spring-*.json`** | One generated catalog per Boot version |
| **`catalog/README.md`** | This doc |
| **`build-nmvn-catalog.sh`** | Catalog → invoke prebuilt builder per entry |
| **`build-nmvn-prebuilt.sh`** | Actual GraalVM native-image with prebaked plugin realms |
| **`build-nmvn-for-pom.sh`** | Optional: specialize an image for **one** project POM (not the product catalog path) |

**Removed / not used on the product path:** multi-tier Initializr / spring one-shot builders and
multi-binary planners (`build-nmvn-for-initializr`, `build-nmvn-for-spring`, `plan_catalog`,
`nmvn_select`).

---

## Product policy (short)

| | |
|--|--|
| **Binary** | `nmvn-spring-<bootVersion>` |
| **Baked** | Lifecycle + `spring-boot-maven-plugin` (jar + war); versions from Boot parent |
| **outOfScope** | Not currently prebaked (Vaadin, REST Docs, gRPC, SBOM, DGS, native, spring-cloud-contract, hibernate enhance) — dynamic if present; not permanently unbakeable |
| **Hibernate without native** | Works via `spring-boot-starter-data-jpa` (library) |
| **Elide fallback** | Hard-coded `nmvn-native` if specialized binary is missing |

Elide ships a copy of the catalog as `nmvn-catalog.json` and installs the matching specialized binary.
