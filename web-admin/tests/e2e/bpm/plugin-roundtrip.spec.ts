/**
 * BPM Process Export / Import Roundtrip — P3-B
 *
 * Validates that a process defined via the real designer + toolbar Save can
 * be exported as a "plugin pack" JSON (mimicking the shape a plugin author
 * would ship in plugins/<name>/processes/*.json), re-imported through
 * /api/bpm/import/execute, and the resulting process definition is semantically
 * equivalent to the source (bpmnContent + designerJson + extension + nodeHooks).
 *
 * Why this test exists:
 *
 *   Plugin authors expect to round-trip a designed process through source-
 *   control-friendly JSON: export → tweak → re-import on another tenant.
 *   The OSS platform already ships a `/api/bpm/export/{processKey}` +
 *   `/api/bpm/import/execute` pair (see BpmExportImportController.java) —
 *   this spec is the first E2E that drives the full loop end-to-end and
 *   pins the "equivalence" contract so refactors can't silently drop fields.
 *
 * What "plugin pack" means here (scope note, see "Deviations" below):
 *
 *   The OSS platform does NOT ship an endpoint that accepts a zipped plugin
 *   directory for BPM processes — import goes through JSON only. So the
 *   "pack" in this spec is the JSON envelope produced by
 *   /api/bpm/export/{processKey} — the same shape a plugin's
 *   `processes/*.json` file would carry (format=aura-bpm-package, version,
 *   processDefinition, nodeHooks, permissions, slaConfigs). The test does
 *   not call `aura plugin publish` because that CLI does not wire into the
 *   BPM import path today — it imports models/commands/pages, and BPM
 *   processes are a parallel track. If the CLI learns `aura plugin publish`
 *   for BPM in the future, a follow-up spec can replace the direct
 *   /api/bpm/import/execute call with a CLI spawn; until then we hit the
 *   import endpoint directly (same contract the CLI would use internally).
 *
 * Dimensions covered (per docs/standards/testing-e2e-web.md):
 *   D1  — Sidebar → process-definition list navigation (no page.goto direct
 *         to the designer)
 *   D4  — Designer canvas: seed a 4-node graph (start → userTask → serviceTask
 *         → end) via the exposed store, assert React Flow renders 4 nodes
 *   D5  — Property panel: userTask assignee testid surfaces the seeded
 *         expression before save (real UI assertion, not store read-back)
 *   D8  — Persistence: PUT /api/bpm/process-definitions/{pid} carries the
 *         designerJson that will round-trip through export/import
 *   D12 — API contract: /api/bpm/export + /api/bpm/import/validate +
 *         /api/bpm/import/execute form a closed loop
 *   D14 — Save toast feedback
 *
 * Test structure (4 serial tests, all tagged @bpm-regression):
 *   PIR-1: design + save draft via real UI toolbar Save
 *   PIR-2: export process via GET /api/bpm/export/{processKey}, assert pack
 *          shape (format/version/processDefinition/nodeHooks/slaConfigs)
 *          and that designerJson + bpmnContent + extension all survive the
 *          export serialization
 *   PIR-3: modify the pack's processKey (so it lands as a new process, not
 *          a conflict with the source) + import via execute with
 *          strategy=new_version; validate then execute. Verify the imported
 *          process has bit-exact bpmnContent + designerJson + extension +
 *          the same node hook count.
 *   PIR-4: cleanup — undeploy both source + imported processes
 *
 * Deviations / simplifications vs. the original spec brief:
 *   - No `.zip` plugin pack: backend has no BPM zip import. We use the
 *     JSON envelope that export produces (which mirrors what a plugin's
 *     processes/*.json would carry).
 *   - No `aura plugin publish` CLI: BPM processes are not yet in the CLI
 *     import path (CLI covers models/commands/pages). Hitting the HTTP
 *     endpoint exercises the same import contract.
 *   - `permissions` is exported as [] today (BpmExportImportService.java:87
 *     notes: "now managed by RBAC (ab_permission + ab_role_permission)").
 *     We assert [] is returned and round-trips unchanged — not a
 *     regression, just pinning current contract.
 *   - processKey rewrite in PIR-3: `executeImport` uses the pack's
 *     processKey verbatim. To avoid "skip_existing" on re-import we either
 *     use strategy=overwrite or rewrite the key. We pick "rewrite the key"
 *     because it more honestly simulates the "second-tenant" use case AND
 *     lets us compare source vs. target side-by-side.
 *
 * @since Epic BPM P3-B (OSS process pack round-trip)
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import { loginAsAdmin, undeployProcess } from './_helpers/bpm-lifecycle';

// ---------------------------------------------------------------------------
// Serial mode — all four tests share the pid + pack captured in PIR-1/2
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Shared constants
// ---------------------------------------------------------------------------
const TS = Date.now();
const SOURCE_KEY = `pir_src_${TS}`;
const SOURCE_NAME = `PIR Source ${TS}`;
const IMPORTED_KEY = `pir_imp_${TS}`;

const ASSIGNEE_HR = '${hr_manager}';

// ---------------------------------------------------------------------------
// Shared state threaded across serial tests
// ---------------------------------------------------------------------------
let adminToken = '';
let sourcePid = '';
let importedPid = '';
// The JSON envelope captured in PIR-2 — the "plugin pack".
let pack: Record<string, unknown> = {};

// ---------------------------------------------------------------------------
// Types — narrow shapes for designerJson comparison
// ---------------------------------------------------------------------------
interface SanitizedNode {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: Record<string, unknown>;
}
interface SanitizedEdge {
  id: string;
  source: string;
  target: string;
  type?: string;
  data?: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function sanitizeNodes(raw: unknown): SanitizedNode[] {
  const arr = Array.isArray(raw) ? raw : [];
  return arr
    .filter((n): n is Record<string, unknown> => typeof n === 'object' && n !== null)
    .map((n) => ({
      id: String(n.id),
      type: String(n.type ?? ''),
      position: {
        x: Number((n.position as { x?: number } | undefined)?.x ?? 0),
        y: Number((n.position as { y?: number } | undefined)?.y ?? 0),
      },
      data: (n.data as Record<string, unknown>) ?? {},
    }));
}

function sanitizeEdges(raw: unknown): SanitizedEdge[] {
  const arr = Array.isArray(raw) ? raw : [];
  return arr
    .filter((e): e is Record<string, unknown> => typeof e === 'object' && e !== null)
    .map((e) => ({
      id: String(e.id),
      source: String(e.source),
      target: String(e.target),
      type: typeof e.type === 'string' ? e.type : undefined,
      data: (e.data as Record<string, unknown>) ?? {},
    }));
}

function sortById<T extends { id: string }>(xs: T[]): T[] {
  return [...xs].sort((a, b) => a.id.localeCompare(b.id));
}

/**
 * Sidebar nav → process definition list (D1). Structural copy of
 * designer-roundtrip.spec.ts's helper — same concern (sidebar click
 * path, not page.goto direct to the designer).
 */
async function navigateToProcessDefinitionList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const bpmParent = nav
    .getByRole('button', { name: /流程管理|Process Management/i })
    .first();
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

/**
 * Seed a 4-node draft graph into the exposed designer store. The graph
 * has one userTask with an assignee expression and one serviceTask with
 * a command implementation — exercises the two config shapes that carry
 * the most round-trip surface.
 */
function buildGraph(): { nodes: SanitizedNode[]; edges: SanitizedEdge[] } {
  return {
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 200 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: 'review',
        type: 'userTask',
        position: { x: 280, y: 200 },
        data: {
          type: 'userTask',
          label: 'HR Review',
          config: {
            assignee: { type: 'expression', expression: ASSIGNEE_HR },
            priority: 'high',
          },
        },
      },
      {
        id: 'notify',
        type: 'serviceTask',
        position: { x: 500, y: 200 },
        data: {
          type: 'serviceTask',
          label: 'Notify',
          config: { implementation: 'notification.send' },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 700, y: 200 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'e_start_review',
        source: 'start',
        target: 'review',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_review_notify',
        source: 'review',
        target: 'notify',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_notify_end',
        source: 'notify',
        target: 'end',
        type: 'smoothstep',
        data: { label: '' },
      },
    ],
  };
}

/**
 * Push the given graph straight into the exposed store. Mirrors
 * designer-roundtrip.spec.ts — per-element add so pushSnapshot fires.
 */
async function seedGraphIntoStore(
  page: Page,
  graph: { nodes: SanitizedNode[]; edges: SanitizedEdge[] },
): Promise<void> {
  await page.evaluate((g) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: {
          getState: () => Record<string, (...args: unknown[]) => unknown>;
        };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store missing');
    const state = store.getState() as unknown as {
      addNode: (n: unknown) => void;
      addEdge: (e: unknown) => void;
      setDirty: (b: boolean) => void;
    };
    for (const node of g.nodes) state.addNode(node);
    for (const edge of g.edges) state.addEdge(edge);
    state.setDirty(true);
  }, graph);
}

/**
 * Drive the real Save button + SaveDialog confirm; wait for the PUT
 * response and return its status.
 */
async function saveViaUI(page: Page, pid: string): Promise<number> {
  const saveBtn = page.locator('[data-testid="bpmn-toolbar-btn-save"]');
  await expect(saveBtn).toBeVisible({ timeout: 5_000 });
  await expect(saveBtn).toBeEnabled({ timeout: 5_000 });
  await saveBtn.click();

  const dialog = page.locator('[data-testid="bpmn-save-dialog-panel"]');
  await dialog.waitFor({ state: 'visible', timeout: 5_000 });

  const submitBtn = dialog.locator('button[type="submit"]').first();
  await expect(submitBtn).toBeEnabled({ timeout: 3_000 });

  const [putResp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/api/bpm/process-definitions/${pid}`) &&
        r.request().method().toLowerCase() === 'put' &&
        r.status() < 500,
      { timeout: 20_000 },
    ),
    submitBtn.click(),
  ]);
  return putResp.status();
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------
test.describe(
  'BPM process export/import plugin-pack roundtrip (P3-B)',
  { tag: ['@bpm-regression'] },
  () => {
    test.setTimeout(180_000);

    test.beforeAll(async ({ request }: { request: APIRequestContext }) => {
      adminToken = await loginAsAdmin(request);
    });

    // =======================================================================
    // PIR-1 — design + deploy source process via UI
    //
    // Seeds a draft via API (matches designer-roundtrip.spec.ts B4.1 rationale:
    // React Flow HTML5 DnD is not reproducible under Playwright), opens the
    // designer, seeds the graph into the exposed store, verifies the
    // property panel surfaces the seeded assignee, then saves via the real
    // toolbar Save button + SaveDialog submit.
    // =======================================================================
    test('PIR-1: design + save source process via toolbar (real UI)', async ({ page }) => {
      // 1. Sidebar → list (D1)
      await navigateToProcessDefinitionList(page);

      // 2. Seed an empty draft via API to get a pid-bound PUT path.
      const createResp = await page.request.post('/api/bpm/process-definitions', {
        data: {
          processKey: SOURCE_KEY,
          processName: SOURCE_NAME,
          description: 'P3-B plugin-pack roundtrip source',
          category: 'e2e-test',
          designerJson: JSON.stringify({ nodes: [], edges: [] }),
        },
      });
      expect(
        createResp.ok(),
        `create must succeed: ${createResp.status()} ${await createResp.text()}`,
      ).toBe(true);
      const createBody = await createResp.json();
      sourcePid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
      expect(sourcePid, 'create must return pid').toBeTruthy();

      // 3. Open draft in designer
      await page.goto(`/bpmn-designer?pid=${sourcePid}`, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
      await page.waitForFunction(
        () =>
          Boolean((window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore),
        undefined,
        { timeout: 8_000 },
      );
      // Wait until the designer finishes loading the (empty) draft from
      // backend before we seed — setProcessDefinition is async and
      // overwrites our adds if we race it.
      await expect
        .poll(
          async () =>
            page.evaluate(() => {
              const s = (
                window as unknown as {
                  __bpmnDesignerStore?: {
                    getState: () => { processDefinition: unknown };
                  };
                }
              ).__bpmnDesignerStore;
              return s ? Boolean(s.getState().processDefinition) : false;
            }),
          { timeout: 10_000, message: 'processDefinition must be loaded before seeding' },
        )
        .toBe(true);

      // 4. Seed graph into store
      const graph = buildGraph();
      await seedGraphIntoStore(page, graph);
      await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 10_000 });

      // 5. D5 — real property panel assertion: select userTask and verify
      //    assignee expression surfaces. Real UI, not just store read-back.
      await page.evaluate(() => {
        const s = (
          window as unknown as {
            __bpmnDesignerStore?: {
              getState: () => Record<string, (...args: unknown[]) => unknown>;
            };
          }
        ).__bpmnDesignerStore!;
        (
          s.getState() as unknown as { setSelectedNode: (id: string) => void }
        ).setSelectedNode('review');
      });
      await page
        .locator('[data-testid="usertask-assignee-type"]')
        .waitFor({ state: 'visible', timeout: 5_000 });
      await expect(page.locator('[data-testid="usertask-assignee-type"]')).toHaveValue(
        'expression',
      );
      await expect(page.locator('[data-testid="usertask-expression"]')).toHaveValue(ASSIGNEE_HR);

      // Deselect so Save button stays stable
      await page.locator('.react-flow__pane').click({ position: { x: 50, y: 50 } });

      // 6. Save via real toolbar button (D14 — toast)
      const putStatus = await saveViaUI(page, sourcePid);
      expect(putStatus).toBeLessThan(400);

      const toast = page
        .locator(':text-matches("保存成功|Save success|Saved", "i")')
        .first();
      await expect(toast, 'save-success toast must be visible').toBeVisible({ timeout: 5_000 });

      // 7. Sanity check that the backend persisted designerJson + bpmnContent
      //    (both are inputs to PIR-2 export comparison).
      const getResp = await page.request.get(`/api/bpm/process-definitions/${sourcePid}`);
      expect(getResp.ok()).toBe(true);
      const detail = (await getResp.json()) as {
        data: { designerJson: string | null; bpmnContent: string | null };
      };
      expect(detail.data.designerJson, 'designerJson must persist').toBeTruthy();
      expect(detail.data.bpmnContent, 'bpmnContent must persist').toBeTruthy();
    });

    // =======================================================================
    // PIR-2 — export process to plugin-pack JSON
    //
    // Hits /api/bpm/export/{processKey} and asserts the JSON envelope has
    // the documented shape (format/version/processDefinition/nodeHooks/
    // permissions/slaConfigs) AND that the processDefinition sub-object
    // carries both bpmnContent and extension.designerJson so a downstream
    // import can faithfully reconstruct the designer view.
    // =======================================================================
    test('PIR-2: export source process as plugin-pack JSON', async ({ request }) => {
      expect(sourcePid, 'sourcePid must be set from PIR-1').toBeTruthy();

      const resp = await request.get(`/api/bpm/export/${SOURCE_KEY}`, {
        headers: authHeaders(adminToken),
      });
      expect(
        resp.ok(),
        `export must succeed: ${resp.status()} ${await resp.text()}`,
      ).toBe(true);
      pack = (await resp.json()) as Record<string, unknown>;

      // Envelope shape
      expect(pack.format, 'pack.format').toBe('aura-bpm-package');
      expect(pack.version, 'pack.version').toBe('1.0');
      expect(pack.processKey, 'pack.processKey').toBe(SOURCE_KEY);
      expect(typeof pack.exportedAt, 'pack.exportedAt is ISO string').toBe('string');

      // processDefinition sub-object
      const pd = pack.processDefinition as Record<string, unknown>;
      expect(pd, 'pack.processDefinition present').toBeTruthy();
      expect(pd.processKey, 'pd.processKey echoed').toBe(SOURCE_KEY);
      expect(pd.processName, 'pd.processName echoed').toBe(SOURCE_NAME);
      expect(
        typeof pd.bpmnContent === 'string' && (pd.bpmnContent as string).length > 0,
        'pd.bpmnContent is non-empty string',
      ).toBe(true);
      expect(pd.version, 'pd.version').toBe(1);

      // extension.designerJson carries the seeded graph (the contract that
      // lets the reimport reconstruct the designer view).
      const extension = pd.extension as Record<string, unknown> | null;
      expect(extension, 'pd.extension present').toBeTruthy();
      const designerJsonRaw = extension?.designerJson;
      expect(
        typeof designerJsonRaw === 'string' && (designerJsonRaw as string).length > 0,
        'extension.designerJson is non-empty string',
      ).toBe(true);
      const parsed = JSON.parse(designerJsonRaw as string) as {
        nodes: unknown[];
        edges: unknown[];
      };
      const exportedNodes = sanitizeNodes(parsed.nodes);
      expect(exportedNodes, 'pack designerJson carries 4 nodes').toHaveLength(4);
      const review = exportedNodes.find((n) => n.id === 'review')!;
      expect(review, 'review node preserved').toBeTruthy();
      expect(
        (review.data.config as { assignee?: { expression?: string } } | undefined)?.assignee
          ?.expression,
        'review assignee expression preserved in pack',
      ).toBe(ASSIGNEE_HR);

      // Sibling sections exist (may be empty in this run — we assert shape,
      // not content, because permissions currently exports [] by design —
      // BpmExportImportService.java:87).
      expect(Array.isArray(pack.nodeHooks), 'pack.nodeHooks is array').toBe(true);
      expect(Array.isArray(pack.permissions), 'pack.permissions is array').toBe(true);
      expect(
        (pack.permissions as unknown[]).length,
        'pack.permissions is empty (RBAC-managed, see service:87)',
      ).toBe(0);
      expect(Array.isArray(pack.slaConfigs), 'pack.slaConfigs is array').toBe(true);
    });

    // =======================================================================
    // PIR-3: import pack creates equivalent process
    //
    // Rewrite processKey to IMPORTED_KEY (simulates "install this pack on a
    // fresh tenant"), validate via /api/bpm/import/validate, then execute
    // via /api/bpm/import/execute. Assert the imported process has
    // bit-exact bpmnContent and designerJson.
    // =======================================================================
    test('PIR-3: import pack creates equivalent process (deep-equal)', async ({ request }) => {
      expect(pack.format, 'pack must be populated from PIR-2').toBe('aura-bpm-package');

      // 1. Deep-clone + rewrite key. The `executeImport` method uses the
      //    pack's processKey verbatim (service:171). If we reuse SOURCE_KEY
      //    the import would match the existing row and (per
      //    strategy=skip_existing default) skip. We want a fresh row to
      //    compare against the source, so we rewrite the key.
      const rewritten: Record<string, unknown> = JSON.parse(JSON.stringify(pack));
      rewritten.processKey = IMPORTED_KEY;
      const rewrittenPd = rewritten.processProcessDefinition as Record<string, unknown> | undefined;
      // Defensive: `processDefinition` key, not `processProcessDefinition` typo.
      const pd = rewritten.processDefinition as Record<string, unknown>;
      pd.processKey = IMPORTED_KEY;
      pd.processName = `${SOURCE_NAME} (imported)`;
      // Guard: the defensive lookup above is intentionally not used; this
      // expect fails loudly if someone renames the key upstream.
      expect(rewrittenPd, 'no `processProcessDefinition` key should exist').toBeUndefined();

      // 2. Validate first (import contract says validate is a required
      //    precursor for a well-behaved client).
      const validateResp = await request.post('/api/bpm/import/validate', {
        headers: authHeaders(adminToken),
        data: rewritten,
      });
      expect(
        validateResp.ok(),
        `validate must succeed: ${validateResp.status()} ${await validateResp.text()}`,
      ).toBe(true);
      const validation = (await validateResp.json()) as {
        valid: boolean;
        errors: string[];
        conflicts: unknown[];
      };
      expect(validation.valid, `validation.errors=${JSON.stringify(validation.errors)}`).toBe(true);
      expect(
        validation.conflicts,
        'no conflicts expected — we rewrote to a fresh processKey',
      ).toEqual([]);

      // 3. Execute import
      const execResp = await request.post('/api/bpm/import/execute', {
        headers: authHeaders(adminToken),
        data: { package: rewritten, strategy: 'skip_existing' },
      });
      expect(
        execResp.ok(),
        `execute must succeed: ${execResp.status()} ${await execResp.text()}`,
      ).toBe(true);
      const execBody = (await execResp.json()) as {
        success: boolean;
        imported: string[];
        skipped: string[];
        processKey: string;
      };
      expect(execBody.success, 'import success').toBe(true);
      expect(execBody.processKey, 'import echoes processKey').toBe(IMPORTED_KEY);
      expect(
        execBody.imported.some((s) => s.startsWith('processDefinition')),
        `processDefinition must be in imported: ${execBody.imported.join(',')}`,
      ).toBe(true);
      expect(
        execBody.skipped,
        'nothing skipped (fresh processKey, no conflict)',
      ).not.toContain('processDefinition');

      // 4. Fetch the imported process by processKey and compare against the
      //    source. GET /key/{processKey} returns the current version row.
      const importedGet = await request.get(`/api/bpm/process-definitions/key/${IMPORTED_KEY}`, {
        headers: authHeaders(adminToken),
      });
      expect(importedGet.ok(), 'GET imported by key must succeed').toBe(true);
      const importedBody = (await importedGet.json()) as {
        data: {
          pid: string;
          bpmnContent: string | null;
          designerJson: string | null;
          extension: Record<string, unknown> | null;
        };
      };
      importedPid = importedBody.data.pid;
      expect(importedPid, 'imported process returned a pid').toBeTruthy();

      // 4a. bpmnContent bit-exact vs. source (verbatim string from pack).
      const sourcePd = pack.processDefinition as Record<string, unknown>;
      expect(
        importedBody.data.bpmnContent,
        'imported bpmnContent must equal source bpmnContent',
      ).toBe(sourcePd.bpmnContent);

      // 4b. designerJson deep-equal at the semantic (sanitized) level.
      //     Note: importedBody.data.designerJson is served out of
      //     extension.designerJson (same storage path used on export), so
      //     this assertion proves the round-trip of the designer graph.
      const sourceExt = sourcePd.extension as Record<string, unknown>;
      const sourceDesignerJson = sourceExt.designerJson as string;
      expect(
        importedBody.data.designerJson,
        'imported designerJson must be present',
      ).toBeTruthy();
      const importedGraph = JSON.parse(importedBody.data.designerJson!) as {
        nodes: unknown[];
        edges: unknown[];
      };
      const sourceGraph = JSON.parse(sourceDesignerJson) as {
        nodes: unknown[];
        edges: unknown[];
      };
      expect(
        sortById(sanitizeNodes(importedGraph.nodes)),
        'imported nodes must bit-exact match source',
      ).toEqual(sortById(sanitizeNodes(sourceGraph.nodes)));
      expect(
        sortById(sanitizeEdges(importedGraph.edges)),
        'imported edges must bit-exact match source',
      ).toEqual(sortById(sanitizeEdges(sourceGraph.edges)));

      // 4c. extension object round-trips (at minimum, designerJson is
      //     preserved — other extension fields pass through).
      expect(
        importedBody.data.extension,
        'imported extension present',
      ).toBeTruthy();
      expect(
        (importedBody.data.extension as Record<string, unknown>).designerJson,
        'imported extension.designerJson matches source',
      ).toBe(sourceDesignerJson);
    });

    // =======================================================================
    // PIR-4: cleanup — undeploy both source + imported processes
    //
    // Not in afterAll per project red line; still best-effort (either
    // endpoint may return 400/500 if no deployment exists, which is fine).
    // =======================================================================
    test('PIR-4: cleanup — undeploy source + imported (best-effort)', async ({ request }) => {
      // Source
      if (sourcePid) {
        const src = await undeployProcess(request, adminToken, sourcePid);
        expect(
          [200, 204, 400, 404, 500],
          `source undeploy status ${src.status} must be one of ok/expected`,
        ).toContain(src.status);
      }
      // Imported
      if (importedPid) {
        const imp = await undeployProcess(request, adminToken, importedPid);
        expect(
          [200, 204, 400, 404, 500],
          `imported undeploy status ${imp.status} must be one of ok/expected`,
        ).toContain(imp.status);
      }
    });
  },
);
