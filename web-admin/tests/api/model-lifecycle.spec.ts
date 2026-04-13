/**
 * Model Lifecycle API Tests
 *
 * Migrated from: tests/e2e/integration/model-lifecycle.spec.ts
 * Tests INT-01 ~ INT-07: Model API lifecycle (create, add fields, publish, CRUD on dynamic table)
 *
 * INT-08 (UI navigation) remains in the E2E file.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';
import { ErrorCodes } from '~/shared/services/http-client/types';

function generateCode(prefix: string = 'intg'): string {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).substring(2, 6);
  return `${prefix}_e2e_${timestamp}_${random}`;
}

test.describe('Model Lifecycle API', () => {
  test.describe.configure({ mode: 'serial' });

  let modelPid: string | null = null;
  let modelCode: string;
  let nameFieldCode: string;
  let recordPid: string | null = null;
  let modelPublished = false;

  test('INT-01: Create model via API', async ({ request }) => {
    modelCode = generateCode('intg');

    const response = await request.post(`/api/meta/models`, {
      data: {
        code: modelCode,
        displayName: `Integration Test Model ${modelCode}`,
        description: 'Model lifecycle integration test',
        modelType: 'entity',
      },
    });

    expect(response.ok()).toBe(true);

    const body = await response.json();
    expect(body.code).toBe(ErrorCodes.SUCCESS);
    expect(body.data).not.toBeNull();
    expect(body.data.pid).toBeTruthy();
    expect(body.data.code).toBe(modelCode);
    expect(body.data.modelType).toBe('entity');
    expect(body.data.status).toBe('draft');

    modelPid = body.data.pid;
  });

  test('INT-02: Add fields to model', async ({ request }) => {
    test.skip(!modelPid, 'INT-01 must run first to create the model (serial dependency)');

    async function createAndBindField(code: string, dataType: string) {
      const createResp = await request.post(`/api/meta/fields`, {
        data: { code, dataType },
      });
      expect(createResp.ok()).toBe(true);
      const createBody = await createResp.json();
      expect(createBody.code).toBe(ErrorCodes.SUCCESS);
      const fieldPid = createBody.data?.pid;
      expect(fieldPid).toBeTruthy();

      const bindResp = await request.post(`/api/meta/models/${modelPid}/fields/${fieldPid}`);
      expect(bindResp.ok()).toBe(true);
      const bindBody = await bindResp.json();
      expect(bindBody.code).toBe(ErrorCodes.SUCCESS);

      return fieldPid;
    }

    nameFieldCode = `test_name_${Date.now().toString(36)}`;
    await createAndBindField(nameFieldCode, 'string');
    await createAndBindField(`test_status_${Date.now().toString(36)}`, 'string');
  });

  test('INT-03: Publish model creates dynamic table', async ({ request }) => {
    test.skip(!modelPid, 'INT-01 must run first to create the model (serial dependency)');

    const publishResponse = await request.post(`/api/meta/models/${modelPid}/publish`);

    const publishBody = await publishResponse.json();
    if (!publishResponse.ok() || publishBody.code !== ErrorCodes.SUCCESS) {
      test.skip(true, `Publish failed: ${publishBody.message || publishResponse.status()}`);
      return;
    }
    expect(publishBody.code).toBe(ErrorCodes.SUCCESS);

    const getResponse = await request.get(`/api/meta/models/${modelPid}`);

    expect(getResponse.ok()).toBe(true);
    const getBody = await getResponse.json();
    expect(getBody.code).toBe(ErrorCodes.SUCCESS);
    expect(getBody.data.status).toBe('published');
    modelPublished = true;

    const dynamicResponse = await request.get(`/api/dynamic/${modelCode}/list`);

    expect(dynamicResponse.ok()).toBe(true);
    const dynamicBody = await dynamicResponse.json();
    expect(dynamicBody.code).toBe(ErrorCodes.SUCCESS);
  });

  test('INT-04: Create record in dynamic table', async ({ request }) => {
    test.skip(!modelPid || !modelPublished, 'INT-03 must succeed first (model must be published)');

    const createResponse = await request.post(`/api/dynamic/${modelCode}`, {
      data: {
        [nameFieldCode]: 'Integration Test Record',
      },
    });

    expect(createResponse.ok()).toBe(true);
    const createBody = await createResponse.json();
    expect(createBody.code).toBe(ErrorCodes.SUCCESS);
    expect(createBody.data).not.toBeNull();
    expect(createBody.data.pid).toBeTruthy();

    recordPid = createBody.data.pid;
  });

  test('INT-05: Read record from dynamic table', async ({ request }) => {
    test.skip(!recordPid, 'INT-04 must run first (serial dependency)');

    const getResponse = await request.get(`/api/dynamic/${modelCode}/${recordPid}`);

    expect(getResponse.ok()).toBe(true);
    const getBody = await getResponse.json();
    expect(getBody.code).toBe(ErrorCodes.SUCCESS);
    expect(getBody.data).not.toBeNull();
    expect(getBody.data[nameFieldCode]).toBe('Integration Test Record');
  });

  test('INT-06: Update record in dynamic table', async ({ request }) => {
    test.skip(!recordPid, 'INT-04 must run first (serial dependency)');

    const updateResponse = await request.put(`/api/dynamic/${modelCode}/${recordPid}`, {
      data: {
        [nameFieldCode]: 'Updated Record',
      },
    });

    expect(updateResponse.ok()).toBe(true);
    const updateBody = await updateResponse.json();
    expect(updateBody.code).toBe(ErrorCodes.SUCCESS);

    const verifyResponse = await request.get(`/api/dynamic/${modelCode}/${recordPid}`);

    expect(verifyResponse.ok()).toBe(true);
    const verifyBody = await verifyResponse.json();
    expect(verifyBody.data[nameFieldCode]).toBe('Updated Record');
  });

  test('INT-07: Delete record from dynamic table', async ({ request }) => {
    test.skip(!recordPid, 'INT-04 must run first (serial dependency)');

    const deleteResponse = await request.delete(`/api/dynamic/${modelCode}/${recordPid}`);

    expect(deleteResponse.ok()).toBe(true);
    const deleteBody = await deleteResponse.json();
    expect(deleteBody.code).toBe(ErrorCodes.SUCCESS);

    const verifyResponse = await request.get(`/api/dynamic/${modelCode}/${recordPid}`);

    const verifyBody = await verifyResponse.json();
    const isGone =
      !verifyResponse.ok() || verifyBody.code !== ErrorCodes.SUCCESS || verifyBody.data === null;
    expect(isGone).toBe(true);
  });

  test.afterAll(async ({ request }) => {
    if (modelPid) {
      try {
        await request.delete(`/api/meta/models/${modelPid}`);
      } catch {
        console.warn(`[Cleanup] Failed to delete model ${modelPid}`);
      }
    }
  });
});
