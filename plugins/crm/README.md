# AuraBoot CRM

`com.auraboot.crm` is AuraBoot's single product-grade CRM implementation. It
contains accounts, contacts, leads, opportunities, activities, service,
campaigns, forecasting, the five role-aware workbenches, and the released-QDP
order-commitment evidence chain. The removed `crm-quick-start` learning sample
is not a second CRM.

The core has no business-plugin dependency. Product selection, sales-order
creation, finance rate sources, and industry packages remain downstream
extensions. In particular, `crm:win_opportunity` only closes the opportunity in
the core package; installing Sales adds atomic, idempotent draft-order creation
through a secondary command handler.

## 30-minute adoption path

The recommended clean-room path uses an isolated OSS stack. Docker, JDK 21 and
Gradle must be available; Node.js and a host database are not required. The CRM
package is hybrid, so its PF4J JAR must be built and staged **before** the backend
starts. Importing the DSL first is intentionally rejected when handlers are
missing. Run the following from the repository root:

```bash
crm_adoption_started_epoch=$(date +%s)
crm_adoption_jars="${PWD}/.aura-stack/crm-adoption-plugin-jars"

./platform/gradlew -p platform \
  :platform-plugin-api:publishToMavenLocal \
  --no-daemon
gradle -p plugins/crm/backend clean test jar --no-daemon
mkdir -p "${crm_adoption_jars}"
install -m 0644 \
  plugins/crm/backend/build/libs/crm-plugin-1.2.0.jar \
  "${crm_adoption_jars}/crm-plugin-1.2.0.jar"

ENTERPRISE_PLUGIN_JARS_DIR="${crm_adoption_jars}" \
  scripts/dev/start-isolated.sh \
  --slug=crm-adoption \
  --rebuild \
  --wait

source .aura-stack/crm-adoption.env
curl -fsS -X POST "http://127.0.0.1:${BE_PORT}/api/bootstrap/setup" \
  -H 'Content-Type: application/json' \
  -d '{
    "companyName":"CRM Adoption",
    "adminEmail":"admin@auraboot.com",
    "adminPassword":"Test2026x",
    "adminDisplayName":"Admin User",
    "systemMode":"single"
  }'

scripts/import-plugins.sh \
  --slug=crm-adoption \
  --profile=demo \
  --edition=oss

BACKEND_URL="http://127.0.0.1:${BE_PORT}" \
ADOPTION_STARTED_AT_EPOCH="${crm_adoption_started_epoch}" \
ADOPTION_EVIDENCE_DIR="${PWD}/.workspace/evidence/crm-adoption" \
python3 plugins/crm/scripts/adoption_journey.py
```

The `demo` profile is the canonical OSS demonstration composition. It imports
the platform foundations, ownership support, CRM, Showcase, Agent Control Plane,
Workflow Demo and the dashboard shell in dependency order. The adoption driver
then creates a real `crm_sales_manager` user and proves:

1. CRM Lead Desk menu and page schema are installed;
2. Lead creation, contact and qualification persist;
3. conversion creates the Account, Contact, Opportunity and Customer Request
   relationship graph;
4. the user creates the next open Activity against that Opportunity;
5. total elapsed time is at most 1,800 seconds.

The command exits non-zero on any missing entry point, permission failure,
incorrect relationship, persistence mismatch or deadline breach. Its JSON file
is the machine-readable adoption receipt.

To stop the stack without deleting its database volume:

```bash
scripts/dev/stop-isolated.sh --slug=crm-adoption
```

No data migration is part of this development-stage adoption path.

## First use in the browser

Open the Vite URL printed by `start-isolated.sh` and sign in as
`admin@auraboot.com` / `Test2026x`. For a manual acceptance run:

1. assign the `crm_sales_manager` role to a user;
2. open **Customer Relationship Management → Lead Desk**;
3. create a Lead with company, contact and requirement;
4. run **Mark Contacted**, **Qualify Lead**, then **Convert Lead**;
5. open the generated Opportunity and create the next task/activity;
6. verify the Lead shows the converted Opportunity and the Activity points back
   to that Opportunity.

Do not count a demo as complete if a command only shows a success toast. Reopen
or query each record and verify the persisted status and relationship IDs.

## Install into an existing host stack

Build the hybrid JAR and use the runtime guard so the local artifact, PF4J
registry, imported metadata and page schema are checked together:

```bash
gradle -p plugins/crm/backend clean test jar

node scripts/dev/plugin-runtime-import-guard.mjs \
  --plugin plugins/crm \
  --backend http://127.0.0.1:6443 \
  --hotload-upload \
  --import \
  --expect-handler crm:convert_lead \
  --expect-handler crm:record_order_commitment \
  --page-key crm_lead_desk_workbench \
  --json
```

The generated artifact is
`plugins/crm/backend/build/libs/crm-plugin-1.2.0.jar`, matching `plugin.json`.

## Recovery

All recovery steps are additive and safe to retry; they do not migrate or delete
business data.

1. **Backend unavailable:** check `/actuator/health` and the stack logs. Restart
   only the CRM adoption stack, then rerun the adoption driver.
2. **Command handler missing in the isolated stack:** stop only
   `crm-adoption`, rebuild and restage `crm-plugin-1.2.0.jar`, then rerun
   `start-isolated.sh` with the same `ENTERPRISE_PLUGIN_JARS_DIR`. The database
   volume is preserved; rerun the `demo` import after the backend is healthy.
   A config import cannot register a missing PF4J handler.
3. **Command handler missing in a writable host runtime:** rebuild the JAR and
   rerun the runtime guard with `--hotload-upload`.
4. **Page, menu or field metadata stale:** rerun `scripts/import-plugins.sh` with
   `--profile=demo --edition=oss`, or rerun the runtime guard with `--import`.
5. **Role permission changed after import:** reimport CRM, sign out, and sign in
   again so the tenant's role-permission cache and JWT are refreshed.
6. **A previous adoption journey partially wrote data:** rerun
   `adoption_journey.py`. Every run uses unique fixture identities and verifies
   only its own relationship graph; no reset is required.

If recovery still fails, preserve the adoption JSON, the import-guard JSON and
the backend log. Do not replace the failure with a manual database update.

## Developer verification

```bash
./platform/gradlew -p platform :platform-plugin-api:publishToMavenLocal --no-daemon
gradle -p plugins/crm/backend test jar
node --test plugins/crm/tests/*.test.mjs
node scripts/check-dsl-actions.mjs plugins/crm
node plugins/crm/scripts/verify_release_coverage.mjs
```

The QDP and order-commitment real-stack/browser gates live under
`plugins/crm/scripts/it/` and `plugins/crm/e2e/`.

## Licensing

This package follows the repository's AuraBoot License 1.3. The repository is
public and source-available; it must not be described as OSI open source unless
the repository license and product policy are explicitly changed together.
