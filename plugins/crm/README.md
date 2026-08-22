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
./platform/gradlew --project-dir plugins/crm/backend clean test jar --no-daemon
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

## Excel import business keys

CRM import is configured per model and remains fail-closed unless both the model
`extension.importPolicy` and its `model.{modelCode}.import` permission are present.
Account, Lead and Opportunity support insert/update; Contact supports insert only
because it has no safe unique update key.

Reference columns accept either the stored public PID or one exact business value
declared by the field's `refTarget.importMatchFields`. The current CRM contract is:

| Import model | Reference column | Accepted business keys |
| --- | --- | --- |
| Contact | Account | `crm_acc_code`, `crm_acc_name` |
| Opportunity | Account | `crm_acc_code`, `crm_acc_name` |
| Opportunity | Source Lead | `crm_lead_code`, `crm_lead_company` |

Business-key resolution goes through the normal dynamic-data read path, so tenant,
data-scope and soft-delete rules remain authoritative. Missing and ambiguous values
fail during precheck and perform zero writes; ambiguous display names must be replaced
with a unique business code or PID. Generated templates preserve the normal import
header and add the accepted keys to cell comments and the `填写说明` worksheet.

Blank cells mean “omitted” on insert and “preserve the stored value” on update. Before
command execution, nonblank spreadsheet values are converted using the field metadata
type; database or mapper details are retained in server logs and never returned in the
row-level UI error.

The real-stack Cordys-parity journey is
`web-admin/tests/e2e/crm/crm-multimodel-import-cordys-parity.spec.ts`. Its committed
evidence manifest lives in
`docs/e2e/evidence/crm-multimodel-import-2026-08-13/`; the full Playwright traces stay
in the workspace evidence directory to avoid adding large binaries to clone history.

Correction workbooks are private storage objects exposed only through the scoped import API.
The assembled platform ships the local, MinIO, S3 and OSS implementations and refuses to
silently fall back to local disk when a non-local provider is configured. If a provider upload
fails, the UI retains row-level errors and clearly states that the correction workbook is
unavailable; it does not show a false download action. Reports expire after the configured
retention period (seven days by default). Scheduled cleanup deletes the object and clears only
its download pointer; import status and row counts remain available. The committed recovery
and lifecycle denominator is `14 pass / 1 deferred / 5 untested`: multi-node behavior is
deferred, while cancellation, explicit retry, restart recovery and 10k/100k benchmarks remain
untested. These development-stage checks use fresh databases and require no data migration.

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
./platform/gradlew --project-dir plugins/crm/backend clean test jar --no-daemon

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
./platform/gradlew --project-dir plugins/crm/backend test jar --no-daemon
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
