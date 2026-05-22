import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { request } from '@playwright/test';

const repoRoot = resolve(new URL('../..', import.meta.url).pathname);
const defaultBaseUrl = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';
const defaultBackendUrl = process.env.BACKEND_URL || process.env.SPRING_BOOT_URL || 'http://localhost:6443';
const defaultStorageState = process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json';

function parseArgs(argv) {
  const options = {
    baseUrl: defaultBaseUrl,
    backendUrl: defaultBackendUrl,
    storageState: defaultStorageState,
    dryRun: false,
  };

  for (const arg of argv) {
    if (arg === '--dry-run') {
      options.dryRun = true;
    } else if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg.startsWith('--base-url=')) {
      options.baseUrl = arg.slice('--base-url='.length);
    } else if (arg.startsWith('--backend-url=')) {
      options.backendUrl = arg.slice('--backend-url='.length);
    } else if (arg.startsWith('--storage-state=')) {
      options.storageState = arg.slice('--storage-state='.length);
    } else {
      throw new Error(`Unknown option: ${arg}`);
    }
  }

  return options;
}

function printHelp() {
  console.log(`Usage: node scripts/oss-demo-invariants.mjs [options]

Verifies the bugfix-oss-demo scenario after plugin import and seed.

Options:
  --base-url=<url>          Frontend/BFF base URL (default: PLAYWRIGHT_BASE_URL or http://localhost:5173)
  --backend-url=<url>       Backend URL (default: BACKEND_URL or http://localhost:6443)
  --storage-state=<file>    Admin Playwright storage state (default: PW_ADMIN_STORAGE_STATE or tests/storage/admin.json)
  --dry-run                 Print checks without running them
  -h, --help                Show this help
`);
}

function expectedOssPluginCount() {
  const profilePath = resolve(repoRoot, 'scripts/dev/plugin-import-profiles.json');
  const profiles = JSON.parse(readFileSync(profilePath, 'utf8'));
  return profiles.e2e.length;
}

async function getJson(api, path) {
  const resp = await api.get(path);
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok() || String(body?.code ?? '0') !== '0') {
    throw new Error(`${path} failed: HTTP ${resp.status()} ${JSON.stringify(body).slice(0, 800)}`);
  }
  return body;
}

async function dynamicTotal(api, modelCode) {
  const body = await getJson(api, `/api/dynamic/${modelCode}/list?pageSize=1`);
  return Number(body?.data?.total ?? 0);
}

function assertAtLeast(results, label, actual, expected) {
  const ok = actual >= expected;
  results.push({ label, ok, actual, expected });
  if (!ok) {
    throw new Error(`${label} expected >= ${expected}, got ${actual}`);
  }
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    printHelp();
    return 0;
  }

  const expectedPlugins = expectedOssPluginCount();
  console.log('[oss-demo-invariants] plan');
  console.log(`  baseUrl:          ${options.baseUrl}`);
  console.log(`  backendUrl:       ${options.backendUrl}`);
  console.log(`  storageState:     ${options.storageState}`);
  console.log(`  expectedPlugins:  ${expectedPlugins}`);

  if (options.dryRun) {
    console.log('(dry-run mode: not checking invariants)');
    return 0;
  }

  const backendHealth = await fetch(`${options.backendUrl}/actuator/health`).then((resp) =>
    resp.json(),
  );
  if (backendHealth?.status !== 'UP') {
    throw new Error(`backend health is not UP: ${JSON.stringify(backendHealth)}`);
  }

  const api = await request.newContext({
    baseURL: options.baseUrl,
    storageState: options.storageState,
  });

  const results = [];
  try {
    const pluginBody = await getJson(api, '/api/plugins?current=1&size=500');
    const pluginRecords = pluginBody?.data?.records ?? pluginBody?.data?.data ?? pluginBody?.data ?? [];
    assertAtLeast(results, 'installed OSS plugins', pluginRecords.length, expectedPlugins);
    assertAtLeast(results, 'crm_account rows', await dynamicTotal(api, 'crm_account'), 20);
    assertAtLeast(results, 'crm_contact rows', await dynamicTotal(api, 'crm_contact'), 80);
    assertAtLeast(results, 'crm_lead rows', await dynamicTotal(api, 'crm_lead'), 100);
    assertAtLeast(results, 'crm_opportunity rows', await dynamicTotal(api, 'crm_opportunity'), 40);
    assertAtLeast(results, 'crm_activity rows', await dynamicTotal(api, 'crm_activity'), 300);
    assertAtLeast(results, 'bpm_process_management rows', await dynamicTotal(api, 'bpm_process_management'), 1);
    assertAtLeast(results, 'wd_leave_request rows', await dynamicTotal(api, 'wd_leave_request'), 8);
    assertAtLeast(results, 'wd_leave_balance rows', await dynamicTotal(api, 'wd_leave_balance'), 1);

    const todoBody = await getJson(api, '/api/bpm/tasks/todo?size=100');
    const workflowTodo = (todoBody?.data ?? []).filter((task) =>
      String(task?.processDefinitionIdAndVersion ?? '').startsWith('wd_leave_approval'),
    ).length;
    assertAtLeast(results, 'workflow-demo pending tasks', workflowTodo, 1);

    console.log('[oss-demo-invariants] summary');
    for (const result of results) {
      console.log(`  ok ${result.label}: ${result.actual} >= ${result.expected}`);
    }
    return 0;
  } finally {
    await api.dispose();
  }
}

if (process.argv[1] && new URL(import.meta.url).pathname === process.argv[1]) {
  main().then(
    (status) => {
      process.exitCode = status;
    },
    (error) => {
      console.error(`[oss-demo-invariants] ${error instanceof Error ? error.message : String(error)}`);
      process.exitCode = 1;
    },
  );
}
