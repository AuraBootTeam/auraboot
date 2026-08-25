import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const backend = process.env.BACKEND_URL ?? 'http://127.0.0.1:6455';
const evidenceRoot = process.env.AURA_EVIDENCE_ROOT
  ?? mkdtempSync(join(tmpdir(), 'crm-par09-evidence-'));
const run = `PAR09-${Date.now()}`;
let jwt = '';
const checks = [];
const sourceIds = [
  'api:opportunity:opportunity-rule:page',
  'api:opportunity:opportunity-rule:save',
  'api:opportunity:opportunity-rule:update',
  'api:opportunity:opportunity-rule:delete',
  'api:opportunity:opportunity-rule:switch-status',
  'api:opportunity:opportunity-stage:get-stage-config-list',
  'api:opportunity:opportunity-stage:add',
  'api:opportunity:opportunity-stage:delete',
  'api:opportunity:opportunity-stage:update',
  'api:opportunity:opportunity-stage:update:opportunity-stage-update',
  'api:opportunity:opportunity-stage:sort',
];

async function api(path, init = {}) {
  const headers = new Headers(init.headers ?? {});
  if (jwt) headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${backend}${path}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

function expectOk(result, label) {
  if (!result.response.ok || String(result.body?.code) !== '0') {
    throw new Error(`${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`);
  }
  checks.push({ label, status: 'pass' });
  return result.body?.data;
}

function recordPid(data) {
  const nested = data?.data ?? data;
  return nested?.recordPid ?? nested?.recordId ?? nested?.pid ?? nested?.publicRecordId;
}

async function command(code, payload, targetRecordPid, operationType = targetRecordPid ? 'update' : 'create') {
  return api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload, targetRecordPid, operationType }),
  });
}

const login = await api('/api/auth/login', {
  method: 'POST',
  body: JSON.stringify({ email: 'admin@auraboot.com', password: 'Test2026x' }),
});
jwt = String(expectOk(login, 'admin login')?.jwt ?? '');
if (!jwt) throw new Error('login did not return jwt');

const auditStageCode = `audit_${Date.now()}`;
const auditStagePayload = {
  crm_osc_code: auditStageCode, crm_osc_name: `${run} 审计阶段`, crm_osc_type: 'open',
  crm_osc_probability: 35, crm_osc_sequence: 35, crm_osc_status: 'active',
  crm_osc_allow_rollback: false, crm_osc_description: 'PAR09 CRUD and ordering proof',
};
const auditStagePid = recordPid(expectOk(
  await command('crm:create_opportunity_stage_config', auditStagePayload),
  'create disposable stage configuration',
));
expectOk(await command('crm:update_opportunity_stage_config', {
  ...auditStagePayload,
  crm_osc_name: `${run} 审计阶段已更新`,
  crm_osc_probability: 36,
  crm_osc_sequence: 36,
  crm_osc_allow_rollback: true,
}, auditStagePid), 'update stage name probability rollback and order');
const updatedAuditStage = expectOk(
  await api(`/api/dynamic/crm_opportunity_stage_config/${auditStagePid}`),
  'read updated disposable stage configuration',
);
if (
  updatedAuditStage.crm_osc_name !== `${run} 审计阶段已更新`
  || Number(updatedAuditStage.crm_osc_probability) !== 36
  || Number(updatedAuditStage.crm_osc_sequence) !== 36
  || updatedAuditStage.crm_osc_allow_rollback !== true
) {
  throw new Error(`stage update did not persist exact configuration: ${JSON.stringify(updatedAuditStage)}`);
}
checks.push({ label: 'stage rename rollback and sort fields persist exactly', status: 'pass' });
expectOk(
  await command('crm:delete_opportunity_stage_config', {}, auditStagePid, 'delete'),
  'delete disposable stage configuration',
);
const stageRowsAfterDelete = expectOk(
  await api('/api/dynamic/crm_opportunity_stage_config/list?pageNum=1&pageSize=100'),
  'list stage configuration after delete',
)?.records ?? [];
if (stageRowsAfterDelete.some((row) => row.pid === auditStagePid)) {
  throw new Error('deleted stage configuration remains visible');
}
checks.push({ label: 'stage delete persists physical absence', status: 'pass' });

const stagePayload = {
  crm_osc_code: 'closed_won', crm_osc_name: `${run} 赢单`, crm_osc_type: 'won',
  crm_osc_probability: 93, crm_osc_sequence: 90, crm_osc_status: 'inactive',
  crm_osc_allow_rollback: false, crm_osc_description: 'PAR09 true-stack terminal stage',
};
const stageRows = expectOk(await api('/api/dynamic/crm_opportunity_stage_config/list?pageNum=1&pageSize=100'), 'list stage configuration')?.records ?? [];
const existingStage = stageRows.find((row) => row.crm_osc_code === 'closed_won');
const stagePid = existingStage?.pid ?? recordPid(expectOk(
  await command('crm:create_opportunity_stage_config', stagePayload),
  'create inactive won stage',
));
if (existingStage) {
  expectOk(await command('crm:update_opportunity_stage_config', stagePayload, stagePid), 'reset won stage inactive');
}

const ruleCreate = await command('crm:create_opportunity_close_rule', {
  crm_ocr_code: `${run}-AMOUNT`, crm_ocr_name: `${run} 金额校验`, crm_ocr_close_type: 'won',
  crm_ocr_rule_type: 'positive_amount', crm_ocr_status: 'active', crm_ocr_sequence: 10,
  crm_ocr_error_message: 'PAR09 金额必须大于零',
});
const rulePid = recordPid(expectOk(ruleCreate, 'create active structured close rule'));
const rulePayload = {
  crm_ocr_code: `${run}-AMOUNT`, crm_ocr_name: `${run} 金额校验已更新`,
  crm_ocr_close_type: 'won', crm_ocr_rule_type: 'positive_amount',
  crm_ocr_status: 'inactive', crm_ocr_sequence: 11,
  crm_ocr_error_message: 'PAR09 金额必须大于零',
};
expectOk(
  await command('crm:update_opportunity_close_rule', rulePayload, rulePid),
  'update and deactivate structured close rule',
);
expectOk(
  await command('crm:update_opportunity_close_rule', { ...rulePayload, crm_ocr_status: 'active' }, rulePid),
  'reactivate structured close rule',
);
const ruleRows = expectOk(
  await api('/api/dynamic/crm_opportunity_close_rule/list?pageNum=1&pageSize=100'),
  'list close rules after status switch',
)?.records ?? [];
const persistedRule = ruleRows.find((row) => row.pid === rulePid);
if (
  persistedRule?.crm_ocr_name !== `${run} 金额校验已更新`
  || persistedRule?.crm_ocr_status !== 'active'
  || Number(persistedRule?.crm_ocr_sequence) !== 11
) {
  throw new Error(`close rule update/status did not persist: ${JSON.stringify(persistedRule)}`);
}
checks.push({ label: 'close rule page update and status switch persist exactly', status: 'pass' });

const accountCreate = await command('crm:create_account', {
  crm_acc_name: `${run} 客户`, crm_acc_industry: 'manufacturing', crm_acc_status: 'active',
});
const accountPid = recordPid(expectOk(accountCreate, 'create account fixture'));

const opportunityCreate = await command('crm:create_opportunity', {
  crm_opp_name: `${run} 商机`, crm_opp_account_id: accountPid, crm_opp_currency_code: 'CNY',
  crm_opp_expected_amount: 880000, crm_opp_expected_close_date: '2026-12-31T00:00:00Z',
  crm_opp_probability: 20, crm_opp_forecast_category: 'pipeline',
});
const opportunityPid = recordPid(expectOk(opportunityCreate, 'create discovery opportunity'));

const blocked = await command('crm:win_opportunity', {}, opportunityPid);
if (blocked.response.ok && String(blocked.body?.code) === '0') {
  throw new Error('inactive terminal stage mutation was not falsifiable');
}
if (!JSON.stringify(blocked.body).includes('目标商机阶段已停用')) {
  throw new Error(`unexpected blocked close response: ${JSON.stringify(blocked.body)}`);
}
checks.push({ label: 'inactive configured terminal stage blocks close', status: 'pass' });

expectOk(await command('crm:update_opportunity_stage_config', {
  crm_osc_code: 'closed_won', crm_osc_name: `${run} 赢单`, crm_osc_type: 'won',
  crm_osc_probability: 93, crm_osc_sequence: 90, crm_osc_status: 'active',
  crm_osc_allow_rollback: false, crm_osc_description: 'PAR09 true-stack terminal stage',
}, stagePid), 'activate won stage');

expectOk(await command('crm:win_opportunity', {}, opportunityPid), 'configured close succeeds');
const opportunity = expectOk(await api(`/api/dynamic/crm_opportunity_common/${opportunityPid}`), 'read closed opportunity');
if (opportunity.crm_opp_stage !== 'closed_won' || Number(opportunity.crm_opp_probability) !== 93) {
  throw new Error(`runtime did not consume stage configuration: ${JSON.stringify(opportunity)}`);
}
checks.push({ label: 'runtime persists configured terminal probability 93', status: 'pass' });

expectOk(
  await command('crm:delete_opportunity_close_rule', {}, rulePid, 'delete'),
  'delete structured close rule',
);
const rulesAfterDelete = expectOk(
  await api('/api/dynamic/crm_opportunity_close_rule/list?pageNum=1&pageSize=100'),
  'list close rules after delete',
)?.records ?? [];
if (rulesAfterDelete.some((row) => row.pid === rulePid)) {
  throw new Error('deleted close rule remains visible');
}
checks.push({ label: 'close rule delete persists physical absence', status: 'pass' });

mkdirSync(evidenceRoot, { recursive: true });
const receipt = {
  run, runtime: 'crm-par09-governance-20260825-s55', backend,
  database: process.env.PGDATABASE ?? 'auraboot_55',
  sourceIds,
  records: { stagePid, auditStagePid, rulePid, accountPid, opportunityPid }, checks,
  summary: { passed: checks.length, failed: 0 },
};
const path = join(evidenceRoot, 'crm-par09-opportunity-governance-true-stack.json');
writeFileSync(path, `${JSON.stringify(receipt, null, 2)}\n`);
console.log(JSON.stringify({ path, summary: receipt.summary, records: receipt.records }));
