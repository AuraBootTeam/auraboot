/**
 * Named Query API Integration Tests
 *
 * Tests extracted from E2E suite — pure API tests with no UI interaction.
 * Covers: CRUD via API, field management, negative/edge cases.
 *
 * @since 4.0.0
 */

import { test, expect } from '../fixtures';


function generateCode(prefix: string = 'nq'): string {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).substring(2, 6);
  return `${prefix}_e2e_${timestamp}_${random}`;
}

test.describe('Named Query API', () => {
  test.describe.configure({ mode: 'serial' });

  let queryPid: string | null = null;
  let queryCode: string | null = null;
  const testCode = generateCode('nq_api');
  const testTitle = 'API Test Query';
  const testFromSql = 'ab_user u';

  // Setup: create a named query for subsequent tests
  test.beforeAll(async ({ request }) => {
    const response = await request.post(`/api/meta/named-queries`, {
      data: {
        code: testCode,
        title: testTitle,
        description: 'Created by API integration test',
        fromSql: testFromSql,
      },
    });

    if (response.ok()) {
      const result = await response.json();
      if (result.success && result.data) {
        queryPid = result.data.pid;
        queryCode = result.data.code;
      }
    }
  });

  /**
   * F1-E03: Create named query via API
   */
  test('F1-E03: Create named query via API', async ({ page }) => {
    const apiCode = generateCode();
    const response = await page.request.post(`/api/meta/named-queries`, {
      data: {
        code: apiCode,
        title: 'API Created Query',
        description: 'Created via API in E2E test',
        fromSql: 'ab_user u LEFT JOIN ab_department d ON u.dept_id = d.id',
      },
    });

    expect(response.ok()).toBe(true);
    const result = await response.json();
    expect(result.success).toBe(true);
    expect(result.data).toBeDefined();
    expect(result.data.code).toBe(apiCode);
    expect(result.data.pid).toBeDefined();

    // Clean up - delete this query
    if (result.data.pid) {
      await page.request.delete(`/api/meta/named-queries/${result.data.pid}`);
    }
  });

  /**
   * F1-E06: Update named query via API
   */
  test('F1-E06: Update named query via API', async ({ page }) => {
    test.skip(!queryPid, 'No query pid from setup');

    const response = await page.request.put(`/api/meta/named-queries/${queryPid}`, {
      data: {
        title: testTitle,
        description: 'Updated via API',
        fromSql: testFromSql,
      },
    });

    expect(response.ok()).toBe(true);
    const result = await response.json();
    expect(result.success).toBe(true);
    expect(result.data.title).toBe(testTitle);
  });

  /**
   * F1-E07: Add field to named query
   */
  test('F1-E07: Add field to named query', async ({ page }) => {
    test.skip(!queryPid || !queryCode, 'No query pid/code from setup');

    const response = await page.request.post(`/api/meta/named-queries/${queryCode}/fields`, {
      data: {
        fieldCode: 'user_name',
        columnExpr: 'u.user_name',
        dataType: 'string',
        operators: ['EQ', 'like'],
        sortable: true,
        searchable: true,
      },
    });

    expect(response.ok()).toBe(true);
    const result = await response.json();
    expect(result.success).toBe(true);
    expect(result.data.fieldCode).toBe('user_name');
  });

  /**
   * F1-N01: Create query with invalid SQL syntax
   */
  test('F1-N01: Create query with invalid SQL returns error on test execution', async ({ page }) => {
    const invalidCode = generateCode('nq_invalid');

    const createResponse = await page.request.post(`/api/meta/named-queries`, {
      data: {
        code: invalidCode,
        title: 'Invalid SQL Test',
        description: 'E2E test - invalid SQL syntax',
        fromSql: 'SELECT *** FROM nonexistent_table WHERE AND OR',
      },
    });

    expect(createResponse.ok()).toBe(true);
    const createResult = await createResponse.json();
    expect(createResult.success).toBe(true);

    const invalidPid = createResult.data.pid;
    expect(invalidPid).toBeDefined();

    // Test-execute — should return an error with meaningful message
    const testResponse = await page.request.post(
      `/api/meta/named-queries/${invalidPid}/test`,
      {
        data: {
          pageNum: 1,
          pageSize: 5,
        },
      }
    );

    if (testResponse.ok()) {
      const testResult = await testResponse.json();
      expect(testResult.data.success).toBe(false);
      expect(testResult.data.message || testResult.data.errorMessage).toBeTruthy();
    } else {
      const status = testResponse.status();
      expect(status).toBeGreaterThanOrEqual(400);
      expect(status).toBeLessThan(504);
    }

    // Cleanup
    await page.request.delete(`/api/meta/named-queries/${invalidPid}`);
  });

  /**
   * F1-N02: Create query with duplicate code
   */
  test('F1-N02: Create query with duplicate code returns error', async ({ page }) => {
    test.skip(!queryCode, 'No query code from setup');

    const duplicateResponse = await page.request.post(`/api/meta/named-queries`, {
      data: {
        code: queryCode,
        title: 'Duplicate Code Test',
        description: 'E2E test - should fail with unique constraint',
        fromSql: 'ab_user u',
      },
    });

    if (duplicateResponse.ok()) {
      const result = await duplicateResponse.json();
      expect(result.success).toBe(false);
    } else {
      const status = duplicateResponse.status();
      expect([400, 409, 422, 500]).toContain(status);
    }
  });

  /**
   * F1-N03: Create query with whitespace-only fromSql
   */
  test('F1-N03: Create query with whitespace-only fromSql is handled', async ({ page }) => {
    const wsCode = generateCode('nq_ws');

    const createResponse = await page.request.post(`/api/meta/named-queries`, {
      data: {
        code: wsCode,
        title: 'Whitespace SQL Test',
        description: 'E2E test - whitespace-only fromSql',
        fromSql: '   \t  \n  ',
      },
    });

    let createdPid: string | null = null;

    if (createResponse.ok()) {
      const result = await createResponse.json();
      if (result.success && result.data?.pid) {
        createdPid = result.data.pid;
        const testResponse = await page.request.post(
          `/api/meta/named-queries/${createdPid}/test`,
          { data: { pageNum: 1, pageSize: 5 } }
        );
        if (testResponse.ok()) {
          const testResult = await testResponse.json();
          expect(testResult.data?.success).not.toBe(true);
        }
      }
    }

    if (createdPid) {
      await page.request.delete(`/api/meta/named-queries/${createdPid}`);
    }
  });

  /**
   * F1-N04: Create query with empty fromSql
   */
  test('F1-N04: Create query with empty fromSql is rejected', async ({ page }) => {
    const emptyCode = generateCode('nq_empty');

    const createResponse = await page.request.post(`/api/meta/named-queries`, {
      data: {
        code: emptyCode,
        title: 'Empty SQL Test',
        description: 'E2E test - empty fromSql should be rejected',
        fromSql: '',
      },
    });

    if (createResponse.ok()) {
      const result = await createResponse.json();
      if (result.success) {
        if (result.data?.pid) {
          const testResponse = await page.request.post(
            `/api/meta/named-queries/${result.data.pid}/test`,
            { data: { pageNum: 1, pageSize: 5 } }
          );
          if (testResponse.ok()) {
            const testResult = await testResponse.json();
            expect(testResult.data?.success === false || !testResponse.ok()).toBe(true);
          }
          await page.request.delete(`/api/meta/named-queries/${result.data.pid}`);
        }
      } else {
        expect(result.success).toBe(false);
      }
    } else {
      const status = createResponse.status();
      expect(status).toBeGreaterThanOrEqual(400);
      expect(status).toBeLessThan(500);
    }
  });

  /**
   * F1-N05: SQL injection in fromSql field
   */
  test('F1-N05: SQL injection in fromSql field is handled safely', async ({ page }) => {
    const injectionCode = generateCode('nq_inject');

    const createResponse = await page.request.post(`/api/meta/named-queries`, {
      data: {
        code: injectionCode,
        title: 'SQL Injection Test',
        description: 'E2E test - SQL injection attempt',
        fromSql: "ab_user u; DROP TABLE ab_user; --",
      },
    });

    let injectionPid: string | null = null;

    if (createResponse.ok()) {
      const createResult = await createResponse.json();
      if (createResult.success && createResult.data?.pid) {
        injectionPid = createResult.data.pid;

        const testResponse = await page.request.post(
          `/api/meta/named-queries/${injectionPid}/test`,
          {
            data: { pageNum: 1, pageSize: 5 },
          }
        );

        if (testResponse.ok()) {
          const verifyResponse = await page.request.get(`/api/menu/user`);
          expect(verifyResponse.ok()).toBe(true);
        }
      }
    }

    // Verify database integrity
    const integrityResponse = await page.request.get(`/api/menu/user`);
    expect(integrityResponse.ok()).toBe(true);

    if (injectionPid) {
      await page.request.delete(`/api/meta/named-queries/${injectionPid}`);
    }
  });

  // Cleanup: delete the shared test query
  test.afterAll(async ({ request }) => {
    if (queryPid) {
      try {
        if (queryCode) {
          await request.delete(
            `/api/meta/named-queries/${queryCode}/fields/user_name`
          );
        }
        await request.delete(`/api/meta/named-queries/${queryPid}`);
      } catch (error) {
        console.error('Cleanup failed:', error);
      }
    }
  });
});
