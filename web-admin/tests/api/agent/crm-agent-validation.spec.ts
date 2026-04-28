import { expect, test } from '@playwright/test';

const BACKEND_URL = 'http://127.0.0.1:6443';
const TEST_USER = { email: 'admin@example.com', password: 'Test2026x' };

type Filter = { fieldName: string; operator: string; value?: unknown; values?: unknown[] };

function uniqueId(prefix = 'crm_agent'): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
}

async function getToken(): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(TEST_USER),
  });
  const body = await resp.json();
  expect(body.code, 'Run auth setup or reset CRM env when login fails').toBe('0');
  return body.data.jwt;
}

function authHeaders(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}

async function apiGet(token: string, path: string): Promise<any> {
  const resp = await fetch(`${BACKEND_URL}${path}`, { headers: authHeaders(token) });
  expect(resp.ok, `${path} should return HTTP 2xx`).toBe(true);
  const body = await resp.json();
  expect(body.code, `${path} should return ApiResponse code=0`).toBe('0');
  return body.data;
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
  return apiGet(token, `/api/dynamic/${pageKey}/${recordId}`);
}

async function queryList(
  token: string,
  pageKey: string,
  filters: Filter[] = [],
  pageSize = 50,
): Promise<{ total: number; records: Record<string, any>[] }> {
  const params = new URLSearchParams({ pageNum: '1', pageSize: String(pageSize) });
  if (filters.length > 0) params.set('filters', JSON.stringify(filters));
  const data = await apiGet(token, `/api/dynamic/${pageKey}/list?${params}`);
  return { total: data?.total || 0, records: data?.records || [] };
}

async function queryDatasource(
  token: string,
  datasourceId: string,
  params: Record<string, string>,
): Promise<{ total: number; records: Record<string, any>[] }> {
  const search = new URLSearchParams({ datasourceId, format: 'records', ...params });
  const data = await apiGet(token, `/api/datasource/list?${search}`);
  return { total: data?.total || 0, records: data?.records || [] };
}

function isoDaysAgo(days: number): string {
  return new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

async function createLead(
  token: string,
  tag: string,
  overrides: Record<string, unknown> = {},
): Promise<string> {
  const result = await executeCommand(token, 'crm:create_lead', {
    crm_lead_company: `Agent Oracle ${tag}`,
    crm_lead_contact_name: `Contact ${tag}`,
    crm_lead_contact_phone: '13800138000',
    crm_lead_contact_email: `${tag.toLowerCase()}@agent.test`,
    crm_lead_source: 'website',
    crm_lead_industry: 'technology',
    crm_lead_score: 40,
    crm_lead_requirement: `Agent validation seed ${tag}`,
    ...overrides,
  });
  expect(result.success, result.error).toBe(true);
  expect(result.recordId).toBeTruthy();
  return result.recordId!;
}

async function createLeadActivity(
  token: string,
  leadPid: string,
  tag: string,
  activityDate: string,
): Promise<string> {
  const activity = await executeCommand(token, 'crm:create_activity', {
    crm_act_type: 'call',
    crm_act_subject: `Agent activity ${tag}`,
    crm_act_content: `Agent validation activity ${tag}`,
    crm_act_source: 'manual',
  });
  expect(activity.success, activity.error).toBe(true);
  expect(activity.recordId).toBeTruthy();

  const update = await executeCommand(
    token,
    'crm:update_activity',
    { crm_act_date: activityDate },
    activity.recordId,
  );
  expect(update.success, update.error).toBe(true);

  const relation = await executeCommand(token, 'crm:create_activity_relation', {
    crm_ar_activity_id: activity.recordId,
    crm_ar_object_type: 'lead',
    crm_ar_object_id: leadPid,
    crm_ar_role: 'primary',
  });
  expect(relation.success, relation.error).toBe(true);

  return activity.recordId!;
}

async function createAccount(token: string, tag: string): Promise<string> {
  const result = await executeCommand(token, 'crm:create_account', {
    crm_acc_name: `Agent Account ${tag}`,
    crm_acc_industry: 'technology',
    crm_acc_phone: '0755-88888888',
  });
  expect(result.success, result.error).toBe(true);
  expect(result.recordId).toBeTruthy();
  return result.recordId!;
}

test.describe('CRM Agent deterministic validation', () => {
  let token: string;

  test.beforeAll(async () => {
    token = await getToken();
  });

  test('AV-00 metadata readiness: CRM models, commands, and named queries exist', async () => {
    const leadModel = await apiGet(token, '/api/meta/models/code/crm_lead');
    const complaintModel = await apiGet(token, '/api/meta/models/code/crm_complaint');
    expect(leadModel?.code).toBe('crm_lead');
    expect(complaintModel?.code).toBe('crm_complaint');

    for (const commandCode of [
      'crm:create_lead',
      'crm:convert_lead',
      'crm:create_complaint',
      'crm:close_complaint',
    ]) {
      const command = await apiGet(token, `/api/meta/commands/by-code/${commandCode}`);
      expect(command?.code).toBe(commandCode);
      expect(command?.status).toBe('published');
    }

    for (const queryCode of [
      'crm_lead_pipeline_stats',
      'crm_lead_source_distribution',
      'crm_lead_stale_followup',
    ]) {
      const query = await apiGet(token, `/api/meta/named-queries/by-code/${queryCode}`);
      expect(query?.code).toBe(queryCode);
      expect(query?.status).toBe('published');
    }
  });

  test('AV-01 lead lifecycle and illegal convert are verified by detail API', async () => {
    const tag = uniqueId('lead_lifecycle');
    const leadPid = await createLead(token, tag);

    const created = await detail(token, 'crm_lead_list', leadPid);
    expect(created.crm_lead_company).toBe(`Agent Oracle ${tag}`);
    expect(created.crm_lead_status).toBe('new');

    const illegalConvert = await executeCommand(token, 'crm:convert_lead', {}, leadPid);
    expect(illegalConvert.success).toBe(false);
    const stillNew = await detail(token, 'crm_lead_list', leadPid);
    expect(stillNew.crm_lead_status).toBe('new');

    const update = await executeCommand(token, 'crm:update_lead', { crm_lead_score: 88 }, leadPid);
    expect(update.success, update.error).toBe(true);
    expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_score).toBe(88);

    expect((await executeCommand(token, 'crm:contact_lead', {}, leadPid)).success).toBe(true);
    expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_status).toBe('contacted');

    expect((await executeCommand(token, 'crm:qualify_lead', {}, leadPid)).success).toBe(true);
    expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_status).toBe('qualified');

    expect((await executeCommand(token, 'crm:convert_lead', {}, leadPid)).success).toBe(true);
    expect((await detail(token, 'crm_lead_list', leadPid)).crm_lead_status).toBe('converted');
  });

  test('AV-02 lead analytics oracle covers status, source, high score, and industry', async () => {
    const tag = uniqueId('lead_analytics');
    const websiteHighPid = await createLead(token, `${tag}_website_high`, {
      crm_lead_company: `Agent Analytics ${tag} Website High`,
      crm_lead_source: 'website',
      crm_lead_industry: 'technology',
      crm_lead_score: 96,
    });
    const referralPid = await createLead(token, `${tag}_referral`, {
      crm_lead_company: `Agent Analytics ${tag} Referral`,
      crm_lead_source: 'referral',
      crm_lead_industry: 'manufacturing',
      crm_lead_score: 72,
    });
    await executeCommand(token, 'crm:contact_lead', {}, referralPid);
    await executeCommand(token, 'crm:qualify_lead', {}, referralPid);
    await executeCommand(token, 'crm:convert_lead', {}, referralPid);

    const tagFilter: Filter = {
      fieldName: 'crm_lead_company',
      operator: 'LIKE',
      value: `%${tag}%`,
    };
    const allSeeded = await queryList(token, 'crm_lead_list', [tagFilter]);
    expect(allSeeded.records.map((r) => r.pid)).toEqual(
      expect.arrayContaining([websiteHighPid, referralPid]),
    );

    const newLeads = await queryList(token, 'crm_lead_list', [
      tagFilter,
      { fieldName: 'crm_lead_status', operator: 'EQ', value: 'new' },
    ]);
    expect(newLeads.records.map((r) => r.pid)).toContain(websiteHighPid);

    const convertedReferral = await queryList(token, 'crm_lead_list', [
      tagFilter,
      { fieldName: 'crm_lead_source', operator: 'EQ', value: 'referral' },
      { fieldName: 'crm_lead_status', operator: 'EQ', value: 'converted' },
    ]);
    expect(convertedReferral.records.map((r) => r.pid)).toContain(referralPid);

    const highScore = await queryList(token, 'crm_lead_list', [
      tagFilter,
      { fieldName: 'crm_lead_score', operator: 'GT', value: 80 },
    ]);
    expect(highScore.records.map((r) => r.crm_lead_company)).toContain(
      `Agent Analytics ${tag} Website High`,
    );

    const technology = await queryList(token, 'crm_lead_list', [
      tagFilter,
      { fieldName: 'crm_lead_industry', operator: 'EQ', value: 'technology' },
    ]);
    expect(technology.records.map((r) => r.pid)).toContain(websiteHighPid);
  });

  test('AV-03 complaint lifecycle is verified end to end', async () => {
    const tag = uniqueId('complaint');
    const accountPid = await createAccount(token, tag);
    const create = await executeCommand(token, 'crm:create_complaint', {
      crm_cmp_account_id: accountPid,
      crm_cmp_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      crm_cmp_type: 'product_quality',
      crm_cmp_severity: 'high',
      crm_cmp_description: `Agent complaint validation ${tag}`,
    });
    expect(create.success, create.error).toBe(true);
    const complaintPid = create.recordId!;

    expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe('open');
    expect(
      (await executeCommand(token, 'crm:investigate_complaint', {}, complaintPid)).success,
    ).toBe(true);
    expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe(
      'investigating',
    );

    const rootCause = `Root cause ${tag}`;
    const correctiveAction = `Corrective action ${tag}`;
    expect(
      (
        await executeCommand(
          token,
          'crm:update_complaint',
          {
            crm_cmp_root_cause: rootCause,
            crm_cmp_corrective_action: correctiveAction,
          },
          complaintPid,
        )
      ).success,
    ).toBe(true);
    const updated = await detail(token, 'crm_complaint_list', complaintPid);
    expect(updated.crm_cmp_root_cause).toBe(rootCause);
    expect(updated.crm_cmp_corrective_action).toBe(correctiveAction);

    expect(
      (
        await executeCommand(
          token,
          'crm:resolve_complaint',
          {
            crm_cmp_root_cause: rootCause,
            crm_cmp_corrective_action: correctiveAction,
            crm_cmp_resolution_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
          },
          complaintPid,
        )
      ).success,
    ).toBe(true);
    expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe(
      'resolved',
    );

    expect((await executeCommand(token, 'crm:close_complaint', {}, complaintPid)).success).toBe(
      true,
    );
    expect((await detail(token, 'crm_complaint_list', complaintPid)).crm_cmp_status).toBe('closed');
  });

  test('AV-04 account related complaint and recent lead oracle are queryable', async () => {
    const tag = uniqueId('cross_recent');
    const accountPid = await createAccount(token, tag);
    const leadPid = await createLead(token, tag, {
      crm_lead_company: `Agent Recent ${tag}`,
      crm_lead_score: 91,
    });
    const complaint = await executeCommand(token, 'crm:create_complaint', {
      crm_cmp_account_id: accountPid,
      crm_cmp_date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      crm_cmp_type: 'service',
      crm_cmp_severity: 'medium',
      crm_cmp_description: `Related complaint ${tag}`,
    });
    expect(complaint.success, complaint.error).toBe(true);

    const relatedComplaints = await queryList(token, 'crm_complaint_list', [
      { fieldName: 'crm_cmp_account_id', operator: 'EQ', value: accountPid },
    ]);
    expect(relatedComplaints.records.map((r) => r.pid)).toContain(complaint.recordId);

    const recentLead = await queryList(token, 'crm_lead_list', [
      { fieldName: 'crm_lead_company', operator: 'LIKE', value: `%${tag}%` },
    ]);
    expect(recentLead.records.map((r) => r.pid)).toContain(leadPid);
  });

  test('AV-05 stale new lead follow-up oracle is queryable without direct DB writes', async () => {
    const tag = uniqueId('stale_followup');
    const staleLeadPid = await createLead(token, `${tag}_stale`, {
      crm_lead_company: `Agent Stale Followup ${tag}`,
      crm_lead_contact_name: `Stale Contact ${tag}`,
      crm_lead_score: 61,
    });
    const freshLeadPid = await createLead(token, `${tag}_fresh`, {
      crm_lead_company: `Agent Fresh Followup ${tag}`,
      crm_lead_contact_name: `Fresh Contact ${tag}`,
      crm_lead_score: 62,
    });

    const oldActivityDate = isoDaysAgo(10);
    const recentActivityDate = isoDaysAgo(1);
    await createLeadActivity(token, staleLeadPid, `${tag}_old`, oldActivityDate);
    await createLeadActivity(token, freshLeadPid, `${tag}_recent`, recentActivityDate);

    const stale = await queryDatasource(token, 'nq:crm_lead_stale_followup', {
      keyword: tag,
      staleBefore: isoDaysAgo(7),
      maxItems: '50',
    });

    const stalePids = stale.records.map((r) => r.pid);
    expect(stalePids).toContain(staleLeadPid);
    expect(stalePids).not.toContain(freshLeadPid);
    const staleRecord = stale.records.find((r) => r.pid === staleLeadPid);
    expect(staleRecord?.company).toBe(`Agent Stale Followup ${tag}`);
    expect(staleRecord?.contact_name).toBe(`Stale Contact ${tag}`);
    expect(String(staleRecord?.last_activity_date)).toContain(oldActivityDate.slice(0, 10));
  });
});
