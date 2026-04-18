/**
 * D3 — designer: serviceTask bound to an Aura Command
 *
 * Covers:
 *   - serviceTask node configured with serviceType=command + commandCode
 *   - Three-layer assertions: L1 designerJson, L2 BPMN XML, L3 runtime
 *
 * Flow: start_1 → svc_1 (serviceTask/command) → end_1
 *
 * Command used: wd:submit_leave_request — an existing state_transition command
 *   in the workflow-demo plugin. No new command is added; the test uses the
 *   _chain_nodes.svc_1.onFail = 'skip_and_warn' to tolerate the expected
 *   runtime failure (no valid recordId is provided) and still verify that the
 *   process reaches the 'completed' status without hanging on a user task.
 *
 * L1 (designerJson):
 *   - Nodes: start_1, svc_1, end_1
 *   - Edges: start_1→svc_1, svc_1→end_1
 *   - svc_1.data.serviceType === 'command'
 *   - svc_1.data.commandCode === 'wd:submit_leave_request'
 *
 * L2 (BPMN XML):
 *   - serviceTask element with id="svc_1" present
 *   - smart:class="commandServiceTaskDelegate" attribute on svc_1
 *     (emitted by JsonToBpmnConverter.writeServiceTask for serviceType=command)
 *
 * L3 (runtime):
 *   - Start instance with _chain_nodes.svc_1 configured (commandCode + onFail=skip_and_warn)
 *   - No userTasks → instance should auto-complete
 *   - finalStatus === 'completed'
 *
 * CONCERN: L3 variable readout — there is no GET /api/bpm/process-instances/{id}/variables
 *   endpoint (verified by grepping ProcessInstanceController.java). L3 can only assert
 *   finalStatus. The skip_and_warn failure means _step_svc_1_skipped=true is written to
 *   process vars internally, but is not readable from the API. Fallback (ii) is used.
 *
 * CONCERN: CommandServiceTaskDelegate requires _chain_nodes in start variables.
 *   This is a deliberate design: the delegate is intended for CommandChain pipelines.
 *   We pass _chain_nodes manually to confirm the BPMN-to-runtime wiring is correct.
 *
 * Red lines honoured:
 *   - page.goto only for /login
 *   - No waitForTimeout
 *   - No afterAll
 *   - Network waits ≤ 15 s, UI assertions ≤ 5 s
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
import {
  assertDesignerJson,
  assertBpmnXml,
} from '../../helpers/bpm-assertions';
import { loginAs } from '../../helpers/wd-fixtures';

const BACKEND = 'http://localhost:6443';

test.describe('D3 — designer: serviceTask + command binding', () => {
  test('configure serviceTask with commandCode, assert L1/L2/L3 runtime completion', async ({
    page,
    request,
  }) => {
    const processKey = `e2e_designer_svc_${Date.now()}`;

    // -------------------------------------------------------------------------
    // UI login
    // -------------------------------------------------------------------------
    await page.goto('/login');
    await page.getByLabel(/email|邮箱/i).fill('admin@example.com');
    await page.getByLabel(/password|密码/i).fill('Test2026x');
    await page.getByRole('button', { name: /login|登录|sign in/i }).click();
    await page.waitForURL(/\/(dashboard|home|p\/|dashboards)/, { timeout: 15_000 });

    // API token for backend assertions
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // -------------------------------------------------------------------------
    // Build the minimal serviceTask flow: start → svc → end
    // -------------------------------------------------------------------------
    await openDesigner(page, { processKey, name: `D3 SvcTask ${processKey}` });

    await addNode(page, {
      id: 'start_1',
      type: 'startEvent',
      position: { x: 80, y: 200 },
      label: 'Start',
    });
    await addNode(page, {
      id: 'svc_1',
      type: 'serviceTask',
      position: { x: 280, y: 200 },
      label: 'Echo Command',
    });
    await addNode(page, {
      id: 'end_1',
      type: 'endEvent',
      position: { x: 480, y: 200 },
      label: 'End',
    });

    // Configure the serviceTask: serviceType=command, commandCode=wd:submit_leave_request
    // The configureNode call merges the patch into node.data via store.updateNode()
    // ServiceTaskConfig fields: serviceType, commandCode (see types/index.ts:77-90)
    await configureNode(page, 'svc_1', {
      serviceType: 'command',
      commandCode: 'wd:submit_leave_request',
    });

    // Connect nodes
    await connect(page, { from: 'start_1', to: 'svc_1' });
    await connect(page, { from: 'svc_1', to: 'end_1' });

    // -------------------------------------------------------------------------
    // Save and deploy
    // -------------------------------------------------------------------------
    const { processDefinitionId: pdId } = await saveProcess(page);
    await deployProcess(page, pdId);

    // -------------------------------------------------------------------------
    // L1 — Designer JSON: node ids + edges present + serviceTask config stored
    // -------------------------------------------------------------------------
    await assertDesignerJson(request, adminToken, pdId, {
      nodeIds: ['start_1', 'svc_1', 'end_1'],
      edgeSpecs: [
        { from: 'start_1', to: 'svc_1' },
        { from: 'svc_1', to: 'end_1' },
      ],
    });

    // L1 extended — verify serviceTask node data stored in designerJson
    const pdResp = await request.get(`${BACKEND}/api/bpm/process-definitions/${pdId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(pdResp.ok(), `GET /api/bpm/process-definitions/${pdId} failed: ${pdResp.status()}`).toBe(true);

    const pdBody = (await pdResp.json()) as Record<string, unknown>;
    const pdData = pdBody.data as Record<string, unknown>;
    const rawDesignerJson = pdData.designerJson as string;
    expect(typeof rawDesignerJson, 'designerJson must be a string').toBe('string');

    const dj = JSON.parse(rawDesignerJson) as Record<string, unknown>;
    const nodes = (dj.nodes ?? []) as Array<Record<string, unknown>>;
    const svcNode = nodes.find((n) => n.id === 'svc_1');
    expect(svcNode, 'svc_1 node must exist in designerJson').toBeDefined();

    const svcData = svcNode!.data as Record<string, unknown>;
    expect(svcData, 'svc_1 node must have data').toBeDefined();

    // configureNode patches top-level node.data (store.updateNode merges at data level)
    expect(svcData.serviceType, 'serviceType must be command').toBe('command');
    expect(svcData.commandCode, 'commandCode must be wd:submit_leave_request').toBe(
      'wd:submit_leave_request',
    );

    // -------------------------------------------------------------------------
    // L2 — BPMN XML: serviceTask element present + smart:class attribute
    //
    // JsonToBpmnConverter.writeServiceTask() (line 820):
    //   when serviceType === 'command':
    //     writer.writeAttribute(SMART_NAMESPACE, "class", "commandServiceTaskDelegate")
    // SMART_NAMESPACE prefix is "smart" → emitted as smart:class="commandServiceTaskDelegate"
    // -------------------------------------------------------------------------
    await assertBpmnXml(request, adminToken, pdId, {
      hasFlowElement: ['svc_1'],
    });

    // Additional raw XML assertion for smart:class="commandServiceTaskDelegate"
    const xmlResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${pdId}/bpmn`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(xmlResp.ok(), `GET .../bpmn failed: ${xmlResp.status()}`).toBe(true);

    const xmlBody = (await xmlResp.json()) as Record<string, unknown>;
    const xml = xmlBody.data as string;
    expect(typeof xml, 'BPMN XML must be a string').toBe('string');

    // The serviceTask element with id="svc_1" must carry smart:class="commandServiceTaskDelegate"
    // Pattern: match the opening tag of the serviceTask to find the class attribute
    const svcTagPattern = /serviceTask[^>]*id=["']svc_1["'][^>]*/;
    const svcTagMatch = xml.match(svcTagPattern);
    expect(
      svcTagMatch,
      'serviceTask id="svc_1" element must be present in BPMN XML',
    ).not.toBeNull();

    // Verify the command delegate class is set on the serviceTask
    // (either as smart:class= or as class=, depending on namespace prefix binding)
    const svcTag = svcTagMatch![0];
    expect(
      svcTag.includes('commandServiceTaskDelegate'),
      `serviceTask svc_1 must carry class="commandServiceTaskDelegate" attribute. ` +
        `Actual tag: ${svcTag}`,
    ).toBe(true);

    // -------------------------------------------------------------------------
    // L3 — Runtime: start instance with _chain_nodes configured for svc_1
    //
    // CommandServiceTaskDelegate reads _chain_nodes from process variables.
    // We pass it in start vars so the delegate can resolve commandCode.
    // onFail='skip_and_warn' ensures the instance completes even if the
    // command fails (no valid recordId is provided — the command will throw,
    // the delegate will warn and mark the step as skipped, then flow continues).
    //
    // FALLBACK (ii): No GET /api/bpm/process-instances/{id}/variables endpoint.
    // L3 asserts finalStatus='completed' only — this proves the serviceTask
    // executed (or was skipped gracefully) and the flow reached the end event.
    // -------------------------------------------------------------------------
    const startResp = await request.post(`${BACKEND}/api/bpm/process-instances`, {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
      },
      data: {
        processDefinitionId: pdId,
        variables: {
          // Feed the delegate its required _chain_nodes config
          _chain_nodes: {
            svc_1: {
              commandCode: 'wd:submit_leave_request',
              operationType: 'state_transition',
              onFail: 'skip_and_warn',
              params: {},
            },
          },
          // Input variable for potential future echo assertion
          inputText: 'hello-from-d3',
        },
      },
    });
    expect(
      startResp.ok(),
      `POST /api/bpm/process-instances failed: ${startResp.status()}`,
    ).toBe(true);

    const startBody = (await startResp.json()) as Record<string, unknown>;
    const startData = startBody.data as Record<string, unknown>;
    const instanceId = startData.instanceId as string;
    expect(instanceId, 'instanceId must be present in start response').toBeTruthy();

    // Fetch final instance status — no user tasks in the flow, so it auto-completes
    const finalResp = await request.get(
      `${BACKEND}/api/bpm/process-instances/${instanceId}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(
      finalResp.ok(),
      `GET /api/bpm/process-instances/${instanceId} failed: ${finalResp.status()}`,
    ).toBe(true);

    const finalBody = (await finalResp.json()) as Record<string, unknown>;
    const finalData = finalBody.data as Record<string, unknown>;
    expect(finalData, 'process instance response must have data').toBeDefined();

    const finalStatus = finalData.status as string;
    expect(
      finalStatus,
      `Process instance must reach 'completed' status but got '${finalStatus}'. ` +
        `If status is 'error', check SmartEngine log for commandServiceTaskDelegate; ` +
        `onFail=skip_and_warn should have prevented termination.`,
    ).toBe('completed');
  });
});
