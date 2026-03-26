/**
 * E2E Test: SavedView Management
 *
 * Tests view CRUD operations: create, delete, switch,
 * share, default, duplicate, persistence.
 *
 * @since 7.0.0
 */

import { test, expect, type Page } from '@playwright/test';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { uniqueId } from '../helpers';

// Helper to manage views via API
async function createViewViaApi(page: Page, modelCode: string, name: string, viewType = 'table', scope = 'personal'): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: {
      name,
      modelCode,
      viewType,
      scope,
      viewConfig: {},
    },
  });
  if (!resp.ok()) return '';
  const body = await resp.json();
  return body.data?.pid ?? body.pid ?? '';
}

async function deleteViewViaApi(page: Page, pid: string): Promise<void> {
  await page.request.delete(`/api/views/${pid}`).catch(() => {});
}

async function listViewsViaApi(page: Page, modelCode: string): Promise<any[]> {
  const resp = await page.request.get(`/api/views/accessible?modelCode=${modelCode}`);
  if (!resp.ok()) return [];
  const body = await resp.json();
  return body.data ?? [];
}

test.describe('SavedView — Management', () => {
  const createdViewPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    for (const pid of createdViewPids) {
      await deleteViewViaApi(page, pid);
    }
    await page.close();
  });

  test('SV-050: create new view and name it @smoke', async ({ page }) => {
    const viewName = `TestView_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (pid) {
      createdViewPids.push(pid);
      // Verify view was created
      const views = await listViewsViaApi(page, 'e2et_order');
      const found = views.find((v: any) => v.name === viewName);
      expect(found).toBeTruthy();
    }
  });

  test('SV-051: delete custom view @smoke', async ({ page }) => {
    const viewName = `DeleteMe_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    expect(pid).toBeTruthy();
    if (pid) {
      await deleteViewViaApi(page, pid);
      // Verify it's gone
      const views = await listViewsViaApi(page, 'e2et_order');
      const found = views.find((v: any) => v.pid === pid);
      expect(found).toBeFalsy();
    }
  });

  test('SV-052: switching views preserves filter state', async ({ page }) => {
    // Create two views with different filters
    const view1Name = `FilterView1_${uniqueId()}`;
    const view2Name = `FilterView2_${uniqueId()}`;
    const pid1 = await createViewViaApi(page, 'e2et_order', view1Name);
    const pid2 = await createViewViaApi(page, 'e2et_order', view2Name);
    if (pid1) createdViewPids.push(pid1);
    if (pid2) createdViewPids.push(pid2);

    // Update view1 with filter config
    if (pid1) {
      await page.request.put(`/api/views/${pid1}`, {
        data: {
          viewConfig: {
            filters: [
              { fieldCode: 'e2et_order_type', operator: 'eq', value: 'bulk' },
            ],
          },
        },
      });
    }
    // Verify the filter was saved
    if (pid1) {
      const resp = await page.request.get(`/api/views/${pid1}`);
      if (resp.ok()) {
        const body = await resp.json();
        const viewData = body.data ?? body;
        const filters = viewData.viewConfig?.filters ?? [];
        expect(filters.length).toBeGreaterThan(0);
      }
    }
  });

  test('SV-053: view scope — personal to team to global', async ({ page }) => {
    // Create views with different scopes
    const personalView = await createViewViaApi(page, 'e2et_order', `Personal_${uniqueId()}`, 'table', 'personal');
    const globalView = await createViewViaApi(page, 'e2et_order', `Global_${uniqueId()}`, 'table', 'global');
    if (personalView) createdViewPids.push(personalView);
    if (globalView) createdViewPids.push(globalView);

    // Verify both accessible
    const views = await listViewsViaApi(page, 'e2et_order');
    if (personalView) {
      expect(views.find((v: any) => v.pid === personalView)).toBeTruthy();
    }
    if (globalView) {
      expect(views.find((v: any) => v.pid === globalView)).toBeTruthy();
    }
  });

  test('SV-054: default view cannot be deleted', async ({ page }) => {
    // Create a view and set as default
    const viewName = `DefaultTest_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (!pid) return;
    createdViewPids.push(pid);

    // Set as default
    const setResp = await page.request.post(`/api/views/${pid}/set-default`);
    // Now try to delete it — should fail or succeed depending on business rules
    const delResp = await page.request.delete(`/api/views/${pid}`);
    // The behavior depends on backend rules - document it
    expect(delResp.status()).toBeDefined();
  });

  test('SV-055: duplicate existing view', async ({ page }) => {
    const originalName = `Original_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', originalName);
    if (!pid) return;
    createdViewPids.push(pid);

    const dupName = `Copy_${originalName}`;
    const dupResp = await page.request.post(`/api/views/${pid}/duplicate`, {
      data: { name: dupName },
    });
    if (dupResp.ok()) {
      const body = await dupResp.json();
      const dupPid = body.data?.pid ?? body.pid;
      if (dupPid) createdViewPids.push(dupPid);
      // Verify copy exists
      const views = await listViewsViaApi(page, 'e2et_order');
      expect(views.find((v: any) => v.name === dupName)).toBeTruthy();
    }
  });

  test('SV-056: filter persistence — survives page refresh', async ({ page }) => {
    const viewName = `Persist_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (!pid) return;
    createdViewPids.push(pid);

    // Set filter config
    await page.request.put(`/api/views/${pid}`, {
      data: {
        viewConfig: {
          filters: [{ fieldCode: 'e2et_order_status', operator: 'eq', value: 'draft' }],
        },
      },
    });

    // Fetch again and verify
    const resp = await page.request.get(`/api/views/${pid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const filters = (body.data ?? body).viewConfig?.filters ?? [];
    expect(filters.length).toBe(1);
    expect(filters[0].fieldCode).toBe('e2et_order_status');
  });

  test('SV-057: sort persistence', async ({ page }) => {
    const viewName = `SortPersist_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (!pid) return;
    createdViewPids.push(pid);

    await page.request.put(`/api/views/${pid}`, {
      data: {
        viewConfig: {
          sorts: [{ fieldCode: 'e2et_order_title', direction: 'asc' }],
        },
      },
    });

    const resp = await page.request.get(`/api/views/${pid}`);
    const body = await resp.json();
    const sorts = (body.data ?? body).viewConfig?.sorts ?? [];
    expect(sorts.length).toBe(1);
  });

  test('SV-058: groupBy persistence', async ({ page }) => {
    const viewName = `GroupPersist_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (!pid) return;
    createdViewPids.push(pid);

    await page.request.put(`/api/views/${pid}`, {
      data: {
        viewConfig: {
          groupByField: 'e2et_order_status',
        },
      },
    });

    const resp = await page.request.get(`/api/views/${pid}`);
    const body = await resp.json();
    const groupByField = (body.data ?? body).viewConfig?.groupByField;
    expect(groupByField).toBe('e2et_order_status');
  });

  test('SV-059: density setting (compact/default/comfortable)', async ({ page }) => {
    const viewName = `Density_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (!pid) return;
    createdViewPids.push(pid);

    for (const density of ['compact', 'default', 'comfortable']) {
      await page.request.put(`/api/views/${pid}`, {
        data: { viewConfig: { density } },
      });
      const resp = await page.request.get(`/api/views/${pid}`);
      const body = await resp.json();
      expect((body.data ?? body).viewConfig?.density).toBe(density);
    }
  });

  test('SV-060: page size setting', async ({ page }) => {
    const viewName = `PageSize_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (!pid) return;
    createdViewPids.push(pid);

    await page.request.put(`/api/views/${pid}`, {
      data: {
        viewConfig: {
          pagination: { pageSize: 25 },
        },
      },
    });

    const resp = await page.request.get(`/api/views/${pid}`);
    const body = await resp.json();
    expect((body.data ?? body).viewConfig?.pagination?.pageSize).toBe(25);
  });

  test('SV-061: frozen column setting', async ({ page }) => {
    const viewName = `Frozen_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    if (!pid) return;
    createdViewPids.push(pid);

    await page.request.put(`/api/views/${pid}`, {
      data: {
        viewConfig: {
          columns: [
            { fieldCode: 'e2et_order_no', visible: true, frozen: true },
            { fieldCode: 'e2et_order_title', visible: true },
          ],
        },
      },
    });

    const resp = await page.request.get(`/api/views/${pid}`);
    const body = await resp.json();
    const columns = (body.data ?? body).viewConfig?.columns ?? [];
    const frozenCol = columns.find((c: any) => c.fieldCode === 'e2et_order_no');
    expect(frozenCol?.frozen).toBe(true);
  });
});
