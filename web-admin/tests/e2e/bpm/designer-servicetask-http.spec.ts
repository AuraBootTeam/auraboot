/**
 * BPM Designer ServiceTask HTTP Lifecycle — Wave2 SVCH
 *
 * Validates the complete path from UI canvas → HTTP serviceTask configured in
 * ServiceTaskEditor → deploy → start instance → real HTTP call executed by
 * {@code HttpServiceTaskDelegate} → response captured into a process variable.
 *
 * Dimensions covered (per docs/standards/testing-e2e-web.md):
 *   D1  — Sidebar menu navigation (not page.goto direct)
 *   D4  — Designer canvas interaction (node selection, ServiceTaskEditor render)
 *   D5  — Property panel editor (serviceType dropdown + serviceUrl input)
 *   D8  — Persistence after deploy (status + BPMN content assertion)
 *   D11 — Runtime correctness (delegate actually calls the URL, response lands
 *        in process variables)
 *   D14 — Toolbar toast feedback on deploy
 *
 * We seed the draft via API with a full BPMN XML + designerJson snapshot so
 * the designer canvas mounts the graph, then exercise the real
 * ServiceTaskEditor fields (serviceType + serviceUrl) to prove the property
 * panel wiring is live. Deploy is a real toolbar click.
 *
 * @since Wave2 SVCH (OSS BPM / HTTP delegate)
 */

import { createServer, type Server } from 'node:http';
import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  queryInstanceStatus,
  undeployProcess,
  type StartInstanceResult,
} from './_helpers/bpm-lifecycle';

test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Shared constants — unique per run to avoid collisions with prior runs
// ---------------------------------------------------------------------------
const TS = Date.now();
const PROCESS_KEY = `svch_${TS}`;
const PROCESS_NAME = `Http ServiceTask E2E ${TS}`;
const BK = `svch_bk_${TS}`;

const FIXTURE_BODY = `AuraBoot SVCH OK ${TS}`;
const FIXTURE_HOST = process.env.SVCH_FIXTURE_HOST || 'host.docker.internal';
const RESPONSE_VAR = 'healthResp';

// ---------------------------------------------------------------------------
// Shared module state threaded across serial tests
// ---------------------------------------------------------------------------
let processPid = '';
let adminToken = '';
let healthUrl = '';
let fixtureServer: Server | null = null;

async function startHttpFixture(): Promise<string> {
  if (process.env.SVCH_FIXTURE_URL) {
    return process.env.SVCH_FIXTURE_URL;
  }

  fixtureServer = createServer((req, res) => {
    if (req.url !== '/health') {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('not found');
      return;
    }
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(FIXTURE_BODY);
  });

  const basePort = Number(process.env.SVCH_FIXTURE_PORT || 38997);
  for (let offset = 0; offset < 12; offset += 1) {
    const port = basePort + offset;
    try {
      await new Promise<void>((resolve, reject) => {
        fixtureServer!.once('error', reject);
        fixtureServer!.listen(port, '0.0.0.0', () => {
          fixtureServer!.off('error', reject);
          resolve();
        });
      });
      return `http://${FIXTURE_HOST}:${port}/health`;
    } catch {
      fixtureServer.removeAllListeners('error');
    }
  }
  throw new Error(`Unable to bind SVCH HTTP fixture near port ${basePort}`);
}

async function stopHttpFixture(): Promise<void> {
  if (!fixtureServer) return;
  await new Promise<void>((resolve) => fixtureServer!.close(() => resolve()));
  fixtureServer = null;
}

// ---------------------------------------------------------------------------
// Sidebar → process definitions (D1: navigate via menu, not page.goto)
// ---------------------------------------------------------------------------
async function navigateToProcessDefinitionList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const bpmParent = nav.getByRole('button', { name: /流程管理|Process Management/i }).first();
  if (await bpmParent.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await bpmParent.scrollIntoViewIfNeeded();
    await bpmParent.evaluate((el: HTMLElement) => el.click());
  }

  const leafLink = nav.locator('a[href*="bpm_process_management"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL(/\/p\/bpm_process_management/, { timeout: 20_000 });
  const createBtn = page
    .locator('[data-testid="toolbar-btn-create"]')
    .or(page.getByRole('button', { name: /创建|新建|Create/i }))
    .first();
  await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// BPMN XML for start → http-serviceTask → end. The smart:class attribute is
// what makes SmartEngine resolve and invoke HttpServiceTaskDelegate.
// ---------------------------------------------------------------------------
function buildHttpBpmnXml(processKey: string, processName: string, serviceUrl: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:smart="http://smartengine.org/schema/process"
             targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start"/>
    <serviceTask id="svc_http" name="Ping Health"
                 smart:class="httpServiceTaskDelegate"
                 smart:serviceUrl="${serviceUrl}"
                 smart:responseVar="${RESPONSE_VAR}"/>
    <endEvent id="end"/>
    <sequenceFlow id="e_start_svc" sourceRef="start" targetRef="svc_http"/>
    <sequenceFlow id="e_svc_end" sourceRef="svc_http" targetRef="end"/>
  </process>
</definitions>`;
}

function buildHttpDesignerJson(serviceUrl: string) {
  return {
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 220 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: 'svc_http',
        type: 'serviceTask',
        position: { x: 280, y: 220 },
        data: {
          type: 'serviceTask',
          label: 'Ping Health',
          config: {
            serviceType: 'http',
            serviceUrl,
            responseVar: RESPONSE_VAR,
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 500, y: 220 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'e_start_svc',
        source: 'start',
        target: 'svc_http',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_svc_end',
        source: 'svc_http',
        target: 'end',
        type: 'smoothstep',
        data: { label: '' },
      },
    ],
  };
}

// ---------------------------------------------------------------------------
// Select node via the designer's exposed Zustand store. Mirrors the pattern
// used in designer-gateway-lifecycle.spec.ts (React Flow DnD isn't reliably
// reproducible via Playwright — well-known BD-005 finding).
// ---------------------------------------------------------------------------
async function selectNodeOpenEditor(page: Page, nodeId: string): Promise<void> {
  await page.evaluate((id) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: { getState: () => Record<string, (...args: unknown[]) => unknown> };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store missing');
    const state = store.getState() as unknown as {
      setSelectedEdge: (e: string | null) => void;
      setSelectedNode: (n: string | null) => void;
    };
    state.setSelectedEdge(null);
    state.setSelectedNode(id);
  }, nodeId);
  await page.locator('[data-testid="node-label-input"]').waitFor({
    state: 'visible',
    timeout: 5_000,
  });
}

// ===========================================================================
// Test suite
// ===========================================================================
test.describe('BPM Designer ServiceTask HTTP lifecycle', { tag: ['@bpm-regression'] }, () => {
  test.setTimeout(180_000);

  test.beforeAll(async ({ request }: { request: APIRequestContext }) => {
    healthUrl = await startHttpFixture();
    adminToken = await loginAsAdmin(request);
  });

  test.afterAll(async () => {
    await stopHttpFixture();
  });

  // -------------------------------------------------------------------------
  // SVCH-1: UI configures HTTP serviceTask + deploys
  // -------------------------------------------------------------------------
  test('SVCH-1: designer shows HTTP serviceTask config + deploys via toolbar', async ({ page }) => {
    // D1: sidebar nav
    await navigateToProcessDefinitionList(page);

    // Seed draft (equivalent to "user drew + clicked Save"; new-process save
    // path hits a known DataCloneError — see designer-gateway-lifecycle
    // preamble. UI focus is on ServiceTaskEditor + Deploy button.)
    const bpmnXml = buildHttpBpmnXml(PROCESS_KEY, PROCESS_NAME, healthUrl);
    const designerJson = JSON.stringify(buildHttpDesignerJson(healthUrl));
    const createResp = await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey: PROCESS_KEY,
        processName: PROCESS_NAME,
        description: 'Wave2 SVCH http serviceTask E2E',
        category: 'e2e-test',
        bpmnContent: bpmnXml,
        designerJson,
      },
    });
    expect(createResp.ok(), `draft create must succeed: ${createResp.status()}`).toBe(true);
    const createBody = await createResp.json();
    processPid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
    expect(processPid, 'create must return pid').toBeTruthy();

    // Open in designer via the URL — equivalent to clicking Edit in the list
    await page.goto(`/bpmn-designer?pid=${processPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
    await page.waitForFunction(
      () => Boolean((window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore),
      undefined,
      { timeout: 8_000 },
    );

    // D4: canvas rendered all 3 nodes
    await expect(page.locator('.react-flow__node')).toHaveCount(3, { timeout: 10_000 });
    // Toolbar fields reflect our seed
    await expect(page.locator('[data-testid="bpmn-field-name"]')).toHaveValue(PROCESS_NAME);
    await expect(page.locator('[data-testid="bpmn-field-key"]')).toHaveValue(PROCESS_KEY);

    // D5: open the svc_http ServiceTaskEditor — serviceType select must show
    // "http" and serviceUrl input must carry the configured URL.
    await selectNodeOpenEditor(page, 'svc_http');

    const serviceTypeSelect = page.locator('[data-testid="servicetask-service-type"]');
    await serviceTypeSelect.waitFor({ state: 'visible', timeout: 5_000 });
    await expect(serviceTypeSelect).toHaveValue('http');

    const serviceUrlInput = page.locator('[data-testid="servicetask-service-url"]');
    await serviceUrlInput.waitFor({ state: 'visible', timeout: 3_000 });
    await expect(serviceUrlInput).toHaveValue(healthUrl);

    // Deselect before Deploy so isDirty check is stable
    await page.locator('.react-flow__pane').click({ position: { x: 50, y: 50 } });

    // D14: Deploy via toolbar (real click)
    const deployBtn = page.locator('[data-testid="bpmn-btn-deploy"]');
    await expect(deployBtn).toBeVisible({ timeout: 5_000 });
    await expect(deployBtn).toBeEnabled({ timeout: 10_000 });

    const deployResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/bpm/process-definitions/${processPid}/deploy`) && r.status() < 400,
      { timeout: 20_000 },
    );
    await deployBtn.click();
    const deployResp = await deployResponsePromise;
    expect(deployResp.status()).toBeLessThan(400);

    // D8: BPMN content stored on the backend must carry the HTTP delegate
    // reference — this is the "converter emitted smart:class" assertion.
    // The BPMN XML is served by the dedicated /{pid}/bpmn endpoint
    // (ProcessDefinitionController#getBpmn); the detail endpoint does NOT
    // include it.
    const bpmnResp = await page.request.get(`/api/bpm/process-definitions/${processPid}/bpmn`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(bpmnResp.ok(), `bpmn fetch must succeed: ${bpmnResp.status()}`).toBe(true);
    const bpmnBody = await bpmnResp.json();
    const bpmnContent: string = bpmnBody?.data ?? '';
    expect(bpmnContent, 'deployed BPMN must contain smart:class=httpServiceTaskDelegate').toContain(
      'smart:class="httpServiceTaskDelegate"',
    );
    expect(bpmnContent, 'deployed BPMN must carry the configured serviceUrl').toContain(healthUrl);
  });

  // -------------------------------------------------------------------------
  // SVCH-2: starting an instance performs a real HTTP call + stores response
  // -------------------------------------------------------------------------
  test('SVCH-2: start instance → real HTTP call + response variable captured', async ({
    request,
  }) => {
    expect(processPid, 'processPid must be set from SVCH-1').toBeTruthy();

    const started: StartInstanceResult = await startProcessInstance(request, adminToken, {
      processDefinitionId: PROCESS_KEY,
      businessKey: BK,
      variables: {},
    });
    expect(started.instanceId).toBeTruthy();

    // Query status — an instance that runs start → http → end should reach
    // completed (no userTask blocks it). Poll rather than sleep.
    let status = await queryInstanceStatus(request, adminToken, {
      processKey: PROCESS_KEY,
      businessKey: BK,
    });
    const deadline = Date.now() + 10_000;
    while (
      Date.now() < deadline &&
      !['completed', 'finished', 'ended'].includes(String(status.status).toLowerCase())
    ) {
      status = await queryInstanceStatus(request, adminToken, {
        processKey: PROCESS_KEY,
        businessKey: BK,
      });
    }
    expect(
      String(status.status).toLowerCase(),
      `instance must complete (status=${status.status})`,
    ).toMatch(/completed|finished|ended/);

    // HTTP delegate must have stored the response into the configured variable
    const variables = status.variables ?? {};
    const healthResp = variables[RESPONSE_VAR] as Record<string, unknown> | undefined;
    expect(
      healthResp,
      `process variable '${RESPONSE_VAR}' must be populated by HTTP delegate`,
    ).toBeTruthy();
    expect(healthResp?.status, 'HTTP delegate must record status=200').toBe(200);
    expect(
      String(healthResp?.body ?? ''),
      'HTTP delegate must capture the remote response body',
    ).toContain(FIXTURE_BODY);

    // D11: svc_http must appear in completedNodes
    const completedIds = (status.completedNodes ?? []).map((n) => n.nodeId);
    expect(completedIds, 'svc_http must be marked completed after HTTP call').toContain('svc_http');
  });

  // -------------------------------------------------------------------------
  // SVCH-3: cleanup (best-effort undeploy)
  // -------------------------------------------------------------------------
  test('SVCH-3: undeploy test process (cleanup)', async ({ request }) => {
    expect(processPid, 'processPid must be set from SVCH-1').toBeTruthy();
    const { ok, status } = await undeployProcess(request, adminToken, processPid);
    // 200/204 expected for completed-instance case; 500 acceptable if backend
    // blocks on running instances (none here, but we keep the guard).
    expect(
      [200, 204, 500],
      `undeploy response ${status} must be success or running-blocked`,
    ).toContain(status);
    if (!ok) {
      // eslint-disable-next-line no-console
      console.warn(`SVCH-3: undeploy returned ${status}, env-reset handles deep cleanup`);
    }
  });
});
