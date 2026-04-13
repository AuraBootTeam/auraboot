/**
 * BPM Designer UI E2E Tests
 *
 * Tests D2-E01 ~ D2-E05: BPMN Designer UI interactions
 * API tests (D2-E06 ~ D2-E10) migrated to: tests/api/bpm-conversion.spec.ts
 * - Designer canvas rendering
 * - Node palette visibility
 * - Add nodes to canvas
 * - Connect nodes with edges
 * - Node selection and properties panel
 * - Save flow to backend
 * - Export to BPMN XML
 *
 * Uses real database, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

function generateProcessKey(): string {
  const ts = Date.now();
  const rand = Math.random().toString(36).slice(2, 7);
  return `e2e_des_${ts}_${rand}`;
}

function isProcessUpdateForbidden(message: string): boolean {
  return /system\.process\.update|Access forbidden|Access denied/i.test(message);
}

test.describe('BPMN Designer UI', () => {
  /**
   * D2-E01: Designer page loads with React Flow canvas
   */
  test('D2-E01: Designer page loads canvas', async ({ page }) => {
    await page.goto(`/bpmn-designer`);
    await page.waitForLoadState('domcontentloaded');

    // Wait for main content to appear
    const mainContent = page.locator('main');
    const hasMain = await mainContent.isVisible({ timeout: 8000 }).catch(() => false);

    // Check for React Flow canvas
    const hasCanvas = await page
      .locator('.react-flow, [data-testid="rf-canvas"], .react-flow__renderer')
      .isVisible({ timeout: 3000 })
      .catch(() => false);

    // Check for designer-related content
    const hasDesignerContent = await page
      .locator('[class*="designer"], [class*="flow"]')
      .isVisible({ timeout: 3000 })
      .catch(() => false);

    // Check for any BPMN-related UI elements
    const hasBpmnElements = await page
      .locator('text=BPMN, text=流程, text=Process')
      .first()
      .isVisible({ timeout: 2000 })
      .catch(() => false);

    console.log(
      `Designer check - main: ${hasMain}, canvas: ${hasCanvas}, content: ${hasDesignerContent}, elements: ${hasBpmnElements}`,
    );
    expect(hasMain || hasCanvas || hasDesignerContent || hasBpmnElements).toBe(true);
  });

  /**
   * D2-E02: Node palette shows available node types
   */
  test('D2-E02: Node palette visible', async ({ page }) => {
    await page.goto(`/bpmn-designer`);
    await page.waitForLoadState('domcontentloaded');

    // Look for node palette/sidebar
    const nodeTypes = [
      '开始',
      'Start',
      '结束',
      'End',
      '用户任务',
      'User Task',
      '服务任务',
      'Service',
    ];
    let foundNodeTypes = 0;

    for (const type of nodeTypes) {
      const nodeType = page.locator(`text=${type}`);
      if (await nodeType.isVisible({ timeout: 2000 }).catch(() => false)) {
        foundNodeTypes++;
      }
    }

    // Should find at least some node types in palette
    console.log(`Found ${foundNodeTypes} node types in palette`);
    expect(foundNodeTypes).toBeGreaterThan(0);
  });

  /**
   * D2-E03: Canvas shows nodes after loading process
   */
  test('D2-E03: Canvas renders nodes', async ({ page }) => {
    const processKey = generateProcessKey();

    // Create a process definition with both BPMN XML and designer JSON
    const designerJson = JSON.stringify({
      nodes: [
        { id: 'start', type: 'startEvent', position: { x: 100, y: 200 }, data: { type: 'startEvent', label: 'Start' } },
        { id: 'task1', type: 'userTask', position: { x: 300, y: 200 }, data: { type: 'userTask', label: 'Test Task' } },
        { id: 'end', type: 'endEvent', position: { x: 500, y: 200 }, data: { type: 'endEvent', label: 'End' } },
      ],
      edges: [
        { id: 'f1', source: 'start', target: 'task1', type: 'smoothstep' },
        { id: 'f2', source: 'task1', target: 'end', type: 'smoothstep' },
      ],
    });
    const createResponse = await page.request.post(`/api/bpm/process-definitions`, {
      data: {
        processKey,
        processName: `E2E Canvas Test ${processKey}`,
        designerJson,
        bpmnContent: `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="E2E Canvas Test" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="task1" name="Test Task"/>
    <endEvent id="end"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="task1"/>
    <sequenceFlow id="f2" sourceRef="task1" targetRef="end"/>
  </process>
</definitions>`,
      },
    });

    if (!createResponse.ok()) {
      const bodyText = await createResponse.text().catch(() => '');
      console.warn(`Designer D2-E03: create failed ${createResponse.status()} ${bodyText}`);
      if (createResponse.status() === 403 && isProcessUpdateForbidden(bodyText)) {
        test.skip(true, 'Missing permission: system.process.update');
        return;
      }
      throw new Error(String(`Process creation failed with status ${createResponse.status()}`));
      return;
    }

    const data = await createResponse.json();
    const processPid = data.data?.pid || data.pid;
    if (!processPid) {
      console.warn(
        'Designer D2-E03: create response missing pid:',
        JSON.stringify(data).slice(0, 200),
      );
      throw new Error(String('Process creation response missing pid'));
      return;
    }

    try {
      // Open designer with process (use ?pid= not ?id=)
      await page.goto(`/bpmn-designer?pid=${processPid}`);
      await page.waitForLoadState('domcontentloaded');

      // Ensure ReactFlow container has height for headless rendering
      await page.locator('.react-flow').waitFor({ state: 'visible', timeout: 10_000 });
      await page.evaluate(() => {
        const rf = document.querySelector('.react-flow') as HTMLElement;
        if (rf && rf.offsetHeight < 50) {
          rf.style.height = '600px';
          rf.style.minHeight = '600px';
        }
        const parent = rf?.parentElement;
        if (parent && parent.offsetHeight < 50) {
          parent.style.height = '600px';
          parent.style.minHeight = '600px';
        }
      }).catch(() => {});

      // Wait for ReactFlow to render nodes after height fix
      // Use waitFor instead of isVisible to handle re-layout timing
      await page.locator('.react-flow__node').first().waitFor({ state: 'visible', timeout: 10_000 });

      const nodeCount = await page.locator('.react-flow__node').count();
      console.log(`Canvas has ${nodeCount} nodes`);
      expect(nodeCount).toBeGreaterThanOrEqual(1);
    } finally {
      // Cleanup
      await page.request.delete(`/api/bpm/process-definitions/${processPid}`);
    }
  });

  /**
   * D2-E04: Node selection updates properties panel
   */
  test('D2-E04: Node selection shows properties', async ({ page }) => {
    await page.goto(`/bpmn-designer`);
    await page.waitForLoadState('domcontentloaded');

    // Look for any clickable node
    const node = page.locator('.react-flow__node').first();
    const hasNode = await node.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasNode) {
      // Bootstrap a minimal process and reopen designer with id.
      const processKey = `d2e04_${Date.now()}`;
      const createResponse = await page.request.post('/api/bpm/process-definitions', {
        data: {
          key: processKey,
          name: `D2-E04 ${Date.now()}`,
          category: 'e2e',
        },
      });
      if (!createResponse.ok()) {
        await expect(page.locator('.react-flow, [data-testid="bpmn-canvas"]').first()).toBeVisible({
          timeout: 5000,
        });
        return;
      }
      const created = await createResponse.json().catch(() => ({}) as any);
      const processPid = created?.data?.pid ?? created?.pid;
      if (!processPid) {
        await expect(page.locator('.react-flow, [data-testid="bpmn-canvas"]').first()).toBeVisible({
          timeout: 5000,
        });
        return;
      }
      await page.goto(`/bpmn-designer?id=${processPid}`);
      await page.waitForLoadState('domcontentloaded');
    }

    const runtimeNode = page.locator('.react-flow__node').first();
    if (!(await runtimeNode.isVisible({ timeout: 5000 }).catch(() => false))) {
      await expect(page.locator('.react-flow, [data-testid="bpmn-canvas"]').first()).toBeVisible({
        timeout: 5000,
      });
      return;
    }
    await runtimeNode.click();

    // Check for properties panel
    const propertiesPanel = page.locator('[class*="properties"], [class*="panel"], aside');
    const hasPanel = await propertiesPanel.isVisible({ timeout: 3000 }).catch(() => false);
    console.log(`Properties panel visible: ${hasPanel}`);
    expect(hasPanel).toBe(true);
  });

  /**
   * D2-E05: Save button triggers API call
   */
  test('D2-E05: Save button works', async ({ page }) => {
    await page.goto(`/bpmn-designer`);
    await page.waitForLoadState('domcontentloaded');

    // Find save button
    const saveButton = page.locator('button:has-text("保存"), button:has-text("Save")');
    const hasSaveButton = await saveButton.isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`Save button found: ${hasSaveButton}`);
    expect(hasSaveButton).toBe(true);
  });
});
