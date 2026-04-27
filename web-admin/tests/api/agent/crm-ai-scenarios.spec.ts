import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { expect, test, type TestInfo } from '@playwright/test';

const BACKEND_URL = 'http://127.0.0.1:6443';
const TEST_USER = { email: 'admin@example.com', password: 'Test2026x' };
const LLM_TIMEOUT = 90_000;
const LLM_TRANSPORT_ATTEMPTS = 3;
const EVIDENCE_DIR = 'test-results/agent-evidence';

type ToolCall = { toolId?: string; toolName: string; input: any; result: any };
type Confirmation = { toolId: string; toolName: string; input: any; description?: string };
type ChatResult = {
  content: string;
  toolCalls: ToolCall[];
  confirmations: Confirmation[];
  error?: string;
};
type Filter = { fieldName: string; operator: string; value?: unknown; values?: unknown[] };
type CoverageLevel = 'L1' | 'L2' | 'L3' | 'L4';

function uniqueId(prefix = 'ai'): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
}

async function getToken(): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(TEST_USER),
  });
  const body = await resp.json();
  expect(body.code).toBe('0');
  return body.data.jwt;
}

function authHeaders(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}

function stripThinking(content: string): string {
  return content.replace(/<think>[\s\S]*?<\/think>\s*/g, '').trim();
}

function isTransientLlmTransportError(error?: string): boolean {
  return Boolean(
    error?.match(
      /handshake timed out|operation timed out|failed to resolve|fetch_error:.*(fetch failed|timed out|timeout|ECONNRESET|ECONNREFUSED|EAI_AGAIN|ENOTFOUND|ETIMEDOUT|UND_ERR|socket|network)/i,
    ),
  );
}

function describeFetchError(error: any): string {
  const message = error?.message || String(error);
  const cause = error?.cause;
  if (!cause) return message;

  const causeDetails = [cause.code, cause.name, cause.message].filter(Boolean).join(': ');
  return causeDetails ? `${message} (${causeDetails})` : message;
}

function contentHash(content: string): string {
  return createHash('sha256')
    .update(content || '')
    .digest('hex');
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function toolNames(result: ChatResult): string {
  return result.toolCalls.map((t) => t.toolName).join(',');
}

function serializedToolInputs(result: ChatResult): string {
  return result.toolCalls.map((t) => JSON.stringify(t.input ?? {})).join('\n');
}

function expectNoSqlFallback(result: ChatResult): void {
  expect(toolNames(result)).not.toContain('platform_execute_sql');
}

function expectToolInputContains(result: ChatResult, expected: string): void {
  expect(serializedToolInputs(result), `tool input should contain ${expected}`).toContain(expected);
}

async function writeEvidence(
  testInfo: TestInfo,
  scenarioId: string,
  coverageLevel: CoverageLevel,
  result: ChatResult,
  oracle: Record<string, unknown> = {},
): Promise<void> {
  const evidence = {
    scenarioId,
    coverageLevel,
    error: result.error ?? null,
    confirmations: result.confirmations,
    toolCalls: result.toolCalls.map((tool) => ({
      toolName: tool.toolName,
      input: tool.input,
      success: tool.result?.success ?? tool.result?.data?.success ?? null,
      total: tool.result?.data?.total ?? tool.result?.total ?? null,
    })),
    contentHash: contentHash(result.content),
    contentPreview: result.content.slice(0, 500),
    oracle,
  };
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  fs.writeFileSync(
    `${EVIDENCE_DIR}/agent-evidence-${scenarioId}.json`,
    JSON.stringify(evidence, null, 2),
  );
  const filePath = testInfo.outputPath(`agent-evidence-${scenarioId}.json`);
  fs.writeFileSync(filePath, JSON.stringify(evidence, null, 2));
  await testInfo.attach(`agent-evidence-${scenarioId}`, {
    path: filePath,
    contentType: 'application/json',
  });
}

function parseSse(text: string): ChatResult {
  const toolCalls: ToolCall[] = [];
  const confirmations: Confirmation[] = [];
  const pendingTools = new Map<string, { toolId?: string; toolName: string; input: any }>();
  let content = '';
  let error: string | undefined;
  let currentEvent = 'message';

  for (const rawLine of text.split('\n')) {
    const line = rawLine.trimEnd();
    if (line.startsWith('event:')) {
      currentEvent = line.slice('event:'.length).trim();
      continue;
    }
    if (!line.startsWith('data:')) continue;

    const rawData = line.slice('data:'.length).trim();
    let data: any;
    try {
      data = JSON.parse(rawData);
    } catch {
      if (currentEvent === 'error') error = rawData;
      continue;
    }

    if (currentEvent === 'tool_start' && data.toolName) {
      const pending = { toolId: data.toolId, toolName: data.toolName, input: data.input };
      pendingTools.set(data.toolId || data.toolName, pending);
      continue;
    }
    if (currentEvent === 'tool_result') {
      const key = data.toolId || data.toolName;
      const pending = pendingTools.get(key);
      if (pending) {
        toolCalls.push({ ...pending, result: data.result });
        pendingTools.delete(key);
      }
      continue;
    }
    if (currentEvent === 'confirm_required') {
      confirmations.push({
        toolId: data.toolId,
        toolName: data.toolName,
        input: data.input,
        description: data.description,
      });
      continue;
    }
    if (currentEvent === 'done' && data.content !== undefined) {
      content = data.content;
      continue;
    }
    if (currentEvent === 'error' || data.error) {
      error = data.error || rawData;
      continue;
    }
    if (data.content) {
      content = data.content;
    }
  }

  return { content: stripThinking(content), toolCalls, confirmations, error };
}

async function chatWithAuraBot(
  token: string,
  message: string,
  modelCode?: string,
): Promise<ChatResult> {
  let lastResult: ChatResult | undefined;
  for (let attempt = 0; attempt < LLM_TRANSPORT_ATTEMPTS; attempt += 1) {
    lastResult = await chatWithAuraBotOnce(token, message, modelCode);
    if (!isTransientLlmTransportError(lastResult.error)) {
      return lastResult;
    }
  }
  return lastResult!;
}

async function chatWithAuraBotOnce(
  token: string,
  message: string,
  modelCode?: string,
): Promise<ChatResult> {
  try {
    const sessionId = uniqueId('chat');
    const resp = await fetch(`${BACKEND_URL}/api/ai/aurabot/chat/stream`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify({
        sessionId,
        message,
        conversationId: null,
        pageContext: {
          kind: modelCode ? 'list' : undefined,
          path: modelCode ? `/p/${modelCode}` : '/',
          pageKey: modelCode || undefined,
          modelCode: modelCode || null,
        },
      }),
      signal: AbortSignal.timeout(LLM_TIMEOUT),
    });
    const text = await resp.text();
    return parseSse(text);
  } catch (e: any) {
    return {
      content: '',
      toolCalls: [],
      confirmations: [],
      error: `fetch_error: ${describeFetchError(e)}`,
    };
  }
}

async function executeCommand(
  token: string,
  commandCode: string,
  payload: Record<string, unknown>,
  targetRecordId?: string,
): Promise<{ success: boolean; recordId?: string; data?: any; error?: string }> {
  const requestBody: Record<string, unknown> = { payload };
  if (targetRecordId) requestBody.targetRecordId = targetRecordId;

  const resp = await fetch(`${BACKEND_URL}/api/meta/commands/execute/${commandCode}`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify(requestBody),
  });
  const body = await resp.json();
  if (body.code !== '0') {
    return { success: false, error: body.message || JSON.stringify(body.context || body) };
  }
  const innerData = body.data?.data || body.data;
  return {
    success: true,
    recordId: innerData?.recordId || innerData?.pid || body.data?.recordId,
    data: body.data,
  };
}

async function detail(
  token: string,
  pageKey: string,
  recordId: string,
): Promise<Record<string, any>> {
  const resp = await fetch(`${BACKEND_URL}/api/dynamic/${pageKey}/${recordId}`, {
    headers: authHeaders(token),
  });
  const body = await resp.json();
  expect(body.code).toBe('0');
  return body.data;
}

async function queryList(
  token: string,
  pageKey: string,
  filters: Filter[] = [],
  pageSize = 50,
): Promise<{ total: number; records: Record<string, any>[] }> {
  const params = new URLSearchParams({ pageNum: '1', pageSize: String(pageSize) });
  if (filters.length > 0) params.set('filters', JSON.stringify(filters));
  const resp = await fetch(`${BACKEND_URL}/api/dynamic/${pageKey}/list?${params}`, {
    headers: authHeaders(token),
  });
  const body = await resp.json();
  expect(body.code).toBe('0');
  return { total: body.data?.total || 0, records: body.data?.records || [] };
}

async function createLead(
  token: string,
  tag: string,
  overrides: Record<string, unknown> = {},
): Promise<string> {
  const result = await executeCommand(token, 'crm:create_lead', {
    crm_lead_company: `AI Lead ${tag}`,
    crm_lead_contact_name: `Contact ${tag}`,
    crm_lead_contact_phone: '13800138000',
    crm_lead_contact_email: `${tag.toLowerCase()}@agent.test`,
    crm_lead_source: 'website',
    crm_lead_industry: 'technology',
    crm_lead_score: 40,
    crm_lead_requirement: `AI validation seed ${tag}`,
    ...overrides,
  });
  expect(result.success, result.error).toBe(true);
  expect(result.recordId).toBeTruthy();
  return result.recordId!;
}

async function createAccount(token: string, tag: string): Promise<string> {
  const result = await executeCommand(token, 'crm:create_account', {
    crm_acc_name: `AI Account ${tag}`,
    crm_acc_industry: 'technology',
    crm_acc_phone: '0755-88888888',
  });
  expect(result.success, result.error).toBe(true);
  expect(result.recordId).toBeTruthy();
  return result.recordId!;
}

async function createContact(
  token: string,
  accountPid: string,
  tag: string,
  overrides: Record<string, unknown> = {},
): Promise<string> {
  const result = await executeCommand(token, 'crm:create_contact', {
    crm_ct_account_id: accountPid,
    crm_ct_name: `AI Contact ${tag}`,
    crm_ct_title: 'Agent Validation',
    crm_ct_email: `${tag.toLowerCase()}@agent-contact.test`,
    crm_ct_phone: '0755-66668888',
    crm_ct_is_primary: false,
    ...overrides,
  });
  expect(result.success, result.error).toBe(true);
  expect(result.recordId).toBeTruthy();
  return result.recordId!;
}

async function createComplaint(
  token: string,
  accountPid: string,
  tag: string,
  overrides: Record<string, unknown> = {},
): Promise<{ pid: string; code: string; description: string; severity: string; status: string }> {
  const description = String(overrides.crm_cmp_description || `AI complaint ${tag}`);
  const severity = String(overrides.crm_cmp_severity || 'high');
  const result = await executeCommand(token, 'crm:create_complaint', {
    crm_cmp_account_id: accountPid,
    crm_cmp_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    crm_cmp_type: 'product_quality',
    crm_cmp_severity: severity,
    crm_cmp_description: description,
    ...overrides,
  });
  expect(result.success, result.error).toBe(true);
  expect(result.recordId).toBeTruthy();
  const record = await detail(token, 'crm_complaint_list', result.recordId!);
  return {
    pid: result.recordId!,
    code: String(record.crm_cmp_code),
    description,
    severity,
    status: String(record.crm_cmp_status),
  };
}

function expectL4SoftTelemetry(result: ChatResult, scenarioId: string): void {
  test.info().annotations.push({
    type: 'soft-l4',
    description: `${scenarioId}: real LLM observation only; not counted as hard Agent coverage`,
  });
  expect(
    Boolean(result.error) ||
      Boolean(result.content) ||
      result.toolCalls.length > 0 ||
      result.confirmations.length > 0,
  ).toBe(true);
}

test.describe('CRM AI Scenarios', () => {
  let token: string;
  let highScoreLead: { pid: string; company: string; contact: string };
  let sourceOracle: {
    tag: string;
    websiteContact: string;
    referralNewContact: string;
    referralConvertedContact: string;
  };
  let complaintOracle: {
    tag: string;
    open: { pid: string; code: string; description: string; severity: string; status: string };
    investigating: {
      pid: string;
      code: string;
      description: string;
      severity: string;
      status: string;
    };
    resolved: { pid: string; code: string; description: string; severity: string; status: string };
  };
  let crossOracle: {
    tag: string;
    accountName: string;
    contactA: string;
    contactB: string;
  };
  let industryOracle: {
    tag: string;
    technologyContactA: string;
    technologyContactB: string;
    manufacturingContact: string;
  };
  let weeklyOracle: {
    tag: string;
    contactA: string;
    contactB: string;
  };

  test.beforeAll(async () => {
    token = await getToken();

    const tag = uniqueId('high_score');
    const company = `AI High Score ${tag}`;
    const contact = `Oracle Contact ${tag}`;
    const pid = await createLead(token, tag, {
      crm_lead_company: company,
      crm_lead_contact_name: contact,
      crm_lead_score: 99,
      crm_lead_source: 'referral',
      crm_lead_industry: 'technology',
    });
    const seeded = await detail(token, 'crm_lead_list', pid);
    expect(seeded.crm_lead_score).toBe(99);
    highScoreLead = { pid, company, contact };

    const sourceTag = uniqueId('source');
    sourceOracle = {
      tag: sourceTag,
      websiteContact: `Website Contact ${sourceTag}`,
      referralNewContact: `Referral New Contact ${sourceTag}`,
      referralConvertedContact: `Referral Converted Contact ${sourceTag}`,
    };
    await createLead(token, `${sourceTag}_website`, {
      crm_lead_company: `AI Source Website ${sourceTag}`,
      crm_lead_contact_name: sourceOracle.websiteContact,
      crm_lead_source: 'website',
      crm_lead_score: 61,
    });
    await createLead(token, `${sourceTag}_referral_new`, {
      crm_lead_company: `AI Source Referral New ${sourceTag}`,
      crm_lead_contact_name: sourceOracle.referralNewContact,
      crm_lead_source: 'referral',
      crm_lead_score: 67,
    });
    const convertedReferralPid = await createLead(token, `${sourceTag}_referral_converted`, {
      crm_lead_company: `AI Source Referral Converted ${sourceTag}`,
      crm_lead_contact_name: sourceOracle.referralConvertedContact,
      crm_lead_source: 'referral',
      crm_lead_score: 90,
    });
    const contactReferral = await executeCommand(
      token,
      'crm:contact_lead',
      {},
      convertedReferralPid,
    );
    expect(contactReferral.success, contactReferral.error).toBe(true);
    const qualifyReferral = await executeCommand(
      token,
      'crm:qualify_lead',
      {},
      convertedReferralPid,
    );
    expect(qualifyReferral.success, qualifyReferral.error).toBe(true);
    const convertReferral = await executeCommand(
      token,
      'crm:convert_lead',
      {},
      convertedReferralPid,
    );
    expect(convertReferral.success, convertReferral.error).toBe(true);

    const complaintTag = uniqueId('cmp_oracle');
    const complaintAccountPid = await createAccount(token, complaintTag);
    complaintOracle = {
      tag: complaintTag,
      open: await createComplaint(token, complaintAccountPid, `${complaintTag}_open`, {
        crm_cmp_description: `AI unresolved open complaint ${complaintTag}`,
        crm_cmp_severity: 'high',
      }),
      investigating: await createComplaint(
        token,
        complaintAccountPid,
        `${complaintTag}_investigating`,
        {
          crm_cmp_description: `AI unresolved investigating complaint ${complaintTag}`,
          crm_cmp_severity: 'medium',
        },
      ),
      resolved: await createComplaint(token, complaintAccountPid, `${complaintTag}_resolved`, {
        crm_cmp_description: `AI resolved complaint ${complaintTag}`,
        crm_cmp_severity: 'low',
      }),
    };
    const investigateSeed = await executeCommand(
      token,
      'crm:investigate_complaint',
      {},
      complaintOracle.investigating.pid,
    );
    expect(investigateSeed.success, investigateSeed.error).toBe(true);
    complaintOracle.investigating = {
      ...complaintOracle.investigating,
      status: (await detail(token, 'crm_complaint_list', complaintOracle.investigating.pid))
        .crm_cmp_status,
    };
    const investigateResolvedSeed = await executeCommand(
      token,
      'crm:investigate_complaint',
      {},
      complaintOracle.resolved.pid,
    );
    expect(investigateResolvedSeed.success, investigateResolvedSeed.error).toBe(true);
    const resolveSeed = await executeCommand(
      token,
      'crm:resolve_complaint',
      {
        crm_cmp_root_cause: `Resolved root ${complaintTag}`,
        crm_cmp_corrective_action: `Resolved action ${complaintTag}`,
        crm_cmp_resolution_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      },
      complaintOracle.resolved.pid,
    );
    expect(resolveSeed.success, resolveSeed.error).toBe(true);
    complaintOracle.resolved = {
      ...complaintOracle.resolved,
      status: (await detail(token, 'crm_complaint_list', complaintOracle.resolved.pid))
        .crm_cmp_status,
    };

    const crossTag = uniqueId('cross');
    const crossAccountPid = await createAccount(token, crossTag);
    const crossAccount = await detail(token, 'crm_account_list', crossAccountPid);
    crossOracle = {
      tag: crossTag,
      accountName: String(crossAccount.crm_acc_name),
      contactA: `AI Cross Contact A ${crossTag}`,
      contactB: `AI Cross Contact B ${crossTag}`,
    };
    await createContact(token, crossAccountPid, `${crossTag}_a`, {
      crm_ct_name: crossOracle.contactA,
      crm_ct_is_primary: true,
    });
    await createContact(token, crossAccountPid, `${crossTag}_b`, {
      crm_ct_name: crossOracle.contactB,
    });

    const industryTag = uniqueId('industry');
    industryOracle = {
      tag: industryTag,
      technologyContactA: `Technology Contact A ${industryTag}`,
      technologyContactB: `Technology Contact B ${industryTag}`,
      manufacturingContact: `Manufacturing Contact ${industryTag}`,
    };
    await createLead(token, `${industryTag}_tech_a`, {
      crm_lead_company: `AI Industry Technology A ${industryTag}`,
      crm_lead_contact_name: industryOracle.technologyContactA,
      crm_lead_industry: 'technology',
      crm_lead_score: 80,
    });
    await createLead(token, `${industryTag}_tech_b`, {
      crm_lead_company: `AI Industry Technology B ${industryTag}`,
      crm_lead_contact_name: industryOracle.technologyContactB,
      crm_lead_industry: 'technology',
      crm_lead_score: 90,
    });
    await createLead(token, `${industryTag}_manufacturing`, {
      crm_lead_company: `AI Industry Manufacturing ${industryTag}`,
      crm_lead_contact_name: industryOracle.manufacturingContact,
      crm_lead_industry: 'manufacturing',
      crm_lead_score: 70,
    });

    const weeklyTag = uniqueId('weekly');
    weeklyOracle = {
      tag: weeklyTag,
      contactA: `Weekly Contact A ${weeklyTag}`,
      contactB: `Weekly Contact B ${weeklyTag}`,
    };
    await createLead(token, `${weeklyTag}_a`, {
      crm_lead_company: `AI Weekly A ${weeklyTag}`,
      crm_lead_contact_name: weeklyOracle.contactA,
      crm_lead_score: 52,
    });
    await createLead(token, `${weeklyTag}_b`, {
      crm_lead_company: `AI Weekly B ${weeklyTag}`,
      crm_lead_contact_name: weeklyOracle.contactB,
      crm_lead_score: 54,
    });
  });

  test.setTimeout(LLM_TIMEOUT * 3);

  test.describe('Scenario 1: Sales Morning Briefing', () => {
    test('S1-01: Query lead status distribution', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        '帮我看看目前有多少条线索，各状态分布如何？',
        'crm_lead',
      );
      await writeEvidence(testInfo, 'S1-01', 'L2', result);

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(result.toolCalls.length).toBeGreaterThan(0);
      expect(result.toolCalls.map((t) => t.toolName).join(',')).toMatch(
        /nq_crm_lead_pipeline_stats|list_crm_lead|cmd_crm_list_leads/,
      );
      expectNoSqlFallback(result);
      expect(result.content).toMatch(/new|contacted|qualified|converted|lost|线索|状态|\d+/i);
    });

    test('S1-02: Query lead sources and conversion rate', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        `不要使用 SQL 或 platform_execute_sql。请只调用 CRM 线索列表工具（cmd_crm_list_leads/list_crm_lead），用 keyword 或 filters 查询公司名包含「${sourceOracle.tag}」的线索。请按来源统计数量和转化率，并列出每条线索的公司名、联系人、来源、状态。`,
        'crm_lead',
      );
      await writeEvidence(testInfo, 'S1-02', 'L3', result, {
        tag: sourceOracle.tag,
        websiteCount: 1,
        referralCount: 2,
        referralConvertedCount: 1,
      });

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(toolNames(result)).toMatch(/list_crm_lead|cmd_crm_list_leads/);
      expectNoSqlFallback(result);
      expectToolInputContains(result, sourceOracle.tag);
      expect(result.content).toContain(sourceOracle.websiteContact);
      expect(result.content).toContain(sourceOracle.referralNewContact);
      expect(result.content).toContain(sourceOracle.referralConvertedContact);
      expect(result.content).toMatch(/website[\s\S]*1|1[\s\S]*website/i);
      expect(result.content).toMatch(/referral[\s\S]*2|2[\s\S]*referral/i);
      expect(result.content).toMatch(/converted|已转化|转化/i);
    });

    test('S1-03: Query high-score leads with seeded oracle', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        `不要使用 SQL 或 platform_execute_sql。请只调用 CRM 线索列表工具（cmd_crm_list_leads/list_crm_lead），用 keyword 或 filters 查询公司名包含「${highScoreLead.company}」且评分大于 80 的线索，返回公司名、联系人、评分。`,
        'crm_lead',
      );
      await writeEvidence(testInfo, 'S1-03', 'L3', result, highScoreLead);

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(result.toolCalls.length).toBeGreaterThan(0);
      const toolNames = result.toolCalls.map((t) => t.toolName).join(',');
      expect(toolNames).toMatch(/list_crm_lead|cmd_crm_list_leads/);
      expect(toolNames).not.toContain('platform_execute_sql');
      expectToolInputContains(result, highScoreLead.company);
      expect(result.content).toContain(highScoreLead.company);
      expect(result.content).toContain(highScoreLead.contact);
      expect(result.content).toMatch(/99|评分|score|高质量|高分/i);
    });
  });

  test.describe.serial('Scenario 2: Lead Lifecycle', () => {
    let leadPid = '';
    const tag = uniqueId('lifecycle');

    test('S2-01: Create lead via command', async () => {
      leadPid = await createLead(token, tag, {
        crm_lead_company: `AI Lifecycle ${tag}`,
        crm_lead_contact_name: `Lead Contact ${tag}`,
        crm_lead_source: 'referral',
      });
      const created = await detail(token, 'crm_lead_list', leadPid);
      expect(created.crm_lead_company).toBe(`AI Lifecycle ${tag}`);
      expect(created.crm_lead_status).toBe('new');
    });

    test('S2-02: Update lead score', async () => {
      expect(leadPid).toBeTruthy();
      const result = await executeCommand(
        token,
        'crm:update_lead',
        { crm_lead_score: 85 },
        leadPid,
      );
      expect(result.success, result.error).toBe(true);
      expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_score).toBe(85);
    });

    test('S2-03: Transition lead new to contacted', async () => {
      expect(leadPid).toBeTruthy();
      const result = await executeCommand(token, 'crm:contact_lead', {}, leadPid);
      expect(result.success, result.error).toBe(true);
      expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_status).toBe('contacted');
    });

    test('S2-04: Transition lead contacted to qualified', async () => {
      expect(leadPid).toBeTruthy();
      const result = await executeCommand(token, 'crm:qualify_lead', {}, leadPid);
      expect(result.success, result.error).toBe(true);
      expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_status).toBe('qualified');
    });

    test('S2-05: Convert lead to customer workflow state', async () => {
      expect(leadPid).toBeTruthy();
      const result = await executeCommand(token, 'crm:convert_lead', {}, leadPid);
      expect(result.success, result.error).toBe(true);
      expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_status).toBe('converted');
    });
  });

  test.describe.serial('Scenario 3: Complaint Handling', () => {
    let accountPid = '';
    let complaintPid = '';
    const tag = uniqueId('complaint');

    test('S3-01: Query unresolved complaints', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        `不要使用 SQL 或 platform_execute_sql。请只调用 CRM 投诉列表工具（cmd_crm_list_complaints/list_crm_complaint），用 keyword 或 filters 查询描述包含「${complaintOracle.tag}」的投诉。统计未解决投诉（open 或 investigating），返回投诉编码、状态、描述、严重级别，不要返回 resolved 投诉。`,
        'crm_complaint',
      );
      await writeEvidence(testInfo, 'S3-01', 'L3', result, {
        tag: complaintOracle.tag,
        expectedUnresolved: 2,
        openCode: complaintOracle.open.code,
        investigatingCode: complaintOracle.investigating.code,
        resolvedCode: complaintOracle.resolved.code,
      });

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(toolNames(result)).toMatch(/list_crm_complaint|cmd_crm_list_complaints/);
      expectNoSqlFallback(result);
      expectToolInputContains(result, complaintOracle.tag);
      expect(result.content).toContain(complaintOracle.open.code);
      expect(result.content).toContain(complaintOracle.investigating.code);
      expect(result.content).toMatch(/open|investigating|未解决|处理中|调查/i);
      expect(result.content).toMatch(/2|两/);
    });

    test('S3-02: Query complaint detail by code', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        `不要使用 SQL 或 platform_execute_sql。请只调用 CRM 投诉列表/详情工具，查询投诉编码「${complaintOracle.open.code}」的详细信息，返回投诉编码、状态、描述、严重级别。`,
        'crm_complaint',
      );
      await writeEvidence(testInfo, 'S3-02', 'L3', result, complaintOracle.open);

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(toolNames(result)).toMatch(
        /list_crm_complaint|cmd_crm_list_complaints|get_crm_complaint|detail_crm_complaint/,
      );
      expectNoSqlFallback(result);
      expectToolInputContains(result, complaintOracle.open.code);
      expect(result.content).toContain(complaintOracle.open.code);
      expect(result.content).toContain(complaintOracle.open.description);
      expect(result.content).toContain(complaintOracle.open.severity);
      expect(result.content).toContain(complaintOracle.open.status);
    });

    test('S3-03: Create complaint and investigate', async () => {
      accountPid = await createAccount(token, tag);
      const create = await executeCommand(token, 'crm:create_complaint', {
        crm_cmp_account_id: accountPid,
        crm_cmp_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
        crm_cmp_type: 'product_quality',
        crm_cmp_severity: 'high',
        crm_cmp_description: `AI complaint ${tag}`,
      });
      expect(create.success, create.error).toBe(true);
      expect(create.recordId).toBeTruthy();
      complaintPid = create.recordId!;
      expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe('open');

      const investigate = await executeCommand(
        token,
        'crm:investigate_complaint',
        {},
        complaintPid,
      );
      expect(investigate.success, investigate.error).toBe(true);
      expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe(
        'investigating',
      );
    });

    test('S3-04: Record root cause for complaint', async () => {
      expect(complaintPid).toBeTruthy();
      const result = await executeCommand(
        token,
        'crm:update_complaint',
        {
          crm_cmp_root_cause: `Root cause ${tag}`,
          crm_cmp_corrective_action: `Corrective action ${tag}`,
        },
        complaintPid,
      );
      expect(result.success, result.error).toBe(true);
      const updated = await detail(token, 'crm_complaint_list', complaintPid);
      expect(updated.crm_cmp_root_cause).toBe(`Root cause ${tag}`);
      expect(updated.crm_cmp_corrective_action).toBe(`Corrective action ${tag}`);
    });

    test('S3-05: Resolve complaint', async () => {
      expect(complaintPid).toBeTruthy();
      const result = await executeCommand(
        token,
        'crm:resolve_complaint',
        {
          crm_cmp_root_cause: `Root cause ${tag}`,
          crm_cmp_corrective_action: `Corrective action ${tag}`,
          crm_cmp_resolution_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
        },
        complaintPid,
      );
      expect(result.success, result.error).toBe(true);
      expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe(
        'resolved',
      );
    });

    test('S3-06: Close complaint', async () => {
      expect(complaintPid).toBeTruthy();
      const result = await executeCommand(token, 'crm:close_complaint', {}, complaintPid);
      expect(result.success, result.error).toBe(true);
      expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe(
        'closed',
      );

      const related = await queryList(token, 'crm_complaint_list', [
        { fieldName: 'crm_cmp_account_id', operator: 'EQ', value: accountPid },
      ]);
      expect(related.records.map((r) => r.pid)).toContain(complaintPid);
    });
  });

  test.describe('Scenario 4: Cross-Model Queries', () => {
    test('S4-01: Top accounts by contact count', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        `不要使用 SQL 或 platform_execute_sql。请只调用 CRM 联系人列表工具（cmd_crm_list_contacts/list_crm_contact），用 keyword 或 filters 查询联系人姓名包含「${crossOracle.tag}」的联系人。根据返回结果按所属客户统计联系人数量，返回客户名称或客户ID、联系人数量，并列出联系人姓名。`,
        'crm_contact',
      );
      await writeEvidence(testInfo, 'S4-01', 'L3', result, crossOracle);

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(toolNames(result)).toMatch(/list_crm_contact|cmd_crm_list_contacts/);
      expectNoSqlFallback(result);
      expectToolInputContains(result, crossOracle.tag);
      expect(result.content).toMatch(
        new RegExp(`AI Cross Contact A[\\s\\S]*${escapeRegExp(crossOracle.tag)}`),
      );
      expect(result.content).toMatch(
        new RegExp(`AI Cross Contact B[\\s\\S]*${escapeRegExp(crossOracle.tag)}`),
      );
      expect(result.content).toMatch(/2|两/);
      expect(result.content).toMatch(/客户|account|联系人|contact/i);
    });

    test('S4-02: Customer statistics by industry', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        `不要使用 SQL 或 platform_execute_sql。请只调用 CRM 线索列表工具（cmd_crm_list_leads/list_crm_lead），用 keyword 或 filters 查询公司名包含「${industryOracle.tag}」的线索。请先列出每条线索的联系人、行业、评分，再按行业统计线索数量和平均评分。`,
        'crm_lead',
      );
      await writeEvidence(testInfo, 'S4-02', 'L3', result, {
        ...industryOracle,
        expected: {
          technology: { count: 2, averageScore: 85 },
          manufacturing: { count: 1, averageScore: 70 },
        },
      });

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(toolNames(result)).toMatch(/list_crm_lead|cmd_crm_list_leads/);
      expectNoSqlFallback(result);
      expectToolInputContains(result, industryOracle.tag);
      expect(result.content).toContain(industryOracle.technologyContactA);
      expect(result.content).toContain(industryOracle.technologyContactB);
      expect(result.content).toContain(industryOracle.manufacturingContact);
      expect(result.content).toMatch(/technology[\s\S]*2|2[\s\S]*technology/i);
      expect(result.content).toMatch(/manufacturing[\s\S]*1|1[\s\S]*manufacturing/i);
      expect(result.content).toMatch(/85|平均|avg/i);
      expect(result.content).toMatch(/70/);
    });
  });

  test.describe('Scenario 5: Agent Patrol Task', () => {
    test('S5-01: Agent answers a business patrol question', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        '帮我检查一下，有没有超过7天没有更新的 new 状态线索？列出公司名和创建时间',
        'crm_lead',
      );
      await writeEvidence(testInfo, 'S5-01', 'L4', result, {
        blocker:
          'Command API cannot safely seed historical updated_at records without direct database writes, so this patrol remains observational.',
      });
      expectL4SoftTelemetry(result, 'S5-01');
      if (!result.error && result.content) {
        expect(result.toolCalls.length + result.confirmations.length).toBeGreaterThan(0);
      }
    });

    test('S5-02: Weekly new leads summary', async ({}, testInfo) => {
      const result = await chatWithAuraBot(
        token,
        `不要使用 SQL 或 platform_execute_sql。请只调用 CRM 线索列表工具（cmd_crm_list_leads/list_crm_lead），用 keyword 或 filters 查询公司名包含「${weeklyOracle.tag}」且最近7天创建的线索。统计数量并返回公司名、联系人。`,
        'crm_lead',
      );
      await writeEvidence(testInfo, 'S5-02', 'L3', result, {
        tag: weeklyOracle.tag,
        expectedCount: 2,
        contactA: weeklyOracle.contactA,
        contactB: weeklyOracle.contactB,
      });

      expect(result.error).toBeUndefined();
      expect(result.confirmations).toHaveLength(0);
      expect(toolNames(result)).toMatch(/list_crm_lead|cmd_crm_list_leads/);
      expectNoSqlFallback(result);
      expectToolInputContains(result, weeklyOracle.tag);
      expect(result.content).toContain(weeklyOracle.contactA);
      expect(result.content).toContain(weeklyOracle.contactB);
      expect(result.content).toMatch(/2|两/);
    });
  });
});
