import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const [origin, output, mode = 'core'] = process.argv.slice(2);
assert.ok(origin && output, 'Usage: <owned-origin> <fresh-output> [core|workflow]');
assert.ok(['core', 'workflow'].includes(mode));
assert.ok(process.env.AURA_TEST_EMAIL && process.env.AURA_TEST_PASSWORD, 'Explicit bootstrap credentials required');
mkdirSync(output, { recursive: false });
let token;
const facts = [];
const marker = `P1_BASE_${randomUUID().replaceAll('-', '').slice(0,12)}`;
async function raw(endpoint, body, method = body ? 'POST' : 'GET') {
  const response = await fetch(new URL(endpoint, origin), {
    method, signal: AbortSignal.timeout(90000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const value = await response.json();
  facts.push({ endpoint, method, httpStatus: response.status, code: value.code });
  return { ...value, httpStatus: response.status };
}
async function ok(endpoint, body, method) {
  const value = await raw(endpoint, body, method);
  assert.equal(value.httpStatus, 200, endpoint);
  assert.equal(value.code, '0', `${endpoint}: ${value.message}`);
  return value.data;
}
try {
  token = (await ok('/api/auth/login', { email: process.env.AURA_TEST_EMAIL, password: process.env.AURA_TEST_PASSWORD })).jwt;
  const spaces = (await ok('/api/tenant-selection/my-spaces')).filter(space => space.spaceType === 'business');
  assert.equal(spaces.length, 1, 'Use one explicitly bootstrapped owned business tenant');
  token = (await ok('/api/tenant-selection/process', { action: 'select', tenantId: spaces[0].tenantId })).jwt;
  const response = await fetch(new URL('/api/application/capabilities', origin), {
    headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { schemaVersion: 1, agent: { state: 'not_installed' } });
  const department = await ok('/api/dynamic/org_department/create', { org_dept_name: marker, org_dept_status: 'active' });
  assert.ok(department.pid);
  const endpoint = `/api/dynamic/org_department/${department.pid}`;
  await ok(endpoint, { org_dept_name: `${marker} updated` }, 'PUT');
  assert.equal((await ok(endpoint)).org_dept_name, `${marker} updated`);
  const conversation = await ok('/api/im/conversations', { type: 'group', name: marker, memberIds: [], agentIds: [] });
  assert.ok(conversation.conversationId);
  const messages = `/api/im/conversations/${conversation.conversationId}/messages`;
  const content = `${marker} ordinary human message`;
  const sent = await ok(messages, { messageType: 'TEXT', content, clientMsgId: randomUUID() });
  assert.equal(sent.senderType, 'human');
  assert.equal((await ok(messages)).filter(row => row.senderType === 'human' && row.content === content).length, 1);
  const workflow = {};
  if (mode === 'workflow') {
    const update = { type: 'update_record', sequence: 0, config: {
      modelCode: 'org_department', recordPid: department.pid, fields: { org_dept_name: `${marker} automation` },
    } };
    const create = (name, actions) => ok('/api/automations', {
      name, modelCode: 'org_department', triggerType: 'on_record_create', enabled: false, actions,
    });
    const ordinary = await create(`${marker} ordinary`, [update]);
    const execution = await ok(`/api/automations/${ordinary.pid}/trigger`, { recordPid: department.pid });
    assert.equal(execution.status, 'success', 'The installed workflow product must execute the ordinary action');
    const saved = (await ok(endpoint)).org_dept_name;
    assert.equal(saved, `${marker} automation`);
    const mixed = await create(`${marker} mixed capability guard`, [
      { ...update, config: { ...update.config, fields: { org_dept_name: `${marker} MUST NOT WRITE` } } },
      { type: 'llm_call', sequence: 1, config: { prompt: 'No model invocation in the base combination.' } },
    ]);
    const denied = await raw(`/api/automations/${mixed.pid}/trigger`, { recordPid: department.pid });
    assert.equal(denied.code, '3');
    assert.match(`${denied.message} ${denied.context}`, /LLM workflow capability is not installed/);
    assert.equal((await ok(endpoint)).org_dept_name, saved, 'Mixed flow must be rejected before its first ordinary write');
    Object.assign(workflow, { ordinaryPid: ordinary.pid, mixedPid: mixed.pid, execution, denied, mixedFirstWritePrevented: true });
  }
  writeFileSync(path.join(output, 'report.json'), JSON.stringify({ verdict: 'pass', origin, mode, marker,
    tenantId: spaces[0].tenantId, departmentPid: department.pid, conversationId: conversation.conversationId,
    newHumanMessageId: sent.id, modelCalls: 0, fixtureRetained: true, workflow, facts }, null, 2)+'\n');
  console.log(`Base ${mode}: business CRUD and human IM persisted${mode === 'workflow' ? '; ordinary workflow succeeded and mixed LLM flow refused before writing' : ''}`);
} catch (error) {
  writeFileSync(path.join(output, 'failure.json'), JSON.stringify({ marker, origin, mode, facts, error: String(error) }, null, 2)+'\n');
  throw error;
}
