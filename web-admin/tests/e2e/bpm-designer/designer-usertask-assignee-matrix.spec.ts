/**
 * BPM Designer — userTask assigneeType matrix
 *
 * Coverage goal: verify the `JsonToBpmnConverter.writeUserTaskAssigneeAttributes`
 * handles all 5 designer assignee types:
 *
 *   - user       → smart:assigneeType="user"       + smart:assigneeId="<userId>"
 *   - role       → smart:assigneeType="role"       + smart:assigneeId="<roleId>"
 *   - dept       → smart:assigneeType="dept"       + smart:assigneeId="<deptId>"
 *   - expression → smart:assignee="<expr>"
 *   - starter    → smart:assigneeType="starter"    (no id needed)
 *
 * For each case:
 *   L1: designerJson persists the configured `config.assignee` object verbatim
 *   L2: emitted BPMN XML contains the expected smart:* attributes on the userTask
 *
 * Notes:
 *   - L3 runtime (actual assignee resolution to a real user/group) is out of scope
 *     for this matrix — the converter contract is what's being validated.
 *   - Uses the window.__bpmDesigner test hook (installed in dev mode only).
 *   - The node type 'userTask' matches BPMNNodeType.USER_TASK.
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

interface MatrixCase {
  label: string;
  assignee: {
    type: 'user' | 'role' | 'dept' | 'expression' | 'starter';
    userIds?: string[];
    roleIds?: string[];
    deptIds?: string[];
    expression?: string;
  };
  // What must appear in the BPMN XML userTask tag for this case
  expectedXmlContains: string[];
  // What must NOT appear
  expectedXmlAbsent?: string[];
}

const CASES: MatrixCase[] = [
  {
    label: 'user',
    assignee: { type: 'user', userIds: ['01TEST_USER_ID'] },
    expectedXmlContains: ['assigneeType="user"', 'assigneeId="01TEST_USER_ID"'],
  },
  {
    label: 'role',
    assignee: { type: 'role', roleIds: ['wd_manager'] },
    expectedXmlContains: ['assigneeType="role"', 'assigneeId="wd_manager"'],
  },
  {
    label: 'dept',
    assignee: { type: 'dept', deptIds: ['01TEST_DEPT_ID'] },
    expectedXmlContains: ['assigneeType="dept"', 'assigneeId="01TEST_DEPT_ID"'],
  },
  {
    label: 'expression',
    assignee: { type: 'expression', expression: '${startUserId}' },
    expectedXmlContains: ['assignee="${startUserId}"'],
    // expression uses smart:assignee (not smart:assigneeType)
    expectedXmlAbsent: ['assigneeType='],
  },
  {
    label: 'starter',
    assignee: { type: 'starter' },
    expectedXmlContains: ['assigneeType="starter"'],
    // starter does not need an assigneeId
    expectedXmlAbsent: ['assigneeId='],
  },
];

test.describe('BPM designer — userTask assigneeType matrix', { tag: ['@bpm-regression'] }, () => {
  for (const c of CASES) {
    test(`assigneeType=${c.label} — L1 designerJson + L2 BPMN XML`, async ({ page, request }) => {
      const ts = Date.now();
      const processKey = `e2e_designer_assignee_${c.label}_${ts}`;
      const taskId = 'task_approve';

      const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

      // Build the flow: start → userTask(configured) → end
      await openDesigner(page, { processKey, name: `Assignee ${c.label} ${ts}` });

      await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 200 }, label: 'Start' });
      await addNode(page, { id: taskId, type: 'userTask', position: { x: 260, y: 200 }, label: 'Approve' });
      await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 440, y: 200 }, label: 'End' });

      // Configure the userTask — place assignee under config (converter contract)
      await configureNode(page, taskId, {
        config: { assignee: c.assignee },
      });

      await connect(page, { from: 'start_1', to: taskId });
      await connect(page, { from: taskId, to: 'end_1' });

      const { processDefinitionId } = await saveProcess(page);
      await deployProcess(page, processDefinitionId);

      // L1 — designerJson persists the assignee shape verbatim
      const pdResp = await request.get(
        `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(pdResp.ok(), `GET PD failed: ${pdResp.status()}`).toBe(true);
      const pdBody = (await pdResp.json()) as Record<string, unknown>;
      const pdData = pdBody.data as Record<string, unknown>;
      const designerJsonStr = pdData.designerJson as string;
      const dj = JSON.parse(designerJsonStr) as Record<string, unknown>;
      const nodes = dj.nodes as Array<Record<string, unknown>>;
      const taskNode = nodes.find((n) => n.id === taskId);
      expect(taskNode, 'task node must exist in designerJson').toBeDefined();

      const taskData = taskNode!.data as Record<string, unknown>;
      const taskConfig = taskData.config as Record<string, unknown> | undefined;
      expect(taskConfig, 'task node must carry a config object').toBeDefined();
      expect(taskConfig!.assignee, 'assignee must persist under config').toEqual(c.assignee);

      // L2 — BPMN XML carries the expected smart:* attributes on the userTask
      const xmlResp = await request.get(
        `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}/bpmn`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(xmlResp.ok(), `GET bpmn failed: ${xmlResp.status()}`).toBe(true);
      const xmlBody = (await xmlResp.json()) as Record<string, unknown>;
    const xml = xmlBody.data as string;

      // Narrow to the userTask tag (self-closing OR opening form)
      const tagMatch = xml.match(
        new RegExp(
          `<userTask[^>]*id=["']${taskId}["'][^>]*(?:/>|>[\\s\\S]*?<\\/userTask>)`,
        ),
      );
      expect(tagMatch, `userTask ${taskId} must exist in BPMN XML`).not.toBeNull();
      const tag = tagMatch![0];

      for (const expected of c.expectedXmlContains) {
        expect(tag, `userTask tag must contain "${expected}"\nActual: ${tag}`).toContain(expected);
      }
      for (const absent of c.expectedXmlAbsent ?? []) {
        expect(tag, `userTask tag must NOT contain "${absent}"\nActual: ${tag}`).not.toContain(absent);
      }
    });
  }
});
