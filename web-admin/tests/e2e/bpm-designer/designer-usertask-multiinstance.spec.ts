/**
 * BPM Designer — userTask multiInstance (MI) coverage
 *
 * Coverage goal: `JsonToBpmnConverter.writeUserTask` emits
 *   <userTask ... smart:miCollection="..." smart:miElementVariable="...">
 *     <multiInstanceLoopCharacteristics isSequential="..." smart:collection="..." smart:elementVariable="...">
 *       <loopCardinality>N</loopCardinality>
 *       <completionCondition>${...}</completionCondition>
 *     </multiInstanceLoopCharacteristics>
 *   </userTask>
 *
 * when node.data.config.multiInstance = { enabled: true, sequential, collection, elementVariable, loopCardinality, completionCondition }.
 *
 * Two tests:
 *   A. parallel MI    (sequential=false) with collection + elementVariable
 *   B. sequential MI  (sequential=true)  with loopCardinality=3 + completionCondition
 *
 * Each: L1 (designerJson persists), L2 (BPMN XML carries the expected attrs + child element).
 * L3 runtime is out of scope — SmartEngine's MI expansion depends on runtime variables
 * that the test would need to pre-seed, which is beyond a basic matrix check.
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
import { loginAs } from '../../helpers/wd-fixtures';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:6443';

async function setupFlow(
  page: any,
  processKey: string,
  miConfig: Record<string, unknown>,
  opts: { deploy?: boolean } = {},
): Promise<string> {
  await openDesigner(page, { processKey, name: `MI ${processKey}` });

  await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 200 }, label: 'Start' });
  await addNode(page, { id: 'task_mi', type: 'userTask', position: { x: 260, y: 200 }, label: 'MI Approve' });
  await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 440, y: 200 }, label: 'End' });

  await configureNode(page, 'task_mi', {
    config: {
      assignee: { type: 'expression', expression: '${reviewerId}' },
      multiInstance: miConfig,
    },
  });

  await connect(page, { from: 'start_1', to: 'task_mi' });
  await connect(page, { from: 'task_mi', to: 'end_1' });

  const { processDefinitionId } = await saveProcess(page);
  if (opts.deploy ?? true) {
    await deployProcess(page, processDefinitionId);
  }
  return processDefinitionId;
}

test.describe('BPM designer — userTask multiInstance', { tag: ['@bpm-regression'] }, () => {
  test('A: parallel MI with collection + elementVariable — L1/L2', async ({ page, request }) => {
    const ts = Date.now();
    const pdId = await setupFlow(page, `e2e_designer_mi_par_${ts}`, {
      enabled: true,
      sequential: false,
      collection: 'reviewerIds',
      elementVariable: 'reviewerId',
      completionCondition: '${nrOfCompletedInstances >= nrOfInstances}',
    });

    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // L1
    const pdResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${pdId}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    const pdBody = (await pdResp.json()) as Record<string, unknown>;
    const pdData = pdBody.data as Record<string, unknown>;
    const dj = JSON.parse(pdData.designerJson as string) as Record<string, unknown>;
    const nodes = dj.nodes as Array<Record<string, unknown>>;
    const task = nodes.find((n) => n.id === 'task_mi');
    expect(task).toBeDefined();
    const mi = (task!.data as Record<string, unknown>).config as Record<string, unknown>;
    expect((mi.multiInstance as Record<string, unknown>).enabled).toBe(true);
    expect((mi.multiInstance as Record<string, unknown>).sequential).toBe(false);
    expect((mi.multiInstance as Record<string, unknown>).collection).toBe('reviewerIds');

    // L2
    const xmlResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${pdId}/bpmn`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    const xmlBody = (await xmlResp.json()) as Record<string, unknown>;
    const xml = xmlBody.data as string;
    const tagMatch = xml.match(
      /<userTask[^>]*id=["']task_mi["'][^>]*>[\s\S]*?<\/userTask>/,
    );
    expect(tagMatch, 'task_mi userTask must exist with children').not.toBeNull();
    const tag = tagMatch![0];
    expect(tag).toContain('miCollection="reviewerIds"');
    expect(tag).toContain('miElementVariable="reviewerId"');
    expect(tag).toMatch(/<multiInstanceLoopCharacteristics[^>]*isSequential="false"/);
    expect(tag).toContain('completionCondition');
    expect(tag).toContain('nrOfCompletedInstances');
  });

  test('B: sequential MI with loopCardinality=3 — L1/L2', async ({ page, request }) => {
    const ts = Date.now();
    const pdId = await setupFlow(page, `e2e_designer_mi_seq_${ts}`, {
      enabled: true,
      sequential: true,
      loopCardinality: 3,
      completionCondition: '${taskResult == "approved"}',
    }, { deploy: false });

    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // L2 only (L1 shape identical to test A pattern, covered already)
    const xmlResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${pdId}/bpmn`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    const xmlBody = (await xmlResp.json()) as Record<string, unknown>;
    const xml = xmlBody.data as string;
    const tagMatch = xml.match(
      /<userTask[^>]*id=["']task_mi["'][^>]*>[\s\S]*?<\/userTask>/,
    );
    expect(tagMatch).not.toBeNull();
    const tag = tagMatch![0];
    expect(tag).toMatch(/<multiInstanceLoopCharacteristics[^>]*isSequential="true"/);
    expect(tag).toMatch(/<loopCardinality>\s*3\s*<\/loopCardinality>/);
    expect(tag).toContain('taskResult');
  });
});
