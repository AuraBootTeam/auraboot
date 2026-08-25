#!/usr/bin/env node

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const backend = String(process.env.BACKEND_URL ?? 'http://127.0.0.1:6455').replace(/\/$/, '');
const adminEmail = process.env.CRM_ADMIN_EMAIL ?? 'admin@auraboot.com';
const adminPassword = required(process.env.CRM_ADMIN_PASSWORD, 'CRM_ADMIN_PASSWORD');
const testPassword = required(process.env.CRM_TEST_USER_PASSWORD, 'CRM_TEST_USER_PASSWORD');
const evidencePath = resolve(required(process.env.CRM_PERMISSION_EVIDENCE_PATH, 'CRM_PERMISSION_EVIDENCE_PATH'));
const run = `PAR09-PERM-${Date.now()}`;
const checks = [];

const adminJwt = await login(adminEmail, adminPassword);
await expectAllowed(
  request('/api/dynamic/crm_opportunity_stage_config/list?pageNum=1&pageSize=20', adminJwt),
  'administrator reads stage governance',
);

const personas = [
  { roleCode: 'crm_sales_manager', label: 'sales manager' },
  { roleCode: 'crm_sales', label: 'sales representative' },
  { roleCode: 'crm_viewer', label: 'read-only viewer' },
];
const users = [];
for (const persona of personas) {
  const email = `${persona.roleCode.replaceAll('_', '.')}.${Date.now()}@example.test`;
  const provisioned = await expectAllowed(
    request('/api/admin/users', adminJwt, {
      method: 'POST',
      body: JSON.stringify({
        email,
        displayName: `${run} ${persona.label}`,
        initialPassword: testPassword,
        roleCodes: [persona.roleCode],
        sendInviteEmail: false,
      }),
    }),
    `provision ${persona.label}`,
    false,
  );
  const jwt = await login(email, testPassword);
  await expectDenied(
    request('/api/dynamic/crm_opportunity_stage_config/list?pageNum=1&pageSize=20', jwt),
    `${persona.label} cannot read stage governance`,
  );
  await expectDenied(
    request('/api/meta/commands/execute/crm:create_opportunity_stage_config', jwt, {
      method: 'POST',
      body: JSON.stringify({
        operationType: 'create',
        payload: {
          crm_osc_code: `forged_${Date.now()}`,
          crm_osc_name: `${run} forbidden`,
          crm_osc_type: 'open',
          crm_osc_probability: 10,
          crm_osc_sequence: 10,
          crm_osc_status: 'active',
          crm_osc_allow_rollback: false,
        },
      }),
    }),
    `${persona.label} cannot mutate stage governance`,
  );
  users.push({ roleCode: persona.roleCode, email, userPid: provisioned.body?.data?.pid ?? provisioned.body?.data?.userPid });
}

const receipt = {
  schemaVersion: 1,
  run,
  runtime: 'crm-par09-governance-20260825-s55',
  backend,
  database: process.env.PGDATABASE ?? 'auraboot_55',
  permission: 'crm.opportunity.configure',
  users,
  checks,
  summary: { passed: checks.length, failed: 0 },
  verdict: 'pass',
  dataMigration: 'not required; development stage',
};
mkdirSync(dirname(evidencePath), { recursive: true });
writeFileSync(evidencePath, `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o600 });
console.log(JSON.stringify({ evidencePath, summary: receipt.summary, verdict: receipt.verdict }));

async function login(email, password) {
  const result = await request('/api/auth/login', '', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  if (!result.response.ok || String(result.body?.code) !== '0' || !result.body?.data?.jwt) {
    throw new Error(`login failed for ${email}: HTTP ${result.response.status}`);
  }
  return String(result.body.data.jwt);
}

async function request(path, jwt, init = {}) {
  const headers = new Headers(init.headers ?? {});
  if (jwt) headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${backend}${path}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

async function expectAllowed(promise, label, record = true) {
  const result = await promise;
  if (!result.response.ok || String(result.body?.code) !== '0') {
    throw new Error(`${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`);
  }
  if (record) checks.push({ label, status: 'pass' });
  return result;
}

async function expectDenied(promise, label) {
  const result = await promise;
  const denied = result.response.status === 403 || String(result.body?.code) === '403';
  if (!denied) {
    throw new Error(`${label}: expected 403, received HTTP ${result.response.status} ${JSON.stringify(result.body)}`);
  }
  checks.push({ label, status: 'pass', httpStatus: result.response.status });
}

function required(value, label) {
  if (value == null || String(value).trim() === '') throw new Error(`${label} is required`);
  return String(value).trim();
}
