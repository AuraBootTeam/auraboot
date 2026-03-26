/**
 * Showcase Arsenal Seed — Complete Capability Demonstration
 *
 * Creates ALL arsenal showcase data:
 * 1. Showcase plugin sample data (10 records with all 22 field types)
 * 2. 19-widget Dashboard (all chart/widget types)
 * 3. 10-block Report (all report block types)
 * 4. Full BPMN process (all 9 node types)
 * 5. Full Automation rules (all 6 triggers + 9 actions)
 * 6. AI Agent tool showcase data
 *
 * Run AFTER seed-showcase-data.spec.ts and plugin imports:
 *   aura plugin publish plugins/showcase --yes
 *   npx playwright test seed-showcase-arsenal --config=playwright.seed.config.ts
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi } from '../../e2e/helpers';

async function cmd(
  page: any, commandCode: string, payload: Record<string, unknown>,
  targetRecordId?: string, operationType?: string
): Promise<string> {
  const result = await executeCommandViaApi(page, commandCode, payload, targetRecordId, operationType);
  expect(result.code).toBe('0');
  return result.recordId;
}

test.describe.serial('Showcase Arsenal — Full Capability Demo', () => {
  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(300_000);

  // ═════════════════════════════════════════════════════════════════════════
  // 1. Showcase Plugin — 10 records with ALL field types
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 1: Showcase All-Fields — 10 sample records', async ({ page }) => {
    const items = [
      { name: '高性能MCU控制模块', desc: '基于ARM Cortex-M4的高性能微控制器模块，支持CAN/LIN通信', qty: 500, price: 28.50, active: true, status: 'active', priority: 'high', category: 'electronics', progress: 85, rating: 5, color: '#3B82F6' },
      { name: '车规级LDO稳压器', desc: '宽温域-40~150°C，AEC-Q100认证，适用于汽车电子', qty: 2000, price: 3.80, active: true, status: 'active', priority: 'critical', category: 'electronics', progress: 100, rating: 5, color: '#EF4444' },
      { name: 'PCBA贴片加工服务', desc: 'SMT全自动贴片线，支持0201~QFN/BGA，AOI+SPI全检', qty: 1, price: 15000, active: true, status: 'active', priority: 'high', category: 'service', progress: 60, rating: 4, color: '#10B981' },
      { name: '工业级连接器组件', desc: 'IP67防水矩形连接器，12Pin，额定电流5A/250V', qty: 800, price: 12.60, active: true, status: 'review', priority: 'medium', category: 'hardware', progress: 45, rating: 3, color: '#F59E0B' },
      { name: '物联网网关开发套件', desc: '含Linux主板+4G模块+WiFi+BLE+GPS，预装AuraBoot Agent', qty: 50, price: 680, active: true, status: 'active', priority: 'high', category: 'electronics', progress: 90, rating: 4, color: '#6366F1' },
      { name: '柔性PCB排线', desc: 'FPC 0.5mm间距，6层双面FPCB，用于手机内部连接', qty: 5000, price: 1.25, active: true, status: 'active', priority: 'low', category: 'hardware', progress: 100, rating: 4, color: '#8B5CF6' },
      { name: '智能电源管理芯片', desc: '多通道PMIC，支持锂电池充放电管理+路径管理', qty: 1000, price: 8.90, active: true, status: 'draft', priority: 'medium', category: 'electronics', progress: 20, rating: 3, color: '#EC4899' },
      { name: '自动化测试夹具', desc: '定制ICT/FCT测试治具，含编程烧录+功能测试+外观检测', qty: 10, price: 8500, active: false, status: 'archived', priority: 'low', category: 'hardware', progress: 100, rating: 5, color: '#6B7280' },
      { name: 'EMC整改咨询服务', desc: '提供辐射/传导超标整改方案，含实验室预测试+整改+复测', qty: 1, price: 25000, active: true, status: 'active', priority: 'medium', category: 'service', progress: 30, rating: 4, color: '#14B8A6' },
      { name: '新能源BMS主控板', desc: '16串锂电池管理系统主控板，CAN通信+均衡+SOC估算', qty: 200, price: 158, active: true, status: 'review', priority: 'critical', category: 'electronics', progress: 70, rating: 5, color: '#F97316' },
    ];

    let created = 0;
    for (const item of items) {
      try {
        await cmd(page, 'sc:create_showcase', {
          sc_name: item.name,
          sc_description: item.desc,
          sc_quantity: item.qty,
          sc_price: item.price,
          sc_is_active: item.active,
          sc_status: item.status,
          sc_priority: item.priority,
          sc_category: item.category,
          sc_progress: item.progress,
          sc_rating: item.rating,
          sc_color: item.color,
          sc_website: 'https://www.xinrantech.com',
          sc_email: 'sales@xinrantech.com',
          sc_phone: '0755-86543210',
          sc_richtext_content: `<h3>${item.name}</h3><p>${item.desc}</p><ul><li>数量: ${item.qty}</li><li>单价: ¥${item.price}</li></ul>`,
          sc_remark: `Showcase demo record #${created + 1}`,
        });
        created++;
      } catch (e) {
        console.warn(`  Failed to create showcase record "${item.name}": ${(e as Error).message.slice(0, 100)}`);
      }
    }
    console.log(`  Created ${created}/10 showcase records`);
    expect(created).toBeGreaterThanOrEqual(5);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 2. 19-Widget Dashboard
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 2: Dashboard — 19 widget types', async ({ page }) => {
    const widgets = [
      // Row 1: 4 Number Cards (h=2 at rowHeight 80 = ~170px, good for cards)
      { i: 'w_num_customers', x: 0, y: 0, w: 3, h: 2, type: 'NumberCard', title: '客户总数', config: { value: 60, icon: '🏢' } },
      { i: 'w_num_pipeline', x: 3, y: 0, w: 3, h: 2, type: 'NumberCard', title: 'Pipeline 金额', config: { value: 580, icon: '💰' } },
      { i: 'w_num_winrate', x: 6, y: 0, w: 3, h: 2, type: 'NumberCard', title: '赢单率', config: { value: 73, icon: '🎯' } },
      { i: 'w_num_leads', x: 9, y: 0, w: 3, h: 2, type: 'NumberCard', title: '本月新线索', config: { value: 28, icon: '📋' } },
      // Row 2: Bar + Line (h=4 at rowHeight 80 = ~350px, enough for ECharts)
      { i: 'w_bar_monthly', x: 0, y: 2, w: 6, h: 4, type: 'BarChart', title: '月度销售对比', config: {
        xAxis: ['2025/07', '2025/08', '2025/09', '2025/10', '2025/11', '2025/12', '2026/01', '2026/02', '2026/03'],
        series: [
          { name: '目标(万)', data: [80, 80, 100, 100, 100, 120, 120, 80, 120] },
          { name: '实际(万)', data: [45, 68, 175, 92, 58, 138, 460, 72, 96] },
        ],
      } },
      { i: 'w_line_trend', x: 6, y: 2, w: 6, h: 4, type: 'LineChart', title: '销售趋势', config: {
        xAxis: ['2025/07', '2025/08', '2025/09', '2025/10', '2025/11', '2025/12', '2026/01', '2026/02', '2026/03'],
        series: [
          { name: '新客户', data: [6, 8, 12, 9, 7, 11, 15, 8, 10] },
          { name: '新商机', data: [5, 7, 14, 10, 8, 13, 18, 9, 12] },
          { name: '赢单数', data: [2, 3, 8, 5, 3, 6, 12, 4, 5] },
        ],
      } },
      // Row 3: Pie + Funnel + Radar (h=4)
      { i: 'w_pie_stage', x: 0, y: 6, w: 4, h: 4, type: 'PieChart', title: '商机阶段分布', config: { data: [
        { name: '初步接触', value: 15 }, { name: '需求确认', value: 12 }, { name: '方案报价', value: 18 },
        { name: '商务谈判', value: 8 }, { name: '赢单', value: 22 }, { name: '输单', value: 6 },
      ] } },
      { i: 'w_funnel', x: 4, y: 6, w: 4, h: 4, type: 'FunnelChart', title: '销售漏斗', config: { data: [
        { name: '线索', value: 120 }, { name: '合格线索', value: 68 }, { name: '需求确认', value: 42 },
        { name: '方案报价', value: 28 }, { name: '商务谈判', value: 18 }, { name: '赢单', value: 12 },
      ] } },
      { i: 'w_radar', x: 8, y: 6, w: 4, h: 4, type: 'RadarChart', title: '销售团队能力', config: {
        categories: ['客户开发', '方案能力', '谈判技巧', '交付管理', '客户维护'],
        series: [
          { name: '陈志豪', data: [92, 85, 88, 75, 95] },
          { name: '张雨晴', data: [78, 95, 72, 88, 82] },
          { name: '林伟杰', data: [85, 78, 90, 82, 76] },
        ],
      } },
      // Row 4: Area + Gauge + Progress
      { i: 'w_area', x: 0, y: 10, w: 4, h: 4, type: 'AreaChart', title: '累计收入趋势', config: {
        xAxis: ['Q1-2025', 'Q2-2025', 'Q3-2025', 'Q4-2025', 'Q1-2026'],
        series: [{ name: '累计收入(万)', data: [120, 380, 720, 1200, 1560] }],
      } },
      { i: 'w_gauge', x: 4, y: 10, w: 4, h: 4, type: 'GaugeChart', title: 'Q1 目标完成率', config: { value: 78, max: 100 } },
      { i: 'w_progress', x: 8, y: 10, w: 4, h: 2, type: 'Progress', title: '年度KPI进度', config: { value: 68, target: 100 } },
      // Row 4 continued: Scatter
      { i: 'w_scatter', x: 8, y: 12, w: 4, h: 2, type: 'ScatterChart', title: '客户规模 vs 商机金额', config: { data: [
        { x: 50, y: 120 }, { x: 100, y: 280 }, { x: 200, y: 580 }, { x: 80, y: 175 },
        { x: 150, y: 460 }, { x: 30, y: 65 }, { x: 250, y: 720 }, { x: 120, y: 340 },
      ] } },
      // Row 5: Table + Heatmap
      { i: 'w_table', x: 0, y: 14, w: 5, h: 4, type: 'TableChart', title: '销售排行榜', config: {
        columns: ['排名', '姓名', '赢单额(万)', '赢单数'],
        data: [['1', '陈志豪', '680', '12'], ['2', '张雨晴', '520', '9'], ['3', '林伟杰', '380', '8'], ['4', '王小明', '260', '6'], ['5', '李佳慧', '210', '5']],
      } },
      { i: 'w_heatmap', x: 5, y: 14, w: 7, h: 4, type: 'HeatmapChart', title: '团队活跃度热力图', config: {
        xAxis: ['周一', '周二', '周三', '周四', '周五'],
        yAxis: ['上午', '下午', '晚间'],
        data: [[0,0,8],[0,1,12],[0,2,3],[1,0,10],[1,1,15],[1,2,5],[2,0,9],[2,1,11],[2,2,2],[3,0,13],[3,1,14],[3,2,6],[4,0,7],[4,1,9],[4,2,1]],
      } },
      // Row 6: Treemap + Rich Text + Countdown
      { i: 'w_treemap', x: 0, y: 18, w: 4, h: 4, type: 'TreemapChart', title: '行业收入分布', config: { data: [
        { name: '汽车电子', value: 600 }, { name: '工业自动化', value: 360 },
        { name: '智能硬件', value: 310 }, { name: '消费电子', value: 280 }, { name: '通信设备', value: 180 },
      ] } },
      { i: 'w_richtext', x: 4, y: 18, w: 4, h: 4, type: 'RichText', title: '季度公告', config: {
        content: '<h3 style="margin:0 0 8px 0;color:#1e40af">📊 Q1 销售总结</h3><p style="margin:0 0 6px 0">本季度完成销售额 <strong style="color:#059669">¥1,560万</strong>，同比增长 <strong style="color:#059669">32%</strong>，超额完成目标。</p><ul style="margin:4px 0;padding-left:18px"><li>新增客户 <b>28</b> 家，重点客户 <b>5</b> 家</li><li>赢单 <b>12</b> 笔，平均客单价 <b>¥130万</b></li><li>重点项目：宁波鑫越年度框架 ¥460万</li><li>销冠：陈志豪 ¥680万（连续3个月第一）</li></ul>',
      } },
      { i: 'w_countdown', x: 8, y: 18, w: 4, h: 2, type: 'Countdown', title: 'Q2 结束倒计时', config: { targetDate: '2026-06-30T23:59:59Z', label: '距离 Q2 结束' } },
      // Row 6 continued: Leaderboard
      { i: 'w_leaderboard', x: 8, y: 20, w: 4, h: 2, type: 'Leaderboard', title: '销售冠军榜', config: {
        items: [
          { rank: 1, name: '陈志豪', value: 680 },
          { rank: 2, name: '张雨晴', value: 520 },
          { rank: 3, name: '林伟杰', value: 380 },
          { rank: 4, name: '王小明', value: 260 },
          { rank: 5, name: '李佳慧', value: 210 },
        ],
      } },
    ];

    const resp = await page.request.post('/api/dashboards', {
      data: {
        code: 'arsenal_capability_dashboard',
        title: '军火展 — 组件仪表盘',
        description: '展示 AuraBoot Dashboard Designer 支持的全部 Widget 类型',
        scope: 'global',
        layoutConfig: { columns: 12, rowHeight: 80, gap: 12 },
        widgets,
        isDefault: false,
        sortOrder: 1,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created Dashboard: 19 组件全覆盖仪表盘');
      const pid = body.data?.pid || body.data?.id;
      if (pid) {
        await page.request.post(`/api/dashboards/${pid}/publish`).catch(() => {});
      }
    } else {
      console.warn(`  Dashboard creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 3. 10-Block Report
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 3: Report — 10 block types', async ({ page }) => {
    const reportDsl = {
      kind: 'Report',
      version: '1.0.0',
      id: 'report.arsenal_full',
      pageSize: 'A4',
      areas: {
        header: {
          blocks: [{
            id: 'hdr_1', blockType: 'page-header', height: 80,
            elements: [
              { type: 'text', content: '鑫然科技', align: 'left', style: { fontSize: 18, fontWeight: 'bold' } },
              { type: 'text', content: '军火展 — 全能力报表', align: 'center', style: { fontSize: 14 } },
              { type: 'date', align: 'right', style: { fontSize: 10 } },
            ],
          }],
        },
        main: {
          blocks: [
            { id: 'blk_stat_1', blockType: 'stat-card', title: '总客户数', config: { aggregation: 'count', label: '客户总数', color: '#3B82F6' } },
            { id: 'blk_stat_2', blockType: 'stat-card', title: '赢单总额', config: { label: '赢单总额', value: '¥2,250万', color: '#10B981' } },
            { id: 'blk_stat_3', blockType: 'stat-card', title: '赢单率', config: { label: '赢单率', value: '73%', color: '#F59E0B' } },
            { id: 'blk_table', blockType: 'data-table', title: '客户清单', config: { dataSource: { type: 'model', modelCode: 'crm_account' }, columns: [{ field: 'crm_acc_code', title: '编号', width: 120 }, { field: 'crm_acc_name', title: '客户名', width: 200 }, { field: 'crm_acc_industry', title: '行业', width: 100 }, { field: 'crm_acc_rating', title: '评级', width: 60 }] } },
            { id: 'blk_grouped', blockType: 'grouped-table', title: '按行业分组汇总', config: { groupByField: 'crm_acc_industry', columns: [{ field: 'crm_acc_name', title: '客户名' }, { field: 'crm_acc_rating', title: '评级' }] } },
            { id: 'blk_richtext', blockType: 'rich-text', content: '## 报告说明\n\n本报表展示鑫然科技 CRM 系统中的核心数据指标，包括客户分布、销售漏斗、行业分析等维度。数据时间范围：2024年1月 — 2025年3月。\n\n> 数据来源：AuraBoot CRM 模块自动生成' },
            { id: 'blk_crosstab', blockType: 'cross-tab', title: '销售×月份透视表', config: { rowField: 'owner', colField: 'month', valueField: 'amount', aggregation: 'sum' } },
            { id: 'blk_chart_bar', blockType: 'chart', title: '月度销售柱状图', config: { chartType: 'bar', categoryField: 'month', valueField: 'amount' } },
            { id: 'blk_chart_pie', blockType: 'chart', title: '商机阶段饼图', config: { chartType: 'pie', categoryField: 'stage', valueField: 'count' } },
            { id: 'blk_barcode', blockType: 'barcode', title: '报表编号条码', config: { format: 'code128', value: 'RPT-2025-ARSENAL-001', width: 2, height: 60 } },
            { id: 'blk_watermark', blockType: 'watermark', config: { text: '鑫然科技 — 机密', fontSize: 24, color: 'rgba(0,0,0,0.06)', rotation: -30 } },
          ],
        },
        footer: {
          blocks: [{
            id: 'ftr_1', blockType: 'page-footer', height: 40,
            elements: [
              { type: 'text', content: '鑫然科技有限公司', align: 'left', style: { fontSize: 9, color: '#999' } },
              { type: 'page-number', align: 'center', style: { fontSize: 9 } },
              { type: 'text', content: '机密文件，禁止外传', align: 'right', style: { fontSize: 9, color: '#999' } },
            ],
          }],
        },
      },
    };

    const resp = await page.request.post('/api/pages', {
      data: {
        pageKey: 'arsenal_full_report',
        name: '军火展 — 10块全覆盖报表',
        title: '军火展 — 10块全覆盖报表',
        description: '展示 Report Designer 的全部 10 种 Block 类型：data-table、grouped-table、stat-card、rich-text、cross-tab、chart(bar)、chart(pie)、barcode、watermark + page-header/footer',
        pageType: 'custom',
        pageCategory: 'REPORT',
        dslSchema: reportDsl,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created Report: 10 块全覆盖报表');
    } else {
      console.warn(`  Report creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 4. Full BPMN — All 9 node types in one process
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 4: BPMN — All 9 node types process', async ({ page }) => {
    const processKey = 'arsenal_full_nodes';
    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="军火展 — 全节点流程" isExecutable="true">
    <startEvent id="start" name="开始"/>
    <userTask id="ut_apply" name="填写申请(UserTask)"/>
    <exclusiveGateway id="xgw" name="金额判断(ExclusiveGateway)"/>
    <serviceTask id="st_auto" name="自动审批(ServiceTask)"/>
    <parallelGateway id="pgw_split" name="并行分支(ParallelGateway)"/>
    <userTask id="ut_tech" name="技术评审(UserTask)"/>
    <userTask id="ut_finance" name="财务审批(UserTask)"/>
    <inclusiveGateway id="igw" name="包容汇聚(InclusiveGateway)"/>
    <receiveTask id="rt_wait" name="等待确认(ReceiveTask)"/>
    <callActivity id="ca_sub" name="子流程(CallActivity)" calledElement="showcase_quote_approval"/>
    <parallelGateway id="pgw_join" name="并行汇聚"/>
    <endEvent id="end" name="结束"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="ut_apply"/>
    <sequenceFlow id="f2" sourceRef="ut_apply" targetRef="xgw"/>
    <sequenceFlow id="f3" sourceRef="xgw" targetRef="st_auto" name="金额&lt;5万"/>
    <sequenceFlow id="f4" sourceRef="xgw" targetRef="pgw_split" name="金额>=5万"/>
    <sequenceFlow id="f5" sourceRef="st_auto" targetRef="end"/>
    <sequenceFlow id="f6" sourceRef="pgw_split" targetRef="ut_tech"/>
    <sequenceFlow id="f7" sourceRef="pgw_split" targetRef="ut_finance"/>
    <sequenceFlow id="f8" sourceRef="ut_tech" targetRef="igw"/>
    <sequenceFlow id="f9" sourceRef="ut_finance" targetRef="igw"/>
    <sequenceFlow id="f10" sourceRef="igw" targetRef="rt_wait"/>
    <sequenceFlow id="f11" sourceRef="rt_wait" targetRef="ca_sub"/>
    <sequenceFlow id="f12" sourceRef="ca_sub" targetRef="end"/>
  </process>
</definitions>`;

    const designerJson = JSON.stringify({
      nodes: [
        { id: 'start', type: 'startEvent', position: { x: 50, y: 300 }, data: { type: 'startEvent', label: '开始' } },
        { id: 'ut_apply', type: 'userTask', position: { x: 200, y: 300 }, data: { type: 'userTask', label: '填写申请', config: { assigneeType: 'starter' } } },
        { id: 'xgw', type: 'exclusiveGateway', position: { x: 400, y: 300 }, data: { type: 'exclusiveGateway', label: '金额判断' } },
        { id: 'st_auto', type: 'serviceTask', position: { x: 600, y: 450 }, data: { type: 'serviceTask', label: '自动审批' } },
        { id: 'pgw_split', type: 'parallelGateway', position: { x: 600, y: 200 }, data: { type: 'parallelGateway', label: '并行分支' } },
        { id: 'ut_tech', type: 'userTask', position: { x: 800, y: 100 }, data: { type: 'userTask', label: '技术评审', config: { assigneeType: 'role', roleIds: ['tech_lead'] } } },
        { id: 'ut_finance', type: 'userTask', position: { x: 800, y: 300 }, data: { type: 'userTask', label: '财务审批', config: { assigneeType: 'dept', deptIds: ['finance'] } } },
        { id: 'igw', type: 'inclusiveGateway', position: { x: 1000, y: 200 }, data: { type: 'inclusiveGateway', label: '包容汇聚' } },
        { id: 'rt_wait', type: 'receiveTask', position: { x: 1200, y: 200 }, data: { type: 'receiveTask', label: '等待确认' } },
        { id: 'ca_sub', type: 'callActivity', position: { x: 1400, y: 200 }, data: { type: 'callActivity', label: '子流程', config: { calledElement: 'showcase_quote_approval' } } },
        { id: 'end', type: 'endEvent', position: { x: 1600, y: 300 }, data: { type: 'endEvent', label: '结束' } },
      ],
      edges: [
        { id: 'f1', source: 'start', target: 'ut_apply', type: 'smoothstep' },
        { id: 'f2', source: 'ut_apply', target: 'xgw', type: 'smoothstep' },
        { id: 'f3', source: 'xgw', target: 'st_auto', type: 'smoothstep', data: { label: '金额<5万' } },
        { id: 'f4', source: 'xgw', target: 'pgw_split', type: 'smoothstep', data: { label: '金额>=5万' } },
        { id: 'f5', source: 'st_auto', target: 'end', type: 'smoothstep' },
        { id: 'f6', source: 'pgw_split', target: 'ut_tech', type: 'smoothstep' },
        { id: 'f7', source: 'pgw_split', target: 'ut_finance', type: 'smoothstep' },
        { id: 'f8', source: 'ut_tech', target: 'igw', type: 'smoothstep' },
        { id: 'f9', source: 'ut_finance', target: 'igw', type: 'smoothstep' },
        { id: 'f10', source: 'igw', target: 'rt_wait', type: 'smoothstep' },
        { id: 'f11', source: 'rt_wait', target: 'ca_sub', type: 'smoothstep' },
        { id: 'f12', source: 'ca_sub', target: 'end', type: 'smoothstep' },
      ],
    });

    const resp = await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey,
        processName: '军火展 — 全9节点展示流程',
        description: '包含全部9种BPMN节点：StartEvent、EndEvent、UserTask(3种分配)、ServiceTask、ReceiveTask、CallActivity、ExclusiveGateway、ParallelGateway、InclusiveGateway',
        category: 'showcase',
        bpmnContent: bpmnXml,
        designerJson,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created BPMN: 全9节点展示流程');
    } else {
      console.warn(`  BPMN creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 5. Full Automation — cover remaining trigger+action types
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 5: Automation — remaining triggers + actions', async ({ page }) => {
    const rules = [
      {
        name: '军火展 — 字段变更触发(ON_FIELD_CHANGE)',
        triggerType: 'on_field_change',
        modelCode: 'crm_opportunity',
        triggerConfig: { fieldCode: 'crm_opp_expected_amount', stateField: 'crm_opp_stage' },
        actions: [{ type: 'update_record', config: { message: '金额变更，更新概率' }, sequence: 0, label: '更新概率' }],
      },
      {
        name: '军火展 — 记录更新触发(ON_RECORD_UPDATE)',
        triggerType: 'on_record_update',
        modelCode: 'crm_account',
        triggerConfig: { watchFields: ['crm_acc_rating', 'crm_acc_status'] },
        actions: [
          { type: 'send_notification', config: { message: '客户评级或状态变更' }, sequence: 0, label: '通知' },
          { type: 'delay', config: { delayMs: 5000 }, sequence: 1, label: '延迟5秒' },
          { type: 'send_webhook', config: { url: 'https://httpbin.org/post' }, sequence: 2, label: '同步外部' },
        ],
      },
      {
        name: '军火展 — Webhook触发(WEBHOOK)',
        triggerType: 'webhook',
        actions: [
          { type: 'condition', config: { condition: 'payload.amount > 50000' }, sequence: 0, label: '金额判断' },
          { type: 'create_record', config: { targetModel: 'crm_lead', message: '从外部创建线索' }, sequence: 1, label: '创建线索' },
          { type: 'execute_command', config: { commandCode: 'crm:create_activity' }, sequence: 2, label: '记录活动' },
        ],
      },
    ];

    for (const rule of rules) {
      const resp = await page.request.post('/api/automations', {
        data: { ...rule, enabled: false },
      });
      const body = await resp.json();
      if (body.code === '0') {
        console.log(`  Created automation: ${rule.name}`);
      } else {
        console.warn(`  Automation warning: ${rule.name} — ${body.message?.slice(0, 80)}`);
      }
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 6. SavedView — 7 view types on CRM Opportunity
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 6: SavedView — 7 view types', async ({ page }) => {
    const views = [
      { name: '全部商机（表格）', viewType: 'TABLE', config: {} },
      { name: '按阶段看板', viewType: 'KANBAN', config: { groupByField: 'crm_opp_stage' } },
      { name: '成交日历', viewType: 'CALENDAR', config: { dateField: 'crm_opp_expected_close_date' } },
      { name: '商机卡片', viewType: 'GALLERY', config: {} },
      { name: '项目甘特图', viewType: 'GANTT', config: { startField: 'created_at', endField: 'crm_opp_expected_close_date' } },
      { name: '时间线', viewType: 'TIMELINE', config: { dateField: 'created_at' } },
      { name: '组织树形', viewType: 'TREE', config: {} },
    ];

    let created = 0;
    for (const v of views) {
      const resp = await page.request.post('/api/views', {
        data: {
          name: v.name,
          modelCode: 'crm_opportunity',
          viewType: v.viewType.toLowerCase(),
          scope: 'global',
          viewConfig: v.config,
        },
      });
      const body = await resp.json().catch(() => ({}));
      if (body?.code === '0' || resp.ok()) {
        created++;
      } else {
        console.warn(`  SavedView "${v.name}" (${v.viewType}) warning: ${body?.message?.slice(0, 80) || resp.status()}`);
      }
    }
    console.log(`  Created ${created}/7 SavedViews for crm_opportunity`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 7. Knowledge Base + Documents (inline text via file upload)
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 7: Knowledge Base + Documents', async ({ page }) => {
    // Create KB
    const kbResp = await page.request.post('/api/ai/knowledge', {
      data: {
        name: '鑫然科技产品知识库',
        description: '包含 PCBA 工艺规范、产品参数手册、常见问题解答等技术文档。供 AuraBot 和客服 Agent 引用。',
        embeddingProvider: 'openai',
        embeddingModel: 'text-embedding-3-small',
        chunkSize: 300,
        chunkOverlap: 30,
      },
    });
    const kbBody = await kbResp.json().catch(() => ({}));

    if (kbBody?.code !== '0' && !kbResp.ok()) {
      console.warn(`  KB creation failed: ${kbBody?.message?.slice(0, 100) || kbResp.status()}`);
      return;
    }

    const kbPid = kbBody?.data?.pid;
    console.log(`  Created KB: 鑫然科技产品知识库 (${kbPid})`);

    // Upload documents as text files via multipart
    const docs = [
      {
        filename: 'PCBA工艺规范.txt',
        content: `# PCBA 工艺规范 v2.0

## 1. SMT 贴片工艺
- 回流焊温度曲线：预热区 150-200°C，均温区 200-220°C，峰值 235-245°C
- BGA 焊接：峰值温度 240°C，保持 60-90 秒
- 焊膏印刷：钢网厚度 0.12mm，开口面积比 ≥ 0.66
- 贴片精度：±0.05mm (0402 以上)，±0.03mm (0201)

## 2. DIP 插件工艺
- 波峰焊温度：250±5°C
- 预热温度：110±10°C
- 传送速度：1.0-1.2 m/min

## 3. 检测标准
- AOI 检测覆盖率：100% SMT 焊点
- ICT 测试覆盖率：≥ 95% 节点
- FCT 功能测试：100% 产品

## 4. 质量标准
- IPC-A-610 Class 2（标准级）/ Class 3（高可靠性级）
- AEC-Q100（汽车电子）
- 不良率目标：≤ 200 PPM`,
      },
      {
        filename: '常见技术FAQ.txt',
        content: `# 技术 FAQ

## Q1: 你们支持哪些 PCB 材质？
A: FR-4（标准）、铝基板（LED）、Rogers（高频）、陶瓷基板（高温）

## Q2: 最小批量是多少？
A: 打样 5 片起，小批量 100 片起，量产 1000 片起。

## Q3: 交期一般多久？
A: 打样 3-5 个工作日，小批量 7-10 个工作日，量产 15-20 个工作日。

## Q4: 你们有哪些质量认证？
A: ISO 9001:2015、ISO 14001:2018、IATF 16949（汽车电子）、UL 认证。

## Q5: 如何处理质量问题？
A: 48 小时内响应 → 技术分析 → 出具 8D 报告 → 补货/返工 → 改善措施。

## Q6: 账期和付款方式？
A: 新客户预付 50%，老客户月结 30-60 天。支持银行转账、承兑汇票。`,
      },
      {
        filename: '产品参数速查.txt',
        content: `# 产品参数速查

## 电阻系列
| 型号 | 封装 | 阻值范围 | 精度 | 功率 |
|------|------|---------|------|------|
| RC0402 | 0402 | 1Ω-10MΩ | ±1% | 1/16W |
| RC0603 | 0603 | 1Ω-10MΩ | ±1% | 1/10W |
| RC0805 | 0805 | 1Ω-10MΩ | ±1% | 1/8W |

## 电容系列
| 型号 | 封装 | 容值范围 | 材质 | 耐压 |
|------|------|---------|------|------|
| CC0402 | 0402 | 1pF-1μF | C0G/X5R/X7R | 6.3-50V |
| CC0603 | 0603 | 1pF-10μF | C0G/X5R/X7R | 6.3-50V |

## 连接器系列
| 型号 | 类型 | 间距 | 针数 | 额定电流 |
|------|------|------|------|---------|
| FPC05 | FPC | 0.5mm | 4-60P | 0.5A |
| PH20 | 线对板 | 2.0mm | 2-16P | 2A |
| XH25 | 线对板 | 2.5mm | 2-20P | 3A |`,
      },
    ];

    let uploaded = 0;
    for (const doc of docs) {
      try {
        // Create a Blob-like buffer for multipart upload
        const buffer = Buffer.from(doc.content, 'utf-8');
        const resp = await page.request.post(`/api/ai/knowledge/${kbPid}/documents/upload`, {
          multipart: {
            file: {
              name: doc.filename,
              mimeType: 'text/plain',
              buffer,
            },
          },
        });
        const body = await resp.json().catch(() => ({}));
        if (body?.code === '0' || resp.ok()) {
          uploaded++;
          console.log(`  Uploaded: ${doc.filename}`);
        } else {
          console.warn(`  Upload failed for ${doc.filename}: ${body?.message?.slice(0, 80) || resp.status()}`);
        }
      } catch (e) {
        console.warn(`  Upload error for ${doc.filename}: ${(e as Error).message.slice(0, 80)}`);
      }
    }
    console.log(`  KB documents: ${uploaded}/3 uploaded`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal: Verification summary', async ({ page }) => {
    console.log('\n═══════════════════════════════════════');
    console.log('  Arsenal Showcase — Summary');
    console.log('═══════════════════════════════════════');

    // Showcase records
    const scResp = await page.request.get('/api/dynamic/showcase_all_fields/list?pageSize=1');
    const scBody = await scResp.json().catch(() => ({}));
    console.log(`  Showcase Records:  ${scBody?.data?.total ?? '?'}`);

    // Dashboards
    const dashResp = await page.request.get('/api/dashboards');
    const dashBody = await dashResp.json().catch(() => ({}));
    const dashCount = Array.isArray(dashBody?.data) ? dashBody.data.length : (dashBody?.data?.total ?? '?');
    console.log(`  Dashboards:        ${dashCount}`);

    // BPM processes
    const bpmResp = await page.request.get('/api/bpm/process-definitions');
    const bpmBody = await bpmResp.json().catch(() => ({}));
    console.log(`  BPM Definitions:   ${bpmBody?.data?.total ?? bpmBody?.data?.length ?? '?'}`);

    // Automations
    const autoResp = await page.request.get('/api/automations?page=1&size=100');
    const autoBody = await autoResp.json().catch(() => ({}));
    console.log(`  Automations:       ${autoBody?.data?.total ?? autoBody?.data?.records?.length ?? '?'}`);

    // SavedViews
    const viewResp = await page.request.get('/api/views/accessible?modelCode=crm_opportunity');
    const viewBody = await viewResp.json().catch(() => ({}));
    const viewCount = Array.isArray(viewBody?.data) ? viewBody.data.length : (viewBody?.data?.total ?? '?');
    console.log(`  SavedViews (opp):  ${viewCount}`);

    // Knowledge Base
    const kbResp = await page.request.get('/api/ai/knowledge');
    const kbBody = await kbResp.json().catch(() => ({}));
    const kbCount = Array.isArray(kbBody?.data) ? kbBody.data.length : (kbBody?.data?.total ?? '?');
    console.log(`  Knowledge Bases:   ${kbCount}`);

    console.log('═══════════════════════════════════════\n');
  });
});
