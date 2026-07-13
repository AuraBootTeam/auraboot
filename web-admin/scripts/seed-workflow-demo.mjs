import { request } from '@playwright/test';

const DEFAULT_BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';
const DEFAULT_STORAGE_STATE = process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json';
const DEFAULT_MIN_REQUESTS = 12;
const BALANCE_YEAR = 2026;
const ANNUAL_REMAINING_DAYS = 18;

function parseArgs(argv) {
  const options = {
    baseUrl: DEFAULT_BASE_URL,
    storageState: DEFAULT_STORAGE_STATE,
    minRequests: DEFAULT_MIN_REQUESTS,
    dryRun: false,
  };

  for (const arg of argv) {
    if (arg === '--dry-run') {
      options.dryRun = true;
    } else if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg.startsWith('--base-url=')) {
      options.baseUrl = arg.slice('--base-url='.length);
    } else if (arg.startsWith('--storage-state=')) {
      options.storageState = arg.slice('--storage-state='.length);
    } else if (arg.startsWith('--min-requests=')) {
      options.minRequests = Number.parseInt(arg.slice('--min-requests='.length), 10);
    } else {
      throw new Error(`Unknown option: ${arg}`);
    }
  }

  if (!Number.isFinite(options.minRequests) || options.minRequests < 1) {
    throw new Error('--min-requests must be a positive integer');
  }

  return options;
}

function printHelp() {
  console.log(`Usage: node scripts/seed-workflow-demo.mjs [options]

Seeds OSS workflow-demo business data through product APIs:
  - leave balances
  - leave requests
  - process instances
  - pending and completed approval tasks

Options:
  --base-url=<url>          Frontend/BFF base URL (default: PLAYWRIGHT_BASE_URL or http://localhost:5173)
  --storage-state=<file>    Admin Playwright storage state (default: PW_ADMIN_STORAGE_STATE or tests/storage/admin.json)
  --min-requests=<count>    Minimum leave requests to keep available (default: ${DEFAULT_MIN_REQUESTS})
  --dry-run                 Print plan only
  -h, --help                Show this help
`);
}

async function getJson(api, path, options = {}) {
  const resp = await api.get(path, options);
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok() || String(body?.code ?? '0') !== '0') {
    throw new Error(`${path} failed: HTTP ${resp.status()} ${JSON.stringify(body).slice(0, 800)}`);
  }
  return body;
}

async function postJson(api, path, data, options = {}) {
  const resp = await api.post(path, {
    data,
    timeout: options.timeout ?? 45_000,
  });
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok() || String(body?.code ?? '0') !== '0') {
    throw new Error(`${path} failed: HTTP ${resp.status()} ${JSON.stringify(body).slice(0, 800)}`);
  }
  return body;
}

async function executeCommand(api, commandCode, payload, targetRecordId, operationType) {
  const data = { payload };
  if (targetRecordId) data.targetRecordId = targetRecordId;
  if (operationType) data.operationType = operationType;
  const body = await postJson(
    api,
    `/api/meta/commands/execute/${encodeURIComponent(commandCode)}`,
    data,
  );
  const result = body?.data?.data ?? {};
  return String(result.recordId ?? result.pid ?? result.id ?? '');
}

async function countDynamic(api, modelCode) {
  const body = await getJson(api, `/api/dynamic/${modelCode}/list?pageSize=1`);
  return Number(body?.data?.total ?? 0);
}

function addDays(date, days) {
  const result = new Date(date);
  result.setUTCDate(result.getUTCDate() + days);
  return result;
}

function formatDate(date) {
  return date.toISOString().slice(0, 10);
}

async function getCurrentUser(api) {
  const body = await getJson(api, '/api/auth/me');
  const user = body?.data?.user ?? body?.data ?? body;
  const pid = String(user?.pid ?? user?.id ?? '');
  if (!pid) {
    throw new Error('/api/auth/me did not return a user pid/id');
  }
  return {
    pid,
    label: String(user?.nickName ?? user?.nick_name ?? user?.email ?? pid),
  };
}

async function listDynamic(api, modelCode, pageSize = 200) {
  const body = await getJson(api, `/api/dynamic/${modelCode}/list?pageNum=1&pageSize=${pageSize}`);
  return body?.data?.records ?? [];
}

/**
 * Every user the applicant memberpicker can offer, so a demo submit works no matter who
 * is picked — wd_req_applicant is a reference to sys_user, and wd_leave_validation rejects
 * annual leave for an applicant with no balance row.
 */
async function listPickableUsers(api) {
  const body = await getJson(api, '/api/admin/users/search?keyword=&size=100');
  return (body?.data ?? [])
    .filter((user) => user?.pid)
    .map((user) => ({
      pid: String(user.pid),
      label: String(user.displayName ?? user.email ?? user.pid),
    }));
}

async function ensureBalances(api, users) {
  const onFile = new Set(
    (await listDynamic(api, 'wd_leave_balance'))
      .map((row) => String(row?.wd_bal_employee ?? ''))
      .filter(Boolean),
  );

  const created = [];
  for (const user of users) {
    if (onFile.has(user.pid)) continue;
    await executeCommand(api, 'wd:create_leave_balance', {
      wd_bal_employee: user.pid,
      wd_bal_year: BALANCE_YEAR,
      wd_bal_annual_remaining: ANNUAL_REMAINING_DAYS,
      wd_bal_sick_used: 0,
    });
    created.push(user.label);
    console.log(`[workflow-demo-seed] created leave balance for ${user.label}`);
  }
  return created;
}

async function seedRequests(api, userPid, needed) {
  const samples = [
    ['annual', 1, 'AM', 'PM', '季度休假安排'],
    ['personal', 1, 'AM', 'AM', '家庭事务处理'],
    ['annual', 2, 'AM', 'PM', '年度休假计划'],
    ['comp', 1, 'PM', 'PM', '调休半天'],
    ['annual', 3, 'AM', 'PM', '长假审批演示'],
    ['personal', 3, 'AM', 'PM', '跨部门审批演示'],
    ['annual', 4, 'AM', 'PM', '春节前休假'],
    ['sick', 2, 'AM', 'PM', '病假不超过两天'],
    ['annual', 2, 'PM', 'PM', '客户项目结束后休假'],
    ['personal', 1, 'AM', 'PM', '个人事务'],
    ['comp', 2, 'AM', 'PM', '加班调休'],
    ['annual', 5, 'AM', 'PM', '长流程 HR 审批样例'],
  ];

  const created = [];
  const base = new Date(Date.UTC(2026, 4, 29));
  for (let i = 0; i < needed; i += 1) {
    const [type, days, startSlot, endSlot, reason] = samples[i % samples.length];
    const startDate = addDays(base, i * 2);
    const endDate = addDays(startDate, Math.max(0, Number(days) - 1));
    const recordId = await executeCommand(api, 'wd:create_and_submit_leave_request', {
      wd_req_applicant: userPid,
      wd_req_type: type,
      wd_req_start_date: formatDate(startDate),
      wd_req_start_slot: startSlot,
      wd_req_end_date: formatDate(endDate),
      wd_req_end_slot: endSlot,
      wd_req_days: days,
      wd_req_reason: `Demo seed: ${reason}`,
    });
    created.push(recordId);
    console.log(`[workflow-demo-seed] created leave request ${recordId} (${type}, ${days}d)`);
  }
  return created;
}

async function createHistory(api) {
  const todo = await getJson(api, '/api/bpm/tasks/todo?size=100');
  const tasks = (todo?.data ?? []).filter((task) =>
    String(task?.processDefinitionIdAndVersion ?? '').startsWith('wd_leave_approval'),
  );

  let completed = 0;
  const actionable = Math.max(0, tasks.length - 3);
  for (let i = 0; i < actionable; i += 1) {
    const task = tasks[i];
    if (i % 3 === 0) {
      await postJson(api, `/api/bpm/tasks/${task.taskId}/approve`, {
        comment: 'Demo seed approval',
      }).catch((error) => {
        console.warn(`[workflow-demo-seed] approve skipped for ${task.taskId}: ${error.message}`);
      });
      completed += 1;
    } else if (i % 5 === 0) {
      await postJson(api, `/api/bpm/tasks/${task.taskId}/reject`, {
        comment: 'Demo seed rejection for history',
      }).catch((error) => {
        console.warn(`[workflow-demo-seed] reject skipped for ${task.taskId}: ${error.message}`);
      });
      completed += 1;
    }
  }
  return completed;
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    printHelp();
    return 0;
  }

  console.log('[workflow-demo-seed] plan');
  console.log(`  baseUrl:       ${options.baseUrl}`);
  console.log(`  storageState:  ${options.storageState}`);
  console.log(`  minRequests:   ${options.minRequests}`);

  if (options.dryRun) {
    console.log('(dry-run mode: not seeding workflow-demo data)');
    return 0;
  }

  const api = await request.newContext({
    baseURL: options.baseUrl,
    storageState: options.storageState,
    extraHTTPHeaders: { 'Content-Type': 'application/json' },
  });

  try {
    const user = await getCurrentUser(api);
    const pickable = await listPickableUsers(api);
    const applicants = pickable.some((candidate) => candidate.pid === user.pid)
      ? pickable
      : [user, ...pickable];
    await ensureBalances(api, applicants);
    const existingRequests = await countDynamic(api, 'wd_leave_request');
    const needed = Math.max(0, options.minRequests - existingRequests);
    console.log(
      `[workflow-demo-seed] current user ${user.label}; existing leave requests ${existingRequests}; creating ${needed}`,
    );
    if (needed > 0) {
      await seedRequests(api, user.pid, needed);
    }
    const completed = await createHistory(api);
    const finalRequests = await countDynamic(api, 'wd_leave_request');
    const finalBalances = await countDynamic(api, 'wd_leave_balance');
    console.log('[workflow-demo-seed] summary');
    console.log(`  leaveBalances: ${finalBalances}`);
    console.log(`  leaveRequests: ${finalRequests}`);
    console.log(`  completedTasksAttempted: ${completed}`);
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
      console.error(`[workflow-demo-seed] ${error instanceof Error ? error.message : String(error)}`);
      process.exitCode = 1;
    },
  );
}
