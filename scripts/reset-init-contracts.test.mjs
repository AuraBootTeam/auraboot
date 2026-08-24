import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import test from 'node:test';

function read(path) {
  return readFileSync(path, 'utf8');
}

test('OSS reset init contract gate covers reset, DB, marketplace, and seed runner checks', () => {
  assert.ok(existsSync('scripts/check-reset-init-contracts.sh'));

  const gate = read('scripts/check-reset-init-contracts.sh');
  assert.match(gate, /set -euo pipefail/);
  assert.match(gate, /bash -n scripts\/oss-reset-and-init\.sh/);
  assert.match(gate, /bash -n scripts\/reset-db\.sh/);
  assert.match(gate, /bash -n scripts\/db\/cleanup-scheduler-residue\.sh/);
  assert.match(gate, /bash -n scripts\/import-plugins\.sh/);
  assert.match(gate, /bash -n scripts\/oss-golden-stack\.sh/);
  assert.match(gate, /bash -n scripts\/lib\/reset-init-common\.sh/);
  assert.match(gate, /bash -n scripts\/lib\/runtime-process-owner\.sh/);
  assert.match(gate, /bash -n scripts\/lib\/test-runtime-process-owner\.sh/);
  assert.match(gate, /bash -n scripts\/seed-marketplace\.sh/);
  assert.match(gate, /bash -n scripts\/sync-marketplace-catalog\.sh/);
  assert.match(gate, /bash -n scripts\/docker-ga-e2e-bootstrap\.sh/);
  assert.match(gate, /bash -n scripts\/dev\/env\.sh/);
  assert.match(gate, /bash -n scripts\/dev\/xxl-job-true-stack-smoke\.sh/);
  assert.match(gate, /bash -n scripts\/dev\/lib\/process-manager\.sh/);
  assert.match(gate, /bash -n scripts\/dev\/lib\/health\.sh/);
  assert.match(gate, /bash -n scripts\/dev\/run-agent-runtime-full-gate-docker\.sh/);
  assert.match(gate, /bash -n scripts\/env\/reset-and-init\.sh/);
  assert.match(gate, /bash -n scripts\/oss-test\.sh/);
  assert.match(gate, /node --test scripts\/dev\/lib\/env-registry\.test\.mjs/);
  assert.match(gate, /node --test scripts\/dev\/resolve-plugin-backends\.test\.mjs/);
  assert.match(
    gate,
    /node --test web-admin\/tests\/e2e\/product-catalog\/row-contract\.unit\.mjs/,
  );
  assert.match(gate, /node --test scripts\/reset-init-contracts\.test\.mjs/);
  assert.match(gate, /node --test scripts\/db\/cleanup-scheduler-residue\.test\.mjs/);
  assert.match(gate, /node --test scripts\/oss-test-fixture-gate\.test\.mjs/);
  assert.match(gate, /node web-admin\/scripts\/run-showcase-seed-sequence\.test\.mjs/);
  assert.match(gate, /bash scripts\/lib\/test-runtime-process-owner\.sh/);
});

test('OSS reset uses fail-closed runtime ownership instead of global process matching', () => {
  const reset = read('scripts/oss-reset-and-init.sh');
  const helper = read('scripts/lib/runtime-process-owner.sh');
  const hostE2e = read('scripts/host-e2e-up.sh');

  assert.match(reset, /source "\$SCRIPT_DIR\/lib\/runtime-process-owner\.sh"/);
  assert.match(reset, /aura_reset_owner_init/);
  assert.match(reset, /aura_reset_acquire_locks/);
  assert.match(reset, /aura_reset_stop_service "backend"/);
  assert.match(reset, /backend_owner_command_token/);
  assert.match(reset, /bootRun\|"java -jar"/);
  assert.match(reset, /aura_reset_stop_service "web"/);
  assert.match(reset, /aura_reset_stop_service "bff"/);
  assert.match(reset, /aura_reset_register_process "backend"/);
  assert.match(reset, /aura_reset_register_process "backend"[^\n]+"java -jar"/);
  assert.match(reset, /aura_reset_assert_service_owned[\s\\]+\n\s*"backend"[^\n]+"java -jar"/);
  assert.match(reset, /\.\/gradlew --no-daemon :bootJar -x test/);
  assert.match(reset, /aura_reset_spawn_detached[^\n]+java -jar "\$BOOT_JAR"/);
  assert.match(reset, /aura_reset_register_process "web"/);
  assert.match(reset, /aura_reset_register_process "bff"/);
  assert.match(reset, /aura_reset_assert_service_owned[\s\\]+\n\s*"web"/);
  assert.match(reset, /aura_reset_assert_service_owned[\s\\]+\n\s*"bff"/);
  assert.doesNotMatch(reset, /\bpkill\b|\bkillall\b/);

  for (const requiredField of [
    'runtime_id',
    'hostname',
    'pid',
    'pgid',
    'start_time',
    'source_root',
    'process_root',
    'port',
    'tmux_session',
    'tmux_session_id',
    'tmux_pane_id',
    'command_token',
  ]) {
    assert.match(helper, new RegExp(`echo "${requiredField}=`));
  }
  assert.match(helper, /aura_reset_pid_belongs_to_owner/);
  assert.match(helper, /refusing to signal unsafe process group/);
  assert.match(helper, /lock is held or cannot be proved stale/);
  assert.match(helper, /"tcp-port-\$\{AURA_RESET_OWNER_HOST_HASH:0:16\}-\$port"/);
  assert.match(helper, /LC_ALL=C TZ=UTC ps -p/);
  assert.match(helper, /tmux has-session -t "=\$session_name"/);
  assert.match(helper, /tmux kill-session -t "\$tmux_session_id"/);
  assert.doesNotMatch(helper, /tmux kill-session -t "=?\$tmux_session"/);
  assert.doesNotMatch(helper, /\bpkill\b|\bkillall\b/);

  assert.match(hostE2e, /exec bash scripts\/oss-reset-and-init\.sh/);
  assert.doesNotMatch(hostE2e, /FORCE_HOST="\$\{FORCE_HOST:-1\}"/);
});

test('stable XXL true-stack smoke builds once and runs the executable jar', () => {
  const smoke = read('scripts/dev/xxl-job-true-stack-smoke.sh');

  assert.match(smoke, /\.\/gradlew --no-daemon :bootJar -x test/);
  assert.match(smoke, /exec env SPRING_PROFILES_ACTIVE=dev/);
  assert.match(smoke, /java -jar "\$BOOT_JAR"/);
  assert.doesNotMatch(smoke, /:bootRun/);
});

test('OSS reset init contract gate is executable for direct local use', () => {
  const mode = statSync('scripts/check-reset-init-contracts.sh').mode;

  assert.notEqual(
    mode & 0o111,
    0,
    'scripts/check-reset-init-contracts.sh must be executable because docs reference it directly',
  );
});

test('OSS CI runs reset init contract gate when reset or seed files change', () => {
  assert.ok(existsSync('.github/workflows/reset-init-contracts.yml'));

  const workflow = read('.github/workflows/reset-init-contracts.yml');
  assert.match(workflow, /name: Reset Init Contracts/);
  assert.match(workflow, /node-version: '20'/);
  assert.match(workflow, /scripts\/oss-reset-and-init\.sh/);
  assert.match(workflow, /scripts\/reset-db\.sh/);
  assert.match(workflow, /scripts\/seed-marketplace\.sh/);
  assert.match(workflow, /web-admin\/package\.json/);
  assert.match(workflow, /web-admin\/scripts\/run-showcase-seed-sequence\.mjs/);
  assert.match(workflow, /web-admin\/scripts\/run-showcase-seed-sequence\.test\.mjs/);
  assert.match(workflow, /bash scripts\/check-reset-init-contracts\.sh/);
});

test('OSS reset script fails fast and delegates showcase seeds through the ordered runner', () => {
  const reset = read('scripts/oss-reset-and-init.sh');

  assert.match(reset, /set -o pipefail/);
  assert.match(reset, /scripts\/import-plugins\.sh/);
  assert.match(reset, /--profile="\$PLUGIN_IMPORT_PROFILE"/);
  assert.doesNotMatch(reset, /bootstrap seedDemoData/);
  assert.doesNotMatch(reset, /\"seedDemoData\"/);
  assert.match(reset, /"\$SCRIPT_DIR\/seed-marketplace\.sh" 2>&1 \| tail -1/);
  assert.match(reset, /node scripts\/run-showcase-seed-sequence\.mjs[\s\S]*"\$\{seed_phases\[@\]\}"/);
  assert.match(reset, /node scripts\/run-showcase-seed-sequence\.mjs[\s\S]*dashboard-default invariants/);
  assert.doesNotMatch(reset, /npx playwright test tests\/api\/setup\/seed-showcase-/);
});

test('bootstrap setup remains a minimal system initialization API', () => {
  const engine = read('platform/src/main/java/com/auraboot/framework/saas/bootstrap/BootstrapEngineService.java');
  const wizard = read('web-admin/app/routes/setup/SetupWizard.tsx');

  assert.match(engine, /Main entry point\. Runs the minimal bootstrap pipeline/);
  assert.doesNotMatch(engine, /executeRuntimeSetup\(/);
  assert.doesNotMatch(engine, /executeOptionalSetup\(/);
  assert.doesNotMatch(engine, /repairBuiltinPlugins/);
  assert.doesNotMatch(engine, /seed_demo_data/);
  assert.doesNotMatch(wizard, /seedDemoData/);
  assert.doesNotMatch(wizard, /Load demo data/);
});

test('OSS marketplace seed is env-aware and writes the catalog to the system tenant', () => {
  const seed = read('scripts/seed-marketplace.sh');
  const sync = read('scripts/sync-marketplace-catalog.sh');

  assert.match(seed, /set -euo pipefail/);
  assert.match(seed, /deprecated name/);
  assert.match(seed, /exec "\$SCRIPT_DIR\/sync-marketplace-catalog\.sh" "\$@"/);

  assert.match(sync, /PLUGIN_DIRS="\$\{PLUGIN_DIRS:-\$PLUGINS_DIR\}"/);
  assert.match(sync, /SYSTEM_TENANT_ID="\$\{SYSTEM_TENANT_ID:-1\}"/);
  assert.match(sync, /PG_HOST:-localhost/);
  assert.match(sync, /PG_PORT:-5432/);
  assert.match(sync, /PG_DB:-aura_boot/);
  assert.match(sync, /PG_USER:-\$\{USER:-ghj\}/);
  assert.match(sync, /-v ON_ERROR_STOP=1/);
  assert.match(sync, /tenant_id = \$SYSTEM_TENANT_ID/);
  assert.match(sync, /WHERE tenant_id = \$SYSTEM_TENANT_ID/);
  assert.match(sync, /scanned_count=/);
  assert.match(sync, /published_plugin_count=/);
  assert.match(sync, /published_version_count=/);
  assert.match(sync, /Synced marketplace catalog: scanned/);
  assert.doesNotMatch(sync, /Seeded \$count plugins to marketplace/);
});

test('normalized reset entrypoint makes product runtime and profile explicit', () => {
  const script = read('scripts/env/reset-and-init.sh');

  assert.match(script, /--product=oss\|enterprise/);
  assert.match(script, /--runtime=host\|docker/);
  assert.match(script, /--profile=<name>/);
  assert.match(script, /oss:docker\) PROFILE="e2e"/);
  assert.match(script, /enterprise:docker\) PROFILE="enterprise-demo"/);
  assert.match(script, /oss:host/);
  assert.match(script, /oss:docker/);
  assert.match(script, /scripts\/lib\/reset-init-common\.sh/);
  assert.match(script, /aura_export_docker_proxy_defaults/);
  assert.match(script, /aura_sync_marketplace_catalog/);
  assert.doesNotMatch(script, /export_docker_proxy_defaults\(\)/);
  assert.doesNotMatch(script, /sync_marketplace_catalog\(\)/);
  assert.match(script, /docker-ga-e2e-down\.sh" --purge/);
  assert.match(script, /GA_E2E_FRONTEND_IMAGE="\$\{GA_E2E_FRONTEND_IMAGE:-node:22-bookworm-slim\}"/);
  assert.match(script, /aura_sync_marketplace_catalog "\$PROJECT_ROOT" 5433/);
  assert.match(script, /enterprise:host/);
  assert.match(script, /enterprise:docker/);
  assert.match(script, /stop-isolated\.sh" --slug="\$SLUG" --purge/);
  assert.match(script, /enterprise-docker\] building backend jar on host/);
  assert.match(script, /gradlew bootJar --no-daemon -x test/);
  assert.match(script, /ISOLATED_BACKEND_DOCKERFILE="\$\{ISOLATED_BACKEND_DOCKERFILE:-Dockerfile\.runtime\}"/);
  assert.match(script, /scripts\/import-plugins\.sh/);
  assert.match(script, /import_profile="enterprise-demo"/);
  assert.match(script, /--edition=enterprise/);
  assert.match(script, /aura_sync_marketplace_catalog "\$enterprise_root" "\$PG_PORT"/);
});

test('reset init common helper owns shared docker and bootstrap primitives', () => {
  const helper = read('scripts/lib/reset-init-common.sh');

  assert.match(helper, /aura_export_docker_proxy_defaults\(\)/);
  assert.match(helper, /host\.docker\.internal/);
  assert.match(helper, /AURA_DOCKER_NPM_REGISTRY/);
  assert.match(helper, /aura_sync_marketplace_catalog\(\)/);
  assert.match(helper, /sync-marketplace-catalog\.sh/);
  assert.match(helper, /aura_bootstrap_setup_if_needed\(\)/);
  assert.match(helper, /api\/bootstrap\/status/);
  assert.match(helper, /api\/bootstrap\/setup/);
  assert.match(helper, /AURA_BOOTSTRAP_SETUP_TIMEOUT:-30/);
});

test('plugin import profiles use explicit semantic names and deprecate default', () => {
  const profiles = JSON.parse(read('scripts/dev/plugin-import-profiles.json'));

  assert.deepEqual(Object.keys(profiles).sort(), [
    'core',
    'default',
    'demo',
    'e2e',
    'enterprise-demo',
    'pcba-agent',
  ]);
  assert.deepEqual(profiles.core, [
    'core-meta',
    'core-bpm',
    'platform-admin',
    'core-decisionops',
    'core-aurabot',
    'page-manager',
    'org-management',
  ]);
  assert.deepEqual(profiles.demo.slice(0, profiles.core.length), profiles.core);
  assert.ok(profiles.e2e.includes('test-fixtures'));
  for (const [profile, plugins] of Object.entries(profiles)) {
    const adminIndex = plugins.indexOf('platform-admin');
    const decisionOpsIndex = plugins.indexOf('core-decisionops');
    if (adminIndex >= 0 && decisionOpsIndex >= 0) {
      assert.ok(
        adminIndex < decisionOpsIndex,
        `${profile} must import platform-admin before core-decisionops because DecisionOps webhooks reuse admin_webhook permission`,
      );
    }
    const ownershipIndex = plugins.indexOf('core-ownership');
    const crmIndex = plugins.indexOf('crm');
    if (crmIndex >= 0 && ownershipIndex >= 0) {
      assert.ok(
        ownershipIndex < crmIndex,
        `${profile} must import core-ownership before crm when both are present`,
      );
    }
  }
});

test('OSS golden stack stages manifest-declared backend jars from explicit roots before import', () => {
  const golden = read('scripts/oss-golden-stack.sh');
  const resolver = read('scripts/dev/resolve-plugin-backends.mjs');

  assert.match(golden, /--extra-plugin-root PATH/);
  assert.match(golden, /stage_requested_backend_jars\(\)/);
  assert.match(golden, /resolve-plugin-backends\.mjs/);
  assert.match(resolver, /manifest\.backend == null/);
  assert.doesNotMatch(resolver, /pluginType\s*!==\s*['"]hybrid['"]/);
  assert.match(
    golden,
    /:platform-plugin-api:publishToMavenLocal :publishToMavenLocal/,
    'PF4J builds must receive both platform-plugin-api and root auraboot-core publications',
  );
  assert.match(golden, /platform-plugin-api\/auraboot-core publish failed/);
  assert.match(golden, /"\$DEV" gradle "\$runtime_name" --project "\$REPO_ROOT\/platform"/);
  assert.match(golden, /--project "\$backend_dir"/);
  assert.match(golden, /--wrapper "\$REPO_ROOT\/platform\/gradlew" -- clean jar/);
  assert.match(golden, /runtime_env "\$runtime_name" MAVEN_REPO_LOCAL/);
  assert.match(golden, /runtime_env "\$runtime_name" GRADLE_USER_HOME/);
  assert.match(golden, /seeds the runtime's shared wrapper distribution/);
  assert.match(golden, /META-INF\/MANIFEST\.MF/);
  assert.match(golden, /plugin backend entryClass mismatch/);
  assert.match(golden, /entryClass is missing from jar/);
  assert.match(golden, /pnpm install --frozen-lockfile/);
  assert.match(golden, /npm_registry="\$\{NPM_CONFIG_REGISTRY:-https:\/\/registry\.npmmirror\.com\}"/);
  assert.match(golden, /pnpm_version="\$\{AURA_PNPM_VERSION:-9\.15\.9\}"/);
  assert.match(golden, /COREPACK_NPM_REGISTRY="\$npm_registry" COREPACK_DEFAULT_TO_LATEST=0/);
  assert.match(golden, /corepack install --global "pnpm@\$pnpm_version"/);
  assert.match(golden, /frontend-corepack\.log/);
  assert.match(golden, /frontend-dependencies\.log/);
  assert.match(golden, /pf4j-staging\.tsv/);
  assert.match(golden, /staged PF4J jar hash mismatch/);
  assert.match(golden, /AURA_PLUGINS_DIR="\$sd\/pf4j-plugins"/);
  assert.match(golden, /args\+=\("--extra-plugin-root=\$extra_root"\)/);
  assert.ok(
    golden.indexOf('stage_requested_backend_jars "$sd"') <
      golden.indexOf('log "5/9 start backend'),
    'backend jars must be staged before the PF4J host starts',
  );
  assert.match(read('scripts/import-plugins.sh'), /verify_reference_integrity/);
});

test('Product Catalog smoke fails closed on list PID and current DOM business-cell uniqueness', () => {
  const smoke = read('web-admin/tests/e2e/product-catalog/prod-catalog-smoke.spec.ts');
  const rowContract = read('web-admin/tests/e2e/product-catalog/row-contract.mjs');

  assert.match(smoke, /dynamicListRecords\(await response\.json\(\)/);
  assert.match(smoke, /assertUniqueListRecordPid\(visibleRecords, recordPid, recordLabel\)/);
  assert.match(smoke, /getByRole\('cell', \{ name: recordText, exact: true \}\)/);
  assert.match(smoke, /must map to exactly one current DOM row/);
  assert.match(rowContract, /export function assertUniqueListRecordPid/);
  assert.match(rowContract, /must occur exactly once in the list response/);
  assert.doesNotMatch(smoke, /record\.id|numericId|table-row-\$\{index\}/);
});

test('host-side build scripts never fall back to the system Gradle executable', () => {
  const scripts = [
    'scripts/oss-golden-stack.sh',
    'scripts/p1-verify-in-docker.sh',
    'scripts/mes-wms-golden-run.sh',
    'plugins/scripts/build-plugin.sh',
  ];

  for (const script of scripts) {
    const source = read(script);
    assert.doesNotMatch(
      source,
      /(?:^|[;&(|]\s*|\s)gradle\s+(?:--no-daemon\s+)?(?:build|clean|jar)/m,
      `${script} must use a repository Gradle wrapper`,
    );
  }

  const mesWms = read('scripts/mes-wms-golden-run.sh');
  assert.match(mesWms, /--project-dir "\$2" jar --console=plain -q --no-daemon/);

  const buildPlugin = read('plugins/scripts/build-plugin.sh');
  assert.match(buildPlugin, /publishToMavenLocal --quiet --no-daemon/);
  assert.match(buildPlugin, /clean build -x test --quiet --no-daemon/);
});

test('Docker bootstrap entrypoints preserve plugin dependency order', () => {
  for (const path of ['scripts/quickstart.sh', 'scripts/docker-bootstrap.sh']) {
    const script = read(path);
    const adminIndex = script.indexOf('\n  platform-admin');
    const decisionOpsIndex = script.indexOf('\n  core-decisionops');

    assert.ok(adminIndex >= 0, `${path} must import platform-admin`);
    assert.ok(decisionOpsIndex >= 0, `${path} must import core-decisionops`);
    assert.ok(
      adminIndex < decisionOpsIndex,
      `${path} must import platform-admin before core-decisionops because DecisionOps webhooks reuse admin_webhook permission`,
    );
  }
});

test('Docker quickstart reports a bounded plugin import response when an import fails', () => {
  const quickstart = read('scripts/quickstart.sh');

  assert.match(quickstart, /plugin import failed response \(first 1000 chars\)/);
  assert.match(quickstart, /response\[:1000\]/);
});

test('Docker bootstrap entrypoints use two-phase reference validation', () => {
  for (const path of ['scripts/quickstart.sh', 'scripts/docker-bootstrap.sh']) {
    const script = read(path);

    assert.match(
      script,
      /deferReferenceValidation\\":true/,
      `${path} must defer cross-plugin references during each cold-start import`,
    );
    assert.match(
      script,
      /\/api\/plugins\/import\/verify-reference-integrity/,
      `${path} must close the deferred batch with a reference-integrity sweep`,
    );
  }
});

test('Docker quickstart CI always runs on main and manual dispatch, and detects all bootstrap inputs on PRs', () => {
  const workflow = read('.github/workflows/quickstart.yml');

  assert.match(
    workflow,
    /if \[ "\$\{\{ github\.event_name \}\}" != "pull_request" \]; then[\s\S]*echo "run=true"[\s\S]*exit 0/,
  );
  assert.ok(workflow.includes('plugins/'));
  assert.ok(workflow.includes('scripts/quickstart\\.sh'));
  assert.ok(workflow.includes('scripts/docker-bootstrap\\.sh'));
});

test('Gradle resolves SmartEngine artifacts from Maven Central before Aliyun mirrors', () => {
  const build = read('platform/build.gradle');
  const smartEngineCentral = build.indexOf("name = 'Maven Central SmartEngine'");
  const aliyunPublic = build.indexOf("https://maven.aliyun.com/repository/public");

  assert.ok(smartEngineCentral >= 0, 'SmartEngine artifacts need a dedicated Maven Central repository block');
  assert.ok(aliyunPublic >= 0, 'Aliyun public mirror repository should still be declared');
  assert.ok(
    smartEngineCentral < aliyunPublic,
    'SmartEngine artifacts must resolve from Maven Central before Aliyun mirror stickiness can cache partial syncs',
  );
  assert.match(build, /includeGroup ['"]com\.auraboot\.smart\.framework['"]/);
});

test('Gradle plugin markers resolve from Maven Central before Gradle Plugin Portal', () => {
  const settings = read('platform/settings.gradle');
  const pluginManagement = settings.indexOf('pluginManagement');
  const mavenCentral = settings.indexOf('mavenCentral()');
  const gradlePluginPortal = settings.indexOf('gradlePluginPortal()');

  assert.ok(pluginManagement >= 0, 'settings.gradle must declare pluginManagement repositories');
  assert.ok(mavenCentral >= 0, 'pluginManagement should include Maven Central for plugin markers');
  assert.ok(gradlePluginPortal >= 0, 'pluginManagement should keep Gradle Plugin Portal as fallback');
  assert.ok(
    mavenCentral < gradlePluginPortal,
    'Maven Central should be checked before Gradle Plugin Portal for resilient clean Gradle homes',
  );
});

test('CI and Docker builds no longer install SmartEngine into Maven local', () => {
  assert.equal(
    existsSync('scripts/install-smartengine-maven-local.sh'),
    false,
    'root SmartEngine Maven local wrapper should be removed after publishing 4.0.2',
  );
  assert.equal(
    existsSync('platform/scripts/install-smartengine-maven-local.sh'),
    false,
    'platform SmartEngine Maven local installer should be removed after publishing 4.0.2',
  );

  for (const path of ['.github/workflows/backend.yml', '.github/workflows/codeql.yml', 'platform/Dockerfile']) {
    const content = read(path);
    assert.doesNotMatch(content, /Install SmartEngine fork into Maven local/);
    assert.doesNotMatch(content, /install-smartengine-maven-local/);
  }
});

test('markdownlint MD025 ignores frontmatter title without disabling single-h1 checks', () => {
  const config = read('.markdownlint-cli2.jsonc');

  assert.match(config, /"MD025"\s*:\s*\{\s*"front_matter_title"\s*:\s*""\s*\}/);
  assert.doesNotMatch(config, /"MD025"\s*:\s*false/);
});

test('seeded CS agent declares only official CRM tools that can be imported', () => {
  const seed = read('scripts/seed-cs-agent.sql');
  const officialTools = [
    'get:crm_account_common,',
    'get:crm_contact_common,',
    'list:crm_activity_common,',
    'get:crm_activity_common,',
  ];
  for (const staleReference of officialTools.map((tool) => tool.replace('_common', ''))) {
    assert.doesNotMatch(seed, new RegExp(staleReference), `${staleReference} is a removed CRM starter alias`);
  }

  const models = JSON.parse(read('plugins/crm/config/models.json'));
  const modelCodes = new Set(models.map((model) => model.code));

  const commandCodes = new Set();
  for (const file of readdirSync('plugins/crm/config/commands')) {
    if (!file.endsWith('.json')) continue;
    for (const command of JSON.parse(read(`plugins/crm/config/commands/${file}`))) {
      commandCodes.add(command.code);
    }
  }

  const namedQueryCodes = new Set(
    JSON.parse(read('plugins/crm/config/named-queries.json')).map((query) => query.code),
  );

  const toolsMatch = seed.match(/'([^']*custom:send_customer_reply[^']*)',\s*\n\s*120,/);
  assert.ok(toolsMatch, 'seed-cs-agent.sql must define the cs_agent tools list');
  const declaredTools = toolsMatch[1].split(',').map((tool) => tool.trim()).filter(Boolean);

  for (const tool of declaredTools) {
    if (tool.startsWith('cmd:')) {
      assert.ok(commandCodes.has(tool.slice(4)), `${tool} must exist in official CRM commands`);
    } else if (tool.startsWith('get:') || tool.startsWith('list:')) {
      assert.ok(modelCodes.has(tool.slice(tool.indexOf(':') + 1)), `${tool} must reference an official CRM model`);
    } else if (tool.startsWith('nq:')) {
      assert.ok(namedQueryCodes.has(tool.slice(3)), `${tool} must exist in official CRM named queries`);
    } else if (tool === 'custom:send_customer_reply') {
      assert.match(seed, /'send_customer_reply'/);
    } else {
      assert.fail(`Unexpected cs_agent tool declaration: ${tool}`);
    }
  }

  assert.match(
    seed,
    /\[\{"type":"role","roleCode":"tenant_admin"\}\]/,
    'cs_agent approval policy must use the bootstrap role code tenant_admin',
  );
});

test('customer service agent integration scenario follows official CRM activity flow', () => {
  const integrationTest = read('platform/src/test/java/com/auraboot/framework/agent/CustomerServiceAgentIntegrationTest.java');
  const officialReferences = [
    '"mt_crm_account_common"',
    '"mt_crm_contact_common"',
    '"mt_crm_activity_common"',
    'get:crm_account_common\\"',
    'get:crm_contact_common\\"',
    'list:crm_activity_common\\"',
  ];
  for (const staleReference of officialReferences.map((reference) => reference.replace('_common', ''))) {
    assert.doesNotMatch(
      integrationTest,
      new RegExp(staleReference),
      `${staleReference} belongs to the removed CRM starter contract`,
    );
  }

  assert.match(integrationTest, /mt_crm_account_common/);
  assert.match(integrationTest, /mt_crm_contact_common/);
  assert.match(integrationTest, /mt_crm_activity_common/);
  assert.match(integrationTest, /cmd:crm:create_activity/);
  assert.match(integrationTest, /custom:send_customer_reply/);
});

test('direct schema init includes agent observability and command audit correlation tables', () => {
  // The direct-init schema is now the Flyway-generated snapshot (pg_dump format:
  // `CREATE TABLE public.<t> (...)` with lowercase types, indexes in a trailing section).
  const schema = read('platform/src/main/resources/db/snapshots/schema-current.sql');
  const auditStart = schema.indexOf('CREATE TABLE public.ab_command_audit_log (');
  const commandAudit = schema.slice(auditStart, schema.indexOf('\n);', auditStart));
  assert.match(commandAudit, /trace_id character varying\(36\)/);
  assert.match(commandAudit, /span_id character varying\(36\)/);

  const traceStart = schema.indexOf('CREATE TABLE public.ab_ai_trace (');
  const aiTrace = schema.slice(traceStart, schema.indexOf('\n);', traceStart));
  assert.match(aiTrace, /otel_trace_id character varying\(32\)/);
  assert.match(schema, /CREATE INDEX idx_ab_ai_trace_otel_trace_id ON public\.ab_ai_trace/);

  assert.match(schema, /CREATE TABLE public\.ab_gen_ai_usage/);
  assert.match(schema, /CREATE INDEX idx_ab_gen_ai_usage_tenant_created ON public\.ab_gen_ai_usage/);
  assert.match(schema, /CREATE INDEX idx_ab_gen_ai_usage_trace ON public\.ab_gen_ai_usage/);
});

test('plugin import retries each plugin before importing dependents', () => {
  const script = read('scripts/import-plugins.sh');

  assert.match(script, /IMPORT_ATTEMPTS="\$\{IMPORT_ATTEMPTS:-2\}"/);
  assert.match(script, /deprecated profile: default/);
  assert.match(script, /import_plugin_once\(\)/);
  assert.match(script, /while \[ "\$attempt" -le "\$IMPORT_ATTEMPTS" \]/);
  assert.match(script, /sleep "\$attempt"/);
  assert.match(script, /failures\+=\("\$plugin: \$result"\)/);
});

test('plugin import validates latest import state instead of whole history', () => {
  const script = read('scripts/import-plugins.sh');

  assert.match(script, /successful_plugin_ids=\(\)/);
  assert.match(script, /verify_latest_import_statuses\(\)/);
  assert.match(script, /distinct on \(plugin_id\)/);
  assert.match(script, /latest import status is not success/);
  assert.doesNotMatch(script, /where status <> 'success'/i);
});

test('plugin import activates every imported plugin after reference validation', () => {
  const script = read('scripts/import-plugins.sh');

  assert.match(script, /plugin_pid = d\.get\('pluginPid'\)/);
  assert.match(script, /successful_plugin_pids=\(\)/);
  assert.match(script, /enable_imported_plugins\(\)/);
  assert.match(script, /api\/plugins\/\$plugin_pid\/enable/);
  assert.match(script, /data\.get\('status'\) == 'active'/);
  assert.match(script, /failed to enable imported plugin/);

  const tail = script.slice(script.indexOf('if [ "${#failures[@]}" -gt 0 ]'));
  assert.ok(
    tail.indexOf('verify_reference_integrity') < tail.indexOf('enable_imported_plugins'),
    'plugins must be enabled only after cross-plugin references are verified',
  );
  assert.ok(
    tail.indexOf('enable_imported_plugins') < tail.indexOf('seed_bom_defaults_if_imported'),
    'lifecycle-aware handlers and contributions must be active before product seeds run',
  );
});

test('plugin import seeds BOM defaults when bom-standardization is imported', () => {
  const script = read('scripts/import-plugins.sh');

  assert.match(script, /seed_bom_defaults_if_imported\(\)/);
  assert.match(script, /com\.auraboot\.bom-standardization/);
  assert.match(script, /bom:seed_defaults/);
  assert.match(script, /api\/meta\/commands\/execute\/bom:seed_defaults/);
  assert.match(script, /\{"payload":\{\}\}/);
  assert.match(script, /SKIP_BOM_DEFAULT_SEED/);

  const tail = script.slice(script.indexOf('if [ "${#failures[@]}" -gt 0 ]'));
  assert.ok(
    tail.indexOf('verify_reference_integrity') < tail.indexOf('seed_bom_defaults_if_imported'),
    'BOM defaults must be seeded after cross-plugin references are verified',
  );
  assert.ok(
    tail.indexOf('seed_bom_defaults_if_imported') < tail.indexOf('verify_latest_import_statuses'),
    'BOM defaults must not be blocked by the post-import history audit',
  );
});

test('showcase CRM opportunity seeds send date-only values to DATE fields', () => {
  for (const file of [
    'web-admin/tests/api/setup/seed-showcase-data.spec.ts',
    'web-admin/tests/api/setup/seed-showcase-extended.spec.ts',
  ]) {
    const source = read(file);
    assert.doesNotMatch(
      source,
      /crm_opp_expected_close_date:\s*dateTimeAt\(/,
      `${file} must not feed datetime values into crm_opp_expected_close_date`,
    );
    assert.doesNotMatch(
      source,
      /closeDate:\s*dateTimeAt\(/,
      `${file} must keep opportunity closeDate seed values date-only`,
    );
  }
});

test('docker GA bootstrap initializes a blank stack before admin login', () => {
  const script = read('scripts/docker-ga-e2e-bootstrap.sh');

  assert.match(script, /scripts\/lib\/reset-init-common\.sh/);
  assert.match(script, /aura_bootstrap_setup_if_needed[\s\S]*"\$API_BASE"/);
  assert.match(script, /scripts\/import-plugins\.sh/);
  assert.match(script, /--profile="\$PLUGIN_IMPORT_PROFILE"/);
  assert.doesNotMatch(script, /seedDemoData/);
  assert.match(script, /aura_bootstrap_setup_if_needed[\s\S]*scripts\/import-plugins\.sh[\s\S]*# 1\. Login as admin -> JWT/);
});

test('docker GA bootstrap refreshes storage against the active isolated stack only', () => {
  const script = read('scripts/docker-ga-e2e-bootstrap.sh');

  assert.match(script, /API_BASE="http:\/\/localhost:6444"/);
  assert.match(script, /BACKEND_URL="\$API_BASE"[\s\S]*BE_PORT=6444[\s\S]*PGPORT=5433/);
  assert.match(script, /pnpm install --frozen-lockfile/);
  assert.match(script, /pnpm exec playwright test tests\/auth\.setup\.ts[\s\S]*--project=auth --no-deps --reporter=line/);
  assert.match(script, /auth_setup_log=/);
  assert.doesNotMatch(script, /tests\/auth\.setup\.ts[\s\S]*>\/dev\/null 2>&1 \|\| true/);
});

test('agent runtime gate bootstraps then imports plugins before Playwright setup', () => {
  const script = read('scripts/dev/run-agent-runtime-full-gate-docker.sh');

  assert.match(script, /AGENT_RUNTIME_PLUGIN_IMPORT_PROFILE="\$\{AGENT_RUNTIME_PLUGIN_IMPORT_PROFILE:-e2e\}"/);
  assert.match(script, /scripts\/lib\/reset-init-common\.sh/);
  assert.match(script, /aura_bootstrap_setup_if_needed[\s\S]*"\$API_BASE"/);
  assert.match(script, /"AuraBoot Dev"/);
  assert.match(script, /import_agent_runtime_plugins\(\)/);
  assert.match(script, /scripts\/import-plugins\.sh/);
  assert.match(script, /--slug="\$SLUG"/);
  assert.match(script, /--profile="\$AGENT_RUNTIME_PLUGIN_IMPORT_PROFILE"/);
  assert.match(script, /--edition=oss/);
  assert.doesNotMatch(script, /seedDemoData/);
  assert.match(
    script,
    /aura_bootstrap_setup_if_needed[\s\S]*import_agent_runtime_plugins[\s\S]*run_frontend_phase "auth"/,
  );
});

test('marketplace smoke waits for API data and allows cold plugin boot latency', () => {
  const smoke = read('web-admin/tests/e2e/marketplace/marketplace-smoke.spec.ts');

  assert.match(smoke, /MARKETPLACE_API_TIMEOUT = 30000/);
  assert.match(smoke, /MARKETPLACE_CARD_TIMEOUT = 30000/);
  assert.match(smoke, /waitForMarketplaceListReady/);
  assert.match(smoke, /\/api\/marketplace\/plugins/);
  assert.doesNotMatch(smoke, /cards\.first\(\)\)\.toBeVisible\(\{ timeout: 10000 \}\)/);
});
