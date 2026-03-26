/**
 * Showcase Demo Data — Workflow, Automation, Webhook, Notification
 *
 * Creates:
 * - 2 BPMN process definitions (quote approval + leave request)
 * - 3 automation rules (lead assignment + opportunity notification + daily digest)
 * - 1 webhook subscription (opportunity stage change)
 * - 2 notification rules (opportunity change + approval completion)
 *
 * Run AFTER seed-showcase-data.spec.ts:
 *   npx playwright test tests/api/setup/seed-showcase-workflow.spec.ts
 */

import { test, expect } from '@playwright/test';

test.describe.serial('Showcase Seed — Workflow & Automation', () => {
  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(120_000);

  // ═════════════════════════════════════════════════════════════════════════
  // BPMN Process Definitions
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase W1: BPMN — Quote Approval Process', async ({ page }) => {
    const processKey = 'showcase_quote_approval';
    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm"
             id="def_${processKey}">
  <process id="${processKey}" name="报价审批流程" isExecutable="true">
    <startEvent id="start" name="提交报价"/>
    <userTask id="manager_review" name="销售总监审批"/>
    <exclusiveGateway id="amount_check" name="金额判断"/>
    <userTask id="gm_review" name="总经理审批"/>
    <serviceTask id="send_notification" name="发送通知"/>
    <endEvent id="end" name="审批完成"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="manager_review"/>
    <sequenceFlow id="f2" sourceRef="manager_review" targetRef="amount_check"/>
    <sequenceFlow id="f3" sourceRef="amount_check" targetRef="gm_review" name="金额>10万"/>
    <sequenceFlow id="f4" sourceRef="amount_check" targetRef="send_notification" name="金额<=10万"/>
    <sequenceFlow id="f5" sourceRef="gm_review" targetRef="send_notification"/>
    <sequenceFlow id="f6" sourceRef="send_notification" targetRef="end"/>
  </process>
</definitions>`;

    const designerJson = JSON.stringify({
      nodes: [
        { id: 'start', type: 'startEvent', position: { x: 100, y: 200 }, data: { type: 'startEvent', label: '提交报价' } },
        { id: 'manager_review', type: 'userTask', position: { x: 300, y: 200 }, data: { type: 'userTask', label: '销售总监审批', config: { assigneeType: 'role', roleIds: ['sales_director'] } } },
        { id: 'amount_check', type: 'exclusiveGateway', position: { x: 500, y: 200 }, data: { type: 'exclusiveGateway', label: '金额判断' } },
        { id: 'gm_review', type: 'userTask', position: { x: 700, y: 100 }, data: { type: 'userTask', label: '总经理审批', config: { assigneeType: 'role', roleIds: ['general_manager'] } } },
        { id: 'send_notification', type: 'serviceTask', position: { x: 700, y: 300 }, data: { type: 'serviceTask', label: '发送通知' } },
        { id: 'end', type: 'endEvent', position: { x: 900, y: 200 }, data: { type: 'endEvent', label: '审批完成' } },
      ],
      edges: [
        { id: 'f1', source: 'start', target: 'manager_review', type: 'smoothstep' },
        { id: 'f2', source: 'manager_review', target: 'amount_check', type: 'smoothstep' },
        { id: 'f3', source: 'amount_check', target: 'gm_review', type: 'smoothstep', data: { label: '金额>10万' } },
        { id: 'f4', source: 'amount_check', target: 'send_notification', type: 'smoothstep', data: { label: '金额<=10万' } },
        { id: 'f5', source: 'gm_review', target: 'send_notification', type: 'smoothstep' },
        { id: 'f6', source: 'send_notification', target: 'end', type: 'smoothstep' },
      ],
    });

    const resp = await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey,
        processName: '报价审批流程',
        description: '报价金额10万以下销售总监直接审批，10万以上需总经理二次审批。审批完成后自动发送通知。',
        category: 'approval',
        bpmnContent: bpmnXml,
        designerJson,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created BPMN: 报价审批流程');
    } else {
      console.warn(`  BPMN creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }

    // Deploy the process
    const pid = body.data?.pid || body.data?.id;
    if (pid) {
      await page.request.post(`/api/bpm/process-definitions/${pid}/deploy`).catch(() => {
        console.warn('  Deploy failed (may need manual deploy)');
      });
    }
  });

  test('Phase W1: BPMN — Leave Request Process (with parallel gateway)', async ({ page }) => {
    const processKey = 'showcase_leave_request';
    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm"
             id="def_${processKey}">
  <process id="${processKey}" name="请假审批流程" isExecutable="true">
    <startEvent id="start" name="提交请假"/>
    <userTask id="direct_manager" name="直属主管审批"/>
    <parallelGateway id="parallel_split" name="并行通知"/>
    <userTask id="hr_review" name="人事备案"/>
    <serviceTask id="calendar_update" name="更新日历"/>
    <parallelGateway id="parallel_join" name="合并"/>
    <endEvent id="end" name="审批完成"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="direct_manager"/>
    <sequenceFlow id="f2" sourceRef="direct_manager" targetRef="parallel_split"/>
    <sequenceFlow id="f3" sourceRef="parallel_split" targetRef="hr_review"/>
    <sequenceFlow id="f4" sourceRef="parallel_split" targetRef="calendar_update"/>
    <sequenceFlow id="f5" sourceRef="hr_review" targetRef="parallel_join"/>
    <sequenceFlow id="f6" sourceRef="calendar_update" targetRef="parallel_join"/>
    <sequenceFlow id="f7" sourceRef="parallel_join" targetRef="end"/>
  </process>
</definitions>`;

    const designerJson = JSON.stringify({
      nodes: [
        { id: 'start', type: 'startEvent', position: { x: 100, y: 200 }, data: { type: 'startEvent', label: '提交请假' } },
        { id: 'direct_manager', type: 'userTask', position: { x: 300, y: 200 }, data: { type: 'userTask', label: '直属主管审批', config: { assigneeType: 'expression', expression: '${report_to}' } } },
        { id: 'parallel_split', type: 'parallelGateway', position: { x: 500, y: 200 }, data: { type: 'parallelGateway', label: '并行通知' } },
        { id: 'hr_review', type: 'userTask', position: { x: 700, y: 100 }, data: { type: 'userTask', label: '人事备案', config: { assigneeType: 'role', roleIds: ['hr'] } } },
        { id: 'calendar_update', type: 'serviceTask', position: { x: 700, y: 300 }, data: { type: 'serviceTask', label: '更新日历' } },
        { id: 'parallel_join', type: 'parallelGateway', position: { x: 900, y: 200 }, data: { type: 'parallelGateway', label: '合并' } },
        { id: 'end', type: 'endEvent', position: { x: 1100, y: 200 }, data: { type: 'endEvent', label: '审批完成' } },
      ],
      edges: [
        { id: 'f1', source: 'start', target: 'direct_manager', type: 'smoothstep' },
        { id: 'f2', source: 'direct_manager', target: 'parallel_split', type: 'smoothstep' },
        { id: 'f3', source: 'parallel_split', target: 'hr_review', type: 'smoothstep' },
        { id: 'f4', source: 'parallel_split', target: 'calendar_update', type: 'smoothstep' },
        { id: 'f5', source: 'hr_review', target: 'parallel_join', type: 'smoothstep' },
        { id: 'f6', source: 'calendar_update', target: 'parallel_join', type: 'smoothstep' },
        { id: 'f7', source: 'parallel_join', target: 'end', type: 'smoothstep' },
      ],
    });

    const resp = await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey,
        processName: '请假审批流程',
        description: '员工提交请假申请，直属主管审批通过后，人事备案和日历更新并行执行。展示并行网关能力。',
        category: 'hr',
        bpmnContent: bpmnXml,
        designerJson,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created BPMN: 请假审批流程 (with parallel gateway)');
    } else {
      console.warn(`  BPMN creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Automation Rules
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase W2: Automation — Lead Auto-Assignment', async ({ page }) => {
    const resp = await page.request.post('/api/automations', {
      data: {
        name: '新线索自动分配给销售',
        description: '新线索创建后，根据行业自动分配给对应销售代表，并发送站内通知。',
        triggerType: 'on_record_create',
        modelCode: 'crm_lead',
        actions: [
          {
            type: 'send_notification',
            config: { message: '您有一条新线索需要跟进：${record.crm_lead_company}' },
            sequence: 0,
            label: '通知销售',
          },
        ],
        enabled: true,
      },
    });
    const body = await resp.json();
    if (body.code === '0') { console.log('  Created automation: 新线索自动分配'); }
    else { console.warn(`  Automation creation warning: ${body.message?.slice(0, 100)}`); }
  });

  test('Phase W2: Automation — Opportunity Stage Notification', async ({ page }) => {
    const resp = await page.request.post('/api/automations', {
      data: {
        name: '商机阶段变更通知团队',
        description: '商机阶段发生变化时，自动通知销售总监和相关团队成员。',
        triggerType: 'on_state_change',
        modelCode: 'crm_opportunity',
        triggerConfig: {
          stateField: 'crm_opp_stage',
          fromStates: ['discovery', 'qualification', 'proposal', 'negotiation'],
          toStates: ['qualification', 'proposal', 'negotiation', 'closed_won', 'closed_lost'],
        },
        actions: [
          {
            type: 'send_notification',
            config: { message: '商机"${record.crm_opp_name}"阶段变更为 ${record.crm_opp_stage}' },
            sequence: 0,
            label: '通知团队',
          },
        ],
        enabled: true,
      },
    });
    const body = await resp.json();
    if (body.code === '0') { console.log('  Created automation: 商机阶段变更通知'); }
    else { console.warn(`  Automation creation warning: ${body.message?.slice(0, 100)}`); }
  });

  test('Phase W2: Automation — Daily Digest (Scheduled)', async ({ page }) => {
    const resp = await page.request.post('/api/automations', {
      data: {
        name: '每日销售数据摘要',
        description: '每天早上9点自动统计前一天的销售数据（新线索、新商机、赢单），发送摘要通知给销售总监。',
        triggerType: 'scheduled',
        triggerConfig: {
          cron: '0 9 * * MON-FRI',
          timezone: 'Asia/Shanghai',
        },
        actions: [
          {
            type: 'send_notification',
            config: { message: '昨日销售数据：新线索 ${stats.new_leads} 条，新商机 ${stats.new_opps} 个，赢单 ${stats.won_amount} 万元' },
            sequence: 0,
            label: '发送摘要',
          },
        ],
        enabled: false, // disabled by default to avoid noise in demo
      },
    });
    const body = await resp.json();
    if (body.code === '0') {
      console.log('  Created automation: 每日销售摘要 (scheduled, disabled)');
    } else {
      console.warn(`  Scheduled automation creation failed (may not be supported in this config): ${body.message?.slice(0, 80)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Webhook Subscription
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase W3: Webhook — Opportunity Stage Change', async ({ page }) => {
    const resp = await page.request.post('/api/webhooks', {
      data: {
        name: '商机阶段变更外部通知',
        targetUrl: 'https://httpbin.org/post',
        eventType: 'record_updated',
        modelCode: 'crm_opportunity',
        filterExpression: "crm_opp_stage != null",
        secret: 'showcase-webhook-secret-2025',
        headers: JSON.stringify({ 'X-Source': 'auraboot-showcase' }),
        maxRetries: 3,
        timeoutMs: 10000,
        enabled: true,
      },
    });
    const body = await resp.json();
    if (body.code === '0') { console.log('  Created webhook: 商机阶段变更外部通知'); }
    else { console.warn(`  Webhook creation warning: ${body.message?.slice(0, 100)}`); }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Notification Rules
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase W4: Notification — Opportunity Owner Alert', async ({ page }) => {
    const resp = await page.request.post('/api/notification-rules', {
      data: {
        code: 'showcase_opp_change',
        name: '商机变更通知负责人',
        description: '当商机信息更新时，自动通知商机负责人。',
        enabled: true,
        triggerType: 'EVENT',
        triggerConfig: JSON.stringify({
          eventTypes: ['record_updated'],
          modelCode: 'crm_opportunity',
        }),
        actionChannel: 'IN_APP',
        recipientType: 'RECORD_OWNER',
      },
    });
    const body = await resp.json();
    // Allow failure gracefully if notification rule API differs
    if (body.code === '0') {
      console.log('  Created notification rule: 商机变更通知');
    } else {
      console.warn('  Notification rule creation returned:', body.code, body.message?.slice(0, 100));
    }
  });

  test('Phase W4: Notification — Approval Completion', async ({ page }) => {
    const resp = await page.request.post('/api/notification-rules', {
      data: {
        code: 'showcase_approval_done',
        name: '审批完成通知发起人',
        description: '审批流程完成后，自动通知流程发起人审批结果。',
        enabled: true,
        triggerType: 'EVENT',
        triggerConfig: JSON.stringify({
          eventTypes: ['record_updated'],
          modelCode: 'crm_quote',
        }),
        actionChannel: 'IN_APP',
        recipientType: 'OPERATOR',
      },
    });
    const body = await resp.json();
    if (body.code === '0') {
      console.log('  Created notification rule: 审批完成通知');
    } else {
      console.warn('  Notification rule creation returned:', body.code, body.message?.slice(0, 100));
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Verification: Workflow seed summary', async ({ page }) => {
    console.log('\n═══════════════════════════════════════');
    console.log('  Workflow Seed — Summary');
    console.log('═══════════════════════════════════════');

    // Check BPM processes
    const bpmResp = await page.request.get('/api/bpm/process-definitions?category=approval');
    const bpmBody = await bpmResp.json().catch(() => ({}));
    console.log(`  BPM Definitions:  ${bpmBody?.data?.total ?? bpmBody?.data?.length ?? '?'}`);

    // Check automations
    const autoResp = await page.request.get('/api/automations?page=1&size=100');
    const autoBody = await autoResp.json().catch(() => ({}));
    console.log(`  Automations:      ${autoBody?.data?.total ?? autoBody?.data?.records?.length ?? '?'}`);

    // Check webhooks
    const whResp = await page.request.get('/api/webhooks');
    const whBody = await whResp.json().catch(() => ({}));
    const whCount = Array.isArray(whBody?.data) ? whBody.data.length : (whBody?.data?.total ?? '?');
    console.log(`  Webhooks:         ${whCount}`);

    console.log('═══════════════════════════════════════\n');
  });
});
