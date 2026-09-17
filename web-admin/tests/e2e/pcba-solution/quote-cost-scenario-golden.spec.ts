import { test, expect } from '../../fixtures';
import { dynamicCreate, executeCommand, type CreatedRows } from './quote-e2e-helpers';

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
});
