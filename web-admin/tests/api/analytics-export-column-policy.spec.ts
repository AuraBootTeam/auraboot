/** Stored artifacts must reauthorize their source before streaming bytes. */
import { test, expect, request as requestFactory } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import * as XLSX from 'xlsx';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

for (const boundary of ['resource', 'inferred'] as string[]) {
  test(`column policy protects ${boundary} export sources and invalidates old files`, async ({
    request,
  }, testInfo) => {
    const code = `export_revoke_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
    const role = await request.post('/api/roles', {
      data: {
        code,
        name: code,
        type: 'custom',
        status: 'active',
        scopeType: 'tenant',
        defaultDataScopeType: 'all',
      },
    });
    expect(role.status(), await role.text()).toBe(200);
    const rolePid = (await role.json()).data.pid;
    const tree = await request.get('/api/permissions/tree');
    expect(tree.status()).toBe(200);
    const permissions = new Map<string, number>();
    const collect = (nodes: any[]) => {
      for (const node of nodes) {
        permissions.set(node.code, node.id);
        collect(node.children ?? []);
      }
    };
    collect((await tree.json()).data);
    const sourcePermission = permissions.get('model.e2et_order.read');
    expect(sourcePermission).toBeTruthy();
    const grants = ['meta.query.read', 'model.e2et_order.read', 'model.e2et_customer.read'].map(
      (key) => {
        expect(permissions.get(key), key).toBeTruthy();
        return { permissionId: permissions.get(key), granted: true };
      },
    );
    expect(
      (await request.put(`/api/permissions/matrix/${rolePid}/batch`, { data: grants })).status(),
    ).toBe(200);
    const email = `${code}@e2e.local`;
    const password = `Aa7!${randomUUID()}`;
    const user = await request.post('/api/admin/users', {
      data: {
        email,
        displayName: 'Export owner',
        initialPassword: password,
        roleCodes: [code],
        sendInviteEmail: false,
      },
    });
    expect(user.status(), await user.text()).toBe(200);
    const fixture = await request.post('/api/dynamic/e2et_order/create', {
      data: {
        e2et_order_title: code,
        e2et_order_type: 'normal',
        e2et_order_urgent: false,
        e2et_order_status: 'draft',
      },
    });
    expect(fixture.status()).toBe(200);
    const fixturePid = (await fixture.json()).data.pid;
    const exportPolicy =
      boundary === 'root'
        ? { rootAccess: { modelCode: 'e2et_order', pidParam: 'rootPid', actionCode: 'read' } }
        : {};
    const query = await request.post('/api/meta/named-queries', {
      data: {
        code,
        title: 'Export source revocation',
        status: 'published',
        ...(boundary === 'resource' ? { resourceCode: 'e2et_order', actionCode: 'read' } : {}),
        policy: exportPolicy,
        fromSql:
          'SELECT pid, created_by, e2et_order_title FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}',
        fields: [
          { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
          {
            fieldCode: 'display_title',
            columnExpr: 'e2et_order_title',
            dataType: 'string',
            operators: ['eq'],
          },
        ],
      },
    });
    expect(query.status(), await query.text()).toBe(200);
    const queryPid = (await query.json()).data.pid;
    const login = await request.post('/api/auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    const jwt = (await login.json()).data.jwt;
    expect(jwt).toBeTruthy();
    const owner = await requestFactory.newContext({
      baseURL: process.env.BACKEND_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${jwt}` },
    });
    try {
      const exported = await owner.post(`/api/meta/named-queries/${code}/export-data`, {
        data: { format: 'CSV', parameters: { marker: code, rootPid: fixturePid } },
      });
      expect(exported.status(), await exported.text()).toBe(200);
      expect((await exported.json()).data.recordCount).toBe(1);
      const url = (await exported.json()).data.downloadUrl;
      const original = await owner.get(url);
      expect(original.status(), await original.text()).toBe(200);
      expect((await original.text()).trim().split(/\r?\n/)).toHaveLength(2);
      expect(await original.text()).toContain(fixturePid);
      const policy = await request.post('/api/meta/data-permissions', {
        data: {
          name: `Hide title ${code}`,
          modelCode: 'e2et_order',
          policyType: 'column',
          fieldCode: 'e2et_order_title',
          maskType: 'hide',
          enabled: true,
        },
      });
      expect(policy.status(), await policy.text()).toBe(200);
      const policyPid = (await policy.json()).data.pid;
      const binding = await request.post(
        `/api/meta/data-permissions/${policyPid}/roles/${rolePid}`,
      );
      expect(binding.status(), await binding.text()).toBe(200);
      const detail = await owner.get(`/api/dynamic/e2et_order/${fixturePid}`);
      expect(detail.status(), await detail.text()).toBe(200);
      expect((await detail.json()).data.e2et_order_title).toBeNull();
      const listed = await owner.post(`/api/meta/named-queries/${code}/execute`, {
        data: { page: 1, size: 5, parameters: { marker: code } },
      });
      expect(listed.status(), await listed.text()).toBe(200);
      expect
        .soft((await listed.json()).data.records)
        .toEqual([{ record_key: fixturePid, display_title: null }]);
      const chart = await owner.post('/api/meta/chart-data', {
        data: {
          type: 'namedQuery',
          queryCode: code,
          dimensions: ['record_key', 'display_title'],
          metrics: [],
          parameters: { marker: code },
        },
      });
      expect(chart.status(), await chart.text()).toBe(200);
      expect
        .soft((await chart.json()).data.rows)
        .toEqual([{ record_key: fixturePid, display_title: null }]);
      const passthrough = await owner.post('/api/meta/chart-data', {
        data: {
          type: 'namedQuery',
          queryCode: code,
          dimensions: [],
          metrics: [],
          parameters: { marker: code },
        },
      });
      expect(passthrough.status(), await passthrough.text()).toBe(200);
      expect((await passthrough.json()).data.rows).toEqual([
        { record_key: fixturePid, display_title: null },
      ]);
      const counted = await owner.post('/api/meta/chart-data', {
        data: {
          type: 'namedQuery',
          queryCode: code,
          dimensions: [],
          metrics: [{ field: 'record_key', aggregation: 'count', alias: 'total' }],
          parameters: { marker: code },
        },
      });
      expect(counted.status(), await counted.text()).toBe(200);
      expect((await counted.json()).data.rows).toEqual([{ total: 1 }]);
      const protectedCount = await owner.post('/api/meta/chart-data', {
        data: {
          type: 'namedQuery',
          queryCode: code,
          dimensions: [],
          metrics: [{ field: 'display_title', aggregation: 'count', alias: 'total' }],
          parameters: { marker: code },
        },
      });
      expect(protectedCount.status(), await protectedCount.text()).toBe(403);
      expect(await protectedCount.text()).toContain(
        'Protected field aggregation requires explicit output protection',
      );
      const oldFile = await owner.get(url);
      const maskedExport = await owner.post(`/api/meta/named-queries/${code}/export-data`, {
        data: { format: 'CSV', parameters: { marker: code, rootPid: fixturePid } },
      });
      expect(maskedExport.status(), await maskedExport.text()).toBe(200);
      const newFile = await owner.get((await maskedExport.json()).data.downloadUrl);
      expect(newFile.status(), await newFile.text()).toBe(200);
      expect((await maskedExport.json()).data.recordCount).toBe(1);
      expect((await newFile.text()).trim().split(/\r?\n/)).toHaveLength(2);
      expect(await newFile.text()).toContain(fixturePid);
      for (const expression of ['e2et_order_title', 'upper(e2et_order_title)']) {
        const nestedCode = `${code}_${expression.startsWith('upper') ? 'computed' : 'nested'}`;
        const nested = await request.post('/api/meta/named-queries', {
          data: {
            code: nestedCode,
            title: 'Nested field protection',
            status: 'published',
            ...(boundary === 'resource' ? { resourceCode: 'e2et_order', actionCode: 'read' } : {}),
            fromSql: `SELECT renamed, pid FROM (SELECT ${expression} AS renamed, pid FROM mt_e2et_order WHERE e2et_order_title = #{params.marker}) source`,
            fields: [
              {
                fieldCode: 'display_title',
                columnExpr: 'renamed',
                dataType: 'string',
                operators: ['eq'],
              },
              { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
            ],
          },
        });
        expect(nested.status(), await nested.text()).toBe(200);
        const result = await owner.post(`/api/meta/named-queries/${nestedCode}/export-data`, {
          data: { format: 'CSV', parameters: { marker: code } },
        });
        if (expression.startsWith('upper')) {
          expect(result.status(), await result.text()).toBe(403);
          expect(await result.text()).toContain(
            'Protected export requires resolved function semantics',
          );
        } else {
          expect(result.status(), await result.text()).toBe(200);
          expect((await result.json()).data.recordCount).toBe(1);
          const artifact = await owner.get((await result.json()).data.downloadUrl);
          expect(artifact.status()).toBe(200);
          expect(await artifact.text()).not.toContain(code);
          expect(await artifact.text()).toContain(fixturePid);
          expect((await artifact.text()).trim().split(/\r?\n/)).toHaveLength(2);
        }
      }
      const customerTitle = `customer_${randomUUID().replaceAll('-', '')}`;
      const customer = await request.post('/api/dynamic/e2et_customer/create', {
        data: {
          e2et_cust_code: customerTitle,
          e2et_cust_name: customerTitle,
          e2et_cust_region: 'east',
          e2et_cust_active: true,
        },
      });
      expect(customer.status(), await customer.text()).toBe(200);
      const customerPid = (await customer.json()).data.pid;
      const joinCode = `${code}_join`;
      const joined = await request.post('/api/meta/named-queries', {
        data: {
          code: joinCode,
          title: 'Independent source field protection',
          status: 'published',
          ...(boundary === 'resource' ? { resourceCode: 'e2et_order', actionCode: 'read' } : {}),
          fromSql:
            'SELECT o.pid, o.created_by, o.e2et_order_title AS order_title, c.e2et_cust_name AS customer_name, c.pid AS customer_pid FROM mt_e2et_order o JOIN mt_e2et_customer c ON c.pid = #{params.customerPid} WHERE o.pid = #{params.orderPid}',
          fields: [
            { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
            {
              fieldCode: 'customer_key',
              columnExpr: 'customer_pid',
              dataType: 'string',
              operators: ['eq'],
            },
            {
              fieldCode: 'order_label',
              columnExpr: 'order_title',
              dataType: 'string',
              operators: ['eq'],
            },
            {
              fieldCode: 'customer_label',
              columnExpr: 'customer_name',
              dataType: 'string',
              operators: ['eq'],
            },
          ],
        },
      });
      expect(joined.status(), await joined.text()).toBe(200);
      const exportJoin = async (format: string, asynchronous: boolean, customerHidden: boolean) => {
        const response = await owner.post(
          `/api/meta/named-queries/${joinCode}/${asynchronous ? 'export-async' : 'export-data'}`,
          {
            data: { format, parameters: { orderPid: fixturePid, customerPid } },
          },
        );
        expect(response.status(), await response.text()).toBe(200);
        const result = (await response.json()).data;
        let downloadUrl = result.downloadUrl;
        if (asynchronous) {
          const path = `/api/meta/named-queries/export-tasks/${result.pid}`;
          await expect
            .poll(
              async () => {
                const status = await owner.get(path);
                expect(status.status(), await status.text()).toBe(200);
                const task = (await status.json()).data;
                expect(task.status, task.errorMessage).not.toBe('failed');
                if (task.status === 'completed') expect(task.processedRows).toBe(1);
                return task.status;
              },
              { timeout: 15000 },
            )
            .toBe('completed');
          downloadUrl = `${path}/download`;
        } else {
          expect(result.recordCount).toBe(1);
        }
        const file = await owner.get(downloadUrl);
        expect(file.status(), await file.text()).toBe(200);
        let rows: Record<string, unknown>[];
        if (format === 'JSON') {
          rows = JSON.parse(await file.text());
        } else {
          const workbook = XLSX.read(await file.body(), { type: 'buffer', raw: true });
          expect(workbook.SheetNames).toHaveLength(1);
          rows = XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], {
            defval: null,
          });
        }
        expect(rows).toEqual([
          {
            record_key: fixturePid,
            customer_key: customerPid,
            order_label: null,
            customer_label: customerHidden ? null : customerTitle,
          },
        ]);
        return downloadUrl;
      };
      const modes = ['CSV', 'JSON', 'EXCEL'].flatMap((format) =>
        [false, true].map((asynchronous) => ({ format, asynchronous })),
      );
      const priorFiles: string[] = [];
      for (const mode of modes)
        priorFiles.push(await exportJoin(mode.format, mode.asynchronous, false));
      const sourceGrant = permissions.get('model.e2et_customer.read');
      const revokeSource = await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
        data: [{ permissionId: sourceGrant, granted: false }],
      });
      expect(revokeSource.status(), await revokeSource.text()).toBe(200);
      try {
        const parameters = { orderPid: fixturePid, customerPid };
        const deniedList = await owner.post(`/api/meta/named-queries/${joinCode}/execute`, {
          data: { page: 1, size: 5, parameters },
        });
        const deniedChart = await owner.post('/api/meta/chart-data', {
          data: {
            type: 'namedQuery',
            queryCode: joinCode,
            dimensions: ['record_key', 'customer_key'],
            metrics: [],
            parameters,
          },
        });
        const deniedExport = await owner.post(`/api/meta/named-queries/${joinCode}/export-data`, {
          data: { format: 'JSON', parameters },
        });
        for (const denied of [
          deniedList,
          deniedChart,
          deniedExport,
          await owner.get(priorFiles[0]),
        ]) {
          expect.soft(denied.status(), await denied.text()).toBe(403);
          expect
            .soft(await denied.text())
            .toContain('Access denied for named query source: e2et_customer');
        }
        const submitted = await owner.post(`/api/meta/named-queries/${joinCode}/export-async`, {
          data: { format: 'JSON', parameters },
        });
        expect(submitted.status(), await submitted.text()).toBe(200);
        const taskPath = `/api/meta/named-queries/export-tasks/${(await submitted.json()).data.pid}`;
        await expect
          .poll(
            async () => {
              const status = await owner.get(taskPath);
              expect(status.status(), await status.text()).toBe(200);
              const task = (await status.json()).data;
              expect(task.status).not.toBe('completed');
              if (task.status === 'failed') {
                expect(task.errorMessage).toContain(
                  'Access denied for named query source: e2et_customer',
                );
                expect(task.downloadUrl).toBeFalsy();
              }
              return task.status;
            },
            { timeout: 15000 },
          )
          .toBe('failed');
        const rejectedArtifact = await owner.get(`${taskPath}/download`);
        expect(rejectedArtifact.status()).toBe(404);
      } finally {
        const restored = await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: [{ permissionId: sourceGrant, granted: true }],
        });
        expect(restored.status(), await restored.text()).toBe(200);
      }
      const restoredFile = await owner.get(priorFiles[0]);
      expect(restoredFile.status(), await restoredFile.text()).toBe(200);
      expect(await restoredFile.text()).toContain(customerTitle);
      const setCustomerScope = async (scopeType: string) => {
        const scoped = await request.put(`/api/permissions/matrix/${rolePid}/scope`, {
          data: {
            resourceCode: 'e2et_customer',
            actionCode: 'read',
            scopeType,
            mergeStrategy: 'MAX',
          },
        });
        expect(scoped.status(), await scoped.text()).toBe(200);
      };
      await setCustomerScope('self');
      try {
        const customerAccess = await owner.get(`/api/dynamic/e2et_customer/${customerPid}`);
        expect(customerAccess.status(), await customerAccess.text()).toBe(403);
        const parameters = { orderPid: fixturePid, customerPid };
        const scopedList = await owner.post(`/api/meta/named-queries/${joinCode}/execute`, {
          data: { page: 1, size: 5, parameters },
        });
        expect(scopedList.status(), await scopedList.text()).toBe(200);
        expect.soft((await scopedList.json()).data.records).toEqual([]);
        expect.soft((await scopedList.json()).data.total).toBe(0);
        const scopedChart = await owner.post('/api/meta/chart-data', {
          data: {
            type: 'namedQuery',
            queryCode: joinCode,
            dimensions: ['record_key', 'customer_key'],
            metrics: [],
            parameters,
          },
        });
        expect(scopedChart.status(), await scopedChart.text()).toBe(200);
        expect.soft((await scopedChart.json()).data.rows).toEqual([]);
        const scopedExport = await owner.post(`/api/meta/named-queries/${joinCode}/export-data`, {
          data: { format: 'JSON', parameters },
        });
        expect(scopedExport.status(), await scopedExport.text()).toBe(200);
        expect.soft((await scopedExport.json()).data.recordCount).toBe(0);
        const scopedFile = await owner.get((await scopedExport.json()).data.downloadUrl);
        expect(scopedFile.status(), await scopedFile.text()).toBe(200);
        expect.soft(JSON.parse(await scopedFile.text())).toEqual([]);
        const staleScope = await owner.get(priorFiles[0]);
        expect.soft(staleScope.status(), await staleScope.text()).toBe(403);
      } finally {
        await setCustomerScope('all');
      }
      const scopeRestored = await owner.get(priorFiles[0]);
      expect(scopeRestored.status(), await scopeRestored.text()).toBe(200);
      expect(await scopeRestored.text()).toContain(customerTitle);
      const customerPolicy = await request.post('/api/meta/data-permissions', {
        data: {
          name: `Hide customer ${code}`,
          modelCode: 'e2et_customer',
          policyType: 'column',
          fieldCode: 'e2et_cust_name',
          maskType: 'hide',
          enabled: true,
        },
      });
      expect(customerPolicy.status(), await customerPolicy.text()).toBe(200);
      const customerPolicyPid = (await customerPolicy.json()).data.pid;
      const customerBinding = await request.post(
        `/api/meta/data-permissions/${customerPolicyPid}/roles/${rolePid}`,
      );
      expect(customerBinding.status(), await customerBinding.text()).toBe(200);
      const customerDetail = await owner.get(`/api/dynamic/e2et_customer/${customerPid}`);
      expect(customerDetail.status(), await customerDetail.text()).toBe(200);
      expect((await customerDetail.json()).data.e2et_cust_name).toBeNull();
      for (const url of priorFiles) {
        const staleJoin = await owner.get(url);
        expect(staleJoin.status(), await staleJoin.text()).toBe(403);
      }
      for (const mode of modes) await exportJoin(mode.format, mode.asynchronous, true);
      await testInfo.attach('column-policy-observation', {
        contentType: 'application/json',
        body: JSON.stringify({
          dynamicTitleHidden: (await detail.json()).data.e2et_order_title === null,
          oldFileStatus: oldFile.status(),
          oldFileContainsTitle: (await oldFile.text()).includes(code),
          newFileContainsTitle: (await newFile.text()).includes(code),
        }),
      });
      expect
        .soft(oldFile.status(), 'A newly protected field must invalidate the unmasked artifact')
        .toBe(403);
      expect(
        await newFile.text(),
        'Export aliases must preserve physical field masking',
      ).not.toContain(code);
    } finally {
      await owner.dispose();
    }
  });
}
