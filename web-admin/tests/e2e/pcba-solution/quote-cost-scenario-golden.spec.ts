import { test, expect } from '../../fixtures';
import { dynamicCreate, executeCommand, queryDynamicRecords, type CreatedRows } from './quote-e2e-helpers';

test.describe('PCBA quote cost scenario golden', () => {
  test.describe.configure({ timeout: 120_000 });

  // Q18-01: 成本方案详情页——方案状态/承诺级别按字典显示业务标签(已冻结/估算报价),
  // 档位/假设/缺口子表人类可读渲染,无 raw 状态值或内部 JSON 泄漏。
  test('Q18-01 frozen scenario detail renders dict labels for status/commitment and human-readable tiers, assumptions and gaps', async ({
    page,
  }, info) => {
    const marker = `CSGOLDEN-${Date.now()}`;
    const payload: Record<string, unknown> = {
      qo_cs_name: marker, qo_cs_customer: marker, qo_cs_commitment_level: 'estimate', qo_cs_currency: 'CNY',
      qo_cs_valid_until: '2030-12-31', idempotency_key: marker,
      tiers: [{ qo_cst_quantity: 100, qo_cst_currency: 'CNY', qo_cst_material_unit_cost: 10,
        qo_cst_process_unit_cost: 2, qo_cst_nre_total: 100, qo_cst_other_unit_cost: 1,
        qo_cst_risk_pct: 5, qo_cst_target_margin_pct: 20 }],
      assumptions: [], gaps: [],
    };
    for (const field of ['mdp_id', 'mdp_version', 'mdp_hash', 'structure_ref', 'process_ref', 'pack_set_version',
      'price_snapshot_version', 'rate_card_version', 'fx_rate_version', 'rule_set_version', 'risk_version',
      'assumption_set_version'])
      payload[`qo_cs_${field}`] = `${marker}-${field}`;
    const created = await executeCommand(page, 'qo_cost_scenario_common:create_from_mdp', payload, undefined, 'create');
    const scenarioId = String(created.recordId);
    expect(scenarioId).not.toBe('undefined');
    await executeCommand(page, 'qo_cost_scenario_common:calculate', {}, scenarioId, 'update');
    await executeCommand(page, 'qo_cost_scenario_common:freeze', { frozen_by: 'Local E2E' }, scenarioId, 'update');

    // 假设与缺口行直挂场景(create_from_mdp 的内联假设受 GT-Q02/字段写权限约束;
    // status 类字段仅授权命令可写,走默认值)
    const fixtureRows: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const assumptionId = await dynamicCreate(page, 'qo_cost_assumption_common', {
      qo_ca_code: 'ASSUME-FX', qo_ca_scenario_id: scenarioId,
      qo_ca_description: '汇率假设 7.0 CNY/USD', qo_ca_impact_type: 'cost',
      qo_ca_version: 'v1',
    }, fixtureRows.rows);
    const gapId = await dynamicCreate(page, 'qo_cost_gap_common', {
      qo_cg_code: 'GAP-TEST', qo_cg_scenario_id: scenarioId,
      qo_cg_severity: 'major', qo_cg_description: '缺少测试报告成本项',
    }, fixtureRows.rows);
    expect(assumptionId).toBeTruthy();
    expect(gapId).toBeTruthy();

    await page.goto(`/p/qo_cost_scenario_common/view/${scenarioId}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /成本方案|Cost/i })).toBeVisible({ timeout: 20_000 });

    // 字典业务标签:方案状态 frozen → 已冻结;承诺级别 estimate → 估算报价
    await expect(page.getByText('已冻结', { exact: true }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('估算报价', { exact: true }).first()).toBeVisible({ timeout: 20_000 });
    // 无 raw 状态值节点
    await expect(page.getByText('frozen', { exact: true })).toHaveCount(0);
    await expect(page.getByText('estimate', { exact: true })).toHaveCount(0);

    // 档位子表人类可读:数量 100 可见;缺口/假设描述人类可读,无内部 JSON 块
    const bodyText = await page.locator('main').innerText();
    expect(bodyText).toContain('100');
    expect(bodyText).toContain('缺少测试报告成本项');
    expect(bodyText).toContain('汇率假设 7.0 CNY/USD');
    expect(bodyText).not.toMatch(/"qo_cs_status"\s*:/);
    expect(bodyText).not.toMatch(/\{"qo_cg_/);
    await info.attach('q18-01-scenario-detail', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });
  });

  // Q18-02: 冻结方案不可变——冻结后修改档位/假设(授权命令入口)被拒绝;
  // draft/ready 状态下档位保存生效并产生版本记录(row_version 递增)。
  test('Q18-02 frozen scenario rejects tier/assumption mutations; draft and ready saves persist with version records', async ({
    page,
  }) => {
    const marker = `CSFRZ-${Date.now()}`;
    const payload: Record<string, unknown> = {
      qo_cs_name: marker, qo_cs_customer: marker, qo_cs_commitment_level: 'estimate', qo_cs_currency: 'CNY',
      qo_cs_valid_until: '2030-12-31', idempotency_key: marker,
      tiers: [{ qo_cst_quantity: 100, qo_cst_currency: 'CNY', qo_cst_material_unit_cost: 10,
        qo_cst_process_unit_cost: 2, qo_cst_nre_total: 100, qo_cst_other_unit_cost: 1,
        qo_cst_risk_pct: 5, qo_cst_target_margin_pct: 20 }],
      assumptions: [], gaps: [],
    };
    for (const field of ['mdp_id', 'mdp_version', 'mdp_hash', 'structure_ref', 'process_ref', 'pack_set_version',
      'price_snapshot_version', 'rate_card_version', 'fx_rate_version', 'rule_set_version', 'risk_version',
      'assumption_set_version'])
      payload[`qo_cs_${field}`] = `${marker}-${field}`;
    const created = await executeCommand(page, 'qo_cost_scenario_common:create_from_mdp', payload, undefined, 'create');
    const scenarioId = String(created.recordId);
    expect(scenarioId).not.toBe('undefined');

    const scenarioRecord = async () =>
      (await (await page.request.get(`/api/dynamic/qo_cost_scenario_common/${scenarioId}`)).json()).data;
    const tierRows = async () =>
      await queryDynamicRecords(page, 'qo_cost_scenario_tier_common', [
        { fieldName: 'qo_cst_scenario_id', operator: 'EQ', value: scenarioId },
      ]);
    const draft = await scenarioRecord();
    expect(draft.qo_cs_status, 'fresh scenario starts in draft').toBe('draft');
    const tiers = await tierRows();
    expect(tiers).toHaveLength(1);
    const tierPid = String(tiers[0].pid);
    const tierRvAtDraft = Number(tiers[0].row_version);

    // draft 保存生效并产生版本记录
    const draftSave = await page.request.put(`/api/dynamic/qo_cost_scenario_tier_common/${tierPid}`, {
      data: { qo_cst_quantity: 150 },
    });
    expect(draftSave.ok(), 'draft tier save persists').toBe(true);
    const afterDraft = await tierRows();
    expect(Number(afterDraft[0].qo_cst_quantity)).toBe(150);
    expect(
      Number(afterDraft[0].row_version),
      'draft save produces a new version record (row_version)',
    ).toBeGreaterThan(tierRvAtDraft);

    // calculate → ready;ready 下保存同样生效并递增版本
    await executeCommand(page, 'qo_cost_scenario_common:calculate', {}, scenarioId, 'update');
    expect((await scenarioRecord()).qo_cs_status).toBe('ready');
    const readySave = await page.request.put(`/api/dynamic/qo_cost_scenario_tier_common/${tierPid}`, {
      data: { qo_cst_quantity: 200 },
    });
    expect(readySave.ok(), 'ready tier save persists').toBe(true);
    const afterReady = await tierRows();
    expect(Number(afterReady[0].qo_cst_quantity)).toBe(200);
    expect(
      Number(afterReady[0].row_version),
      'ready save produces a new version record (row_version)',
    ).toBeGreaterThan(Number(afterDraft[0].row_version));

    // freeze → 冻结
    await executeCommand(page, 'qo_cost_scenario_common:freeze', { frozen_by: 'Local E2E' }, scenarioId, 'update');
    expect((await scenarioRecord()).qo_cs_status).toBe('frozen');

    // 冻结后:授权命令入口的档位/假设/重算变更一律拒绝,提示创建新版本
    const expectImmutable = async (commandCode: string, data: Record<string, unknown>) => {
      const resp = await page.request.post(`/api/meta/commands/execute/${commandCode}`, { data });
      expect(resp.status(), `${commandCode} must reject a frozen scenario`).toBe(400);
      const body = await resp.json().catch(() => ({}));
      expect(
        JSON.stringify(body),
        `${commandCode}: ${JSON.stringify(body).slice(0, 300)}`,
      ).toContain('Frozen cost scenarios are immutable');
    };
    await expectImmutable('qo_cost_scenario_tier_common:create', {
      payload: { qo_cst_scenario_id: scenarioId, qo_cst_quantity: 500, qo_cst_currency: 'CNY',
        qo_cst_material_unit_cost: 10, qo_cst_process_unit_cost: 2, qo_cst_nre_total: 100,
        qo_cst_other_unit_cost: 1, qo_cst_risk_pct: 5, qo_cst_target_margin_pct: 20 },
      targetRecordPid: scenarioId, targetRecordId: scenarioId, operationType: 'create',
    });
    await expectImmutable('qo_cost_assumption_common:create', {
      payload: { qo_ca_code: 'ASSUME-X', qo_ca_scenario_id: scenarioId,
        qo_ca_description: 'post-freeze attempt', qo_ca_impact_type: 'cost', qo_ca_version: 'v1' },
      targetRecordPid: scenarioId, targetRecordId: scenarioId, operationType: 'create',
    });
    await expectImmutable('qo_cost_scenario_common:calculate', {
      payload: {}, targetRecordPid: scenarioId, targetRecordId: scenarioId, operationType: 'update',
    });
    // 冻结后档位值保持最后一次 ready 保存的 200,未被后续尝试改动
    expect(Number((await tierRows())[0].qo_cst_quantity)).toBe(200);
  });
});
