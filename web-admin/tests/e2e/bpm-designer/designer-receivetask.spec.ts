/**
 * BPM Designer — receiveTask coverage
 *
 * Coverage goal: `JsonToBpmnConverter.writeReceiveTask` emits
 *   <receiveTask id="..." name="..." />
 *
 * The receiveTask converter currently writes only id + name (no extension attrs).
 * This spec validates that a flow with a receiveTask:
 *   1. persists the node under designerJson (L1)
 *   2. emits <receiveTask> in BPMN XML (L2)
 *
 * L3 runtime (actually sending a signal/message to unblock) is out of scope —
 * it requires a separate SignalCatchService + runtime correlation key infrastructure.
 */

import { test, expect } from '@playwright/test';
import {
  openDesigner,
  addNode,
  connect,
  saveProcess,
  deployProcess,
} from '../../helpers/designer-dsl';
import { loginAs } from '../../helpers/wd-fixtures';

const BACKEND = process.env.BACKEND_URL ?? `http://localhost:${process.env.BE_PORT ?? '6443'}`;

test.describe('BPM designer — receiveTask', { tag: ['@bpm-regression'] }, () => {
  test('flow with receiveTask — L1 designerJson + L2 BPMN XML', async ({ page, request }) => {
    const ts = Date.now();
    const processKey = `e2e_designer_recv_${ts}`;
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    await openDesigner(page, { processKey, name: `Receive ${ts}` });

    await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 200 }, label: 'Start' });
    // receiveTask is BPMNNodeType.RECEIVE_TASK = "receiveTask"
    await addNode(page, { id: 'recv_1', type: 'receiveTask' as never, position: { x: 260, y: 200 }, label: 'Wait Message' });
    await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 440, y: 200 }, label: 'End' });

    await connect(page, { from: 'start_1', to: 'recv_1' });
    await connect(page, { from: 'recv_1', to: 'end_1' });

    const { processDefinitionId } = await saveProcess(page);
    await deployProcess(page, processDefinitionId);

    // L1
    const pdResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(pdResp.ok()).toBe(true);
    const pdBody = (await pdResp.json()) as Record<string, unknown>;
    const pdData = pdBody.data as Record<string, unknown>;
    const dj = JSON.parse(pdData.designerJson as string) as Record<string, unknown>;
    const nodes = dj.nodes as Array<Record<string, unknown>>;
    const recvNode = nodes.find((n) => n.id === 'recv_1');
    expect(recvNode, 'recv_1 must exist in designerJson').toBeDefined();
    expect(recvNode!.type).toBe('receiveTask');

    // L2
    const xmlResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}/bpmn`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(xmlResp.ok()).toBe(true);
    const xmlBody = (await xmlResp.json()) as Record<string, unknown>;
    const xml = xmlBody.data as string;
    expect(xml).toMatch(/<receiveTask[^>]*id=["']recv_1["']/);
    expect(xml).toMatch(/<receiveTask[^>]*name=["']Wait Message["']/);
  });
});
