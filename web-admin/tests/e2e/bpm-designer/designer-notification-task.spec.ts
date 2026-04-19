/**
 * BPM Designer — notification-task coverage
 *
 * Coverage goal: `JsonToBpmnConverter` emits `<serviceTask class="notificationServiceTaskDelegate">`
 * when a node has `type: "notification-task"` with required `config.eventCode` and optional
 * `config.recipientFrom` + `config.templateParamsVars`.
 *
 *   L1: designerJson persists the config
 *   L2: BPMN XML carries smart:class + smart:eventCode + smart:recipientFrom + smart:templateParamsVars
 *   L3: process starts + completes (notification-task is synchronous-compatible;
 *        if the notification dispatch has side-effects that fail, the delegate should
 *        either skip or abort per onFail — here we run in skip-and-warn mode so a
 *        missing downstream notification template does NOT block the flow).
 */

import { test, expect } from '@playwright/test';
import {
  openDesigner,
  addNode,
  connect,
  configureNode,
  saveProcess,
  deployProcess,
} from '../../helpers/designer-dsl';
import { startInstanceAndAdvance } from '../../helpers/bpm-assertions';
import { loginAs } from '../../helpers/wd-fixtures';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:6443';

test.describe('BPM designer — notification-task', { tag: ['@bpm-regression'] }, () => {
  test('configure notification-task with eventCode + templateParamsVars — L1/L2/L3', async ({ page, request }) => {
    const ts = Date.now();
    const processKey = `e2e_designer_notify_${ts}`;
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    await openDesigner(page, { processKey, name: `Notify ${ts}` });

    await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 200 }, label: 'Start' });
    // Note: notification-task isn't in the BPMNNodeType enum yet — the test hook
    // accepts any string, so we pass 'notification-task' verbatim (matches backend
    // BpmServiceTaskConstants.NODE_TYPE_NOTIFICATION_TASK).
    await addNode(page, { id: 'notify_1', type: 'notification-task' as never, position: { x: 260, y: 200 }, label: 'Notify' });
    await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 440, y: 200 }, label: 'End' });

    await configureNode(page, 'notify_1', {
      config: {
        eventCode: 'wd_request_approved',
        recipientFrom: 'applicant',
        templateParamsVars: 'businessKey,days',
        onFail: 'skip_and_warn',
      },
    });

    await connect(page, { from: 'start_1', to: 'notify_1' });
    await connect(page, { from: 'notify_1', to: 'end_1' });

    const { processDefinitionId } = await saveProcess(page);
    await deployProcess(page, processDefinitionId);

    // L1
    const pdResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(pdResp.ok(), `GET PD failed: ${pdResp.status()}`).toBe(true);
    const pdBody = (await pdResp.json()) as Record<string, unknown>;
    const pdData = pdBody.data as Record<string, unknown>;
    const dj = JSON.parse(pdData.designerJson as string) as Record<string, unknown>;
    const nodes = dj.nodes as Array<Record<string, unknown>>;
    const notifyNode = nodes.find((n) => n.id === 'notify_1');
    expect(notifyNode, 'notify_1 must exist').toBeDefined();
    const nData = notifyNode!.data as Record<string, unknown>;
    const nConfig = nData.config as Record<string, unknown> | undefined;
    expect(nConfig, 'notify_1 must have config').toBeDefined();
    expect(nConfig!.eventCode).toBe('wd_request_approved');
    expect(nConfig!.recipientFrom).toBe('applicant');
    expect(nConfig!.templateParamsVars).toBe('businessKey,days');

    // L2
    const xmlResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}/bpmn`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(xmlResp.ok()).toBe(true);
    const xml = await xmlResp.text();
    const tagMatch = xml.match(
      /<serviceTask[^>]*id=["']notify_1["'][^>]*(?:\/>|>[\s\S]*?<\/serviceTask>)/,
    );
    expect(tagMatch, 'notify_1 serviceTask tag must exist').not.toBeNull();
    const tag = tagMatch![0];
    expect(tag).toContain('notificationServiceTaskDelegate');
    expect(tag).toContain('eventCode="wd_request_approved"');
    expect(tag).toContain('recipientFrom="applicant"');
    expect(tag).toContain('templateParamsVars="businessKey,days"');

    // L3 — auto-completes (no user tasks)
    const { finalStatus } = await startInstanceAndAdvance(
      request,
      adminToken,
      processDefinitionId,
      { businessKey: `e2e_${ts}`, days: 3 },
      [],
    );
    expect(finalStatus).toBe('completed');
  });
});
