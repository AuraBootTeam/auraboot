---
type: product-doc
status: active
---

# OEE Host Golden

This page records the repeatable host-first validation path for the PCBA OEE
dashboard. It exists because this golden depends on both current PCBA
manufacturing dynamic tables and Greptime telemetry; running it by hand is easy
to get wrong.

## Why Host-First

Docker is not required for this validation. The dashboard contract is:

```text
Vite -> BFF -> Spring Boot -> PostgreSQL current PCBA tables
                          -> Greptime oee telemetry
```

The useful evidence is produced by the local host services, not by rebuilding
images. Docker is still valid for release images, CI stacks, or clean isolated
full-suite runs, but this OEE golden should default to host-first.

## Preconditions

- PostgreSQL has an isolated validation DB, for example `aura_oee_host_verify`.
- Greptime is reachable at `http://127.0.0.1:4000`.
- The Spring Boot backend is running on `6443` and has the IoT + PCBA PF4J jars
  on its plugin path.
- Vite + BFF are running on `5173` and `3500`.
- Enterprise plugins are available at `/Users/ghj/work/auraboot/plugins`, or
  `ENTERPRISE_PLUGIN_ROOT` points at the enterprise plugin root.

Example host backend:

```bash
cd /Users/ghj/work/auraboot-oss-oee-converge/platform
SERVER_PORT=6443 \
DATABASE_URL='jdbc:postgresql://localhost:5432/aura_oee_host_verify?charSet=UTF8' \
DATABASE_USERNAME="${USER:-ghj}" \
DATABASE_PASSWORD='' \
AURA_PLUGINS_DIR=/tmp/aura-oee-host-plugin-jars \
IOT_OEE_GREPTIME_URL=http://127.0.0.1:4000 \
IOT_OEE_GREPTIME_DB=public \
./gradlew bootRun
```

Example frontend/BFF:

```bash
cd /Users/ghj/work/auraboot-oss-oee-converge/web-admin
VITE_PORT=5173 \
BFF_PORT=3500 \
SPRING_BOOT_URL=http://127.0.0.1:6443 \
PROXY_TARGET=http://127.0.0.1:6443 \
BFF_INTERNAL_URL=http://127.0.0.1:6443 \
NO_PROXY=localhost,127.0.0.1 \
pnpm dev:full
```

## One Command

After the services are up:

```bash
cd /Users/ghj/work/auraboot-oss-oee-converge
PG_DB=aura_oee_host_verify \
PG_USER="${USER:-ghj}" \
BE_PORT=6443 \
VITE_PORT=5173 \
BFF_PORT=3500 \
GREPTIME_URL=http://127.0.0.1:4000 \
ENTERPRISE_PLUGIN_ROOT=/Users/ghj/work/auraboot/plugins \
./scripts/host-oee-dashboard-golden.sh all
```

The root package script is the same manual entry point:

```bash
pnpm test:oee-host-golden
```

When the default frontend ports are already occupied by another worktree, keep
the backend on the selected database and move only the frontend/BFF pair:

```bash
PG_DB=aura_oee_host_verify \
PG_USER="${USER:-ghj}" \
BE_PORT=6443 \
VITE_PORT=5174 \
BFF_PORT=3501 \
GREPTIME_URL=http://127.0.0.1:4000 \
ENTERPRISE_PLUGIN_ROOT=/Users/ghj/work/auraboot/plugins \
pnpm test:oee-host-golden
```

For CI, run this only on a self-hosted or provisioned job that has the same host
services already running. A generic hosted runner does not have Greptime, the
AuraBoot backend, BFF/Vite, or the enterprise plugin checkout, so Docker is not
the fix for this local contract test; service provisioning is.

The script performs these phases:

1. Health preflight for backend, BFF, Vite, DB, and Greptime.
2. `/api/bootstrap/setup` if the DB is not initialized.
3. Minimal plugin import for the OEE dashboard path.
4. Host-only parent menu fixtures needed when `pcba-solution` is not imported.
5. Deterministic PostgreSQL seed in current `mt_mfg_*` PCBA tables.
6. Deterministic Greptime `oee` telemetry seed.
7. Backend API assertions for fleet and summary values.
8. Playwright browser golden for `/dashboards/view/pe_oee_dashboard`.

The expected API values are:

| Item | Expected |
| --- | --- |
| `SMT-01.oeePct` | `57` |
| `TEST-01.oeePct` | `42.2` |
| Summary `oeePct` | `49.6` |
| Summary `teepPct` | `47.8` |
| `equipmentWithDataCount` | `2` |

## Fast Paths

```bash
./scripts/host-oee-dashboard-golden.sh prepare  # bootstrap + import + seed
./scripts/host-oee-dashboard-golden.sh api      # API assertions only
./scripts/host-oee-dashboard-golden.sh ui       # Playwright golden only
```

Use `SKIP_IMPORT=1` or `SKIP_SEED=1` only when the current DB is already known
to contain the imported dashboard and deterministic seed. Do not use those flags
as completion evidence for a fresh environment.
