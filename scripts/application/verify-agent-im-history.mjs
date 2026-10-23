import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
const [origin, phase, fixtureFile, output] = process.argv.slice(2);
assert.ok(origin && fixtureFile && output, 'Usage: <origin> create|base|restored <fixture.json> <fresh-output>');
assert.ok(['create', 'base', 'restored'].includes(phase));
assert.ok(process.env.AURA_TEST_EMAIL && process.env.AURA_TEST_PASSWORD, 'Explicit bootstrap credentials required');
mkdirSync(output, { recursive: false });
async function request(endpoint, token, body) {
  const response = await fetch(new URL(endpoint, origin), { method: body ? 'POST' : 'GET', signal: AbortSignal.timeout(15000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const data = await response.json();
  assert.equal(response.status, 200, endpoint);
  assert.equal(data.code, '0', endpoint);
  return data.data;
}
const login = await request('/api/auth/login', null, { email: process.env.AURA_TEST_EMAIL, password: process.env.AURA_TEST_PASSWORD });
const business = (await request('/api/tenant-selection/my-spaces', login.jwt)).filter(space => space.spaceType === 'business');
assert.equal(business.length, 1);
const session = await request('/api/tenant-selection/process', login.jwt, { action: 'select', tenantId: business[0].tenantId });
assert.equal(session.status, 'success');
const token = session.jwt;
const capabilities = await fetch(new URL('/api/application/capabilities', origin), { signal: AbortSignal.timeout(15000), headers: { Authorization: `Bearer ${token}` } });
assert.equal(capabilities.status, 200);
assert.deepEqual(await capabilities.json(), { schemaVersion: 1, agent: { state: phase === 'base' ? 'not_installed' : 'installed' } });
let fixture;
if (phase === 'create') {
  const agentId = process.env.AURA_TEST_AGENT_ID;
  assert.match(agentId ?? '', /^\d+$/, 'Supply the known owned-tenant agent fixture ID');
  const name = `S3 retained mixed conversation ${randomUUID()}`;
  const conversation = await request('/api/im/conversations', token, { type: 'group', name, memberIds: [], agentIds: [agentId] });
  assert.ok(conversation.conversationId);
  fixture = { conversationId: String(conversation.conversationId), agentId, name, tenantId: business[0].tenantId, messages: [] };
} else {
  fixture = JSON.parse(readFileSync(fixtureFile, 'utf8'));
  assert.equal(fixture.tenantId, business[0].tenantId);
}
const members = await request(`/api/im/conversations/${fixture.conversationId}/members`, token);
const agent = members.find(member => member.memberType === 'agent' && String(member.memberId) === fixture.agentId);
assert.ok(agent, 'Historical agent identity was removed');
assert.ok(members.some(member => member.memberType === 'human'), 'Human membership was removed');
if (phase === 'base') assert.equal(agent.agentAvailable, false);
else assert.ok(agent.agentAvailable == null, 'Full assembly must retain the nullable legacy protocol');
const before = await request(`/api/im/conversations/${fixture.conversationId}/messages`, token);
for (const message of fixture.messages) assert.ok(before.some(row => row.content === message && row.senderType === 'human'), 'A retained human message disappeared');
const content = `S3 human message ${phase} ${randomUUID()}`;
const sent = await request(`/api/im/conversations/${fixture.conversationId}/messages`, token, { messageType: 'TEXT', content, clientMsgId: randomUUID() });
assert.equal(sent.content, content);
assert.equal(sent.senderType, 'human');
const after = await request(`/api/im/conversations/${fixture.conversationId}/messages`, token);
assert.ok(after.some(message => message.content === content && message.senderType === 'human'));
fixture.messages.push(content);
writeFileSync(fixtureFile, JSON.stringify(fixture, null, 2)+'\n');
writeFileSync(path.join(output, 'report.json'), JSON.stringify({ phase, origin, conversationId: fixture.conversationId, agentId: fixture.agentId, agentAvailable: agent.agentAvailable ?? null, memberCount: members.length, preservedHumanMessages: fixture.messages.length, newHumanMessageId: sent.id, verdict: 'pass' }, null, 2)+'\n');
console.log(`IM ${phase}: membership retained, availability correct, ${fixture.messages.length} human messages persisted`);
