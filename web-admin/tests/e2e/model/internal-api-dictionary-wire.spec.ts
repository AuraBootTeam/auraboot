import { test, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import { LoginPage } from '../../pages/LoginPage';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { revokeInternalApiCurrentSession } from '../../helpers/internal-api-owned-user';
import { uniqueId } from '../helpers';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';

const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);

test.use({ storageState: { cookies: [], origins: [] }, locale: 'zh-CN' });

test.afterEach(async ({ page }, info) => {
  const baseURL = info.project.use.baseURL;
  if (!baseURL) throw new Error('Managed test base URL is required for session cleanup');
  const proof = await revokeInternalApiCurrentSession(
    page.request, new URL(baseURL).origin, 'test-fixture-page',
  );
  await info.attach('current-session-cleanup', {
    body: JSON.stringify(proof), contentType: 'application/json',
  });
});

test('all registered dictionary operations satisfy their real JSON response structures', async ({
  page,
}, info) => {
  test.setTimeout(90_000);
  const login = new LoginPage(page);
  await login.gotoRoot();
  await login.waitForFormReady();
  await login.login(DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await login.expectLoggedIn();
  const validate = createResponseShapeValidator(document);
  const headers = { Origin: new URL(page.url()).origin };
  const owned: { pid: string; code: string }[] = [];
  const observed = new Set<string>();
  const rows: object[] = [];

  async function call(
    method: 'get' | 'post' | 'put' | 'delete',
    path: string,
    url: string,
    options: { body?: unknown; negative?: boolean; status?: number; cleanup?: boolean } = {},
  ) {
    const response = await page.request.fetch(url, {
      method: method.toUpperCase(),
      headers,
      ...(options.body === undefined ? {} : { data: options.body }),
    });
    const payload = await response.json();
    // Preserve ownership before asserting the contract so an unexpected response cannot leak a fixture.
    if (
      method === 'post' &&
      path === '/api/meta/dict' &&
      payload.code === '0' &&
      typeof payload.data?.pid === 'string'
    ) {
      owned.push({ pid: payload.data.pid, code: payload.data.code });
    }
    const observation: Record<string, unknown> = {
      method,
      path,
      status: response.status(),
      payload,
      cleanup: Boolean(options.cleanup),
    };
    rows.push(observation);
    expect(response.status()).toBe(options.status ?? 200);
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    observation.shape = shape;
    expect(shape.errors, `${method} ${path} HTTP ${response.status()}`).toEqual([]);
    expect(shape.valid).toBe(true);
    if (options.negative) expect(payload.code).not.toBe('0');
    else expect(payload.code).toBe('0');
    if (!options.cleanup) observed.add(`${method} ${path}`);
    return payload.data;
  }

  async function create(
    code: string,
    dictType = 'simple',
    items: object[] = [{ value: 'yes', label: '是', sortOrder: 0, disabled: false }],
  ) {
    const data = await call('post', '/api/meta/dict', '/api/meta/dict', {
      body: { code, name: `Contract ${code.slice(-8)}`, dictType, sourceType: 'static', items },
    });
    expect(typeof data.pid).toBe('string');
    return data;
  }

  const code = uniqueId('e2e_contract_wire');
  try {
    await call('get', '/api/meta/dict/statistics', '/api/meta/dict/statistics');
    expect(
      await call('get', '/api/meta/dict/code/{code}/unique', `/api/meta/dict/code/${code}/unique`),
    ).toBe(true);
    const simple = await create(code);
    const pid: string = simple.pid;
    expect((await call('get', '/api/meta/dict/{pid}', `/api/meta/dict/${pid}`)).code).toBe(code);
    expect(
      (await call('get', '/api/meta/dict/by-code/{code}', `/api/meta/dict/by-code/${code}`)).pid,
    ).toBe(pid);
    expect(
      (await call('get', '/api/meta/dict', `/api/meta/dict?code=${code}&pageNum=1&pageSize=1`))
        .records[0].pid,
    ).toBe(pid);
    await call('put', '/api/meta/dict/{pid}', `/api/meta/dict/${pid}`, {
      body: { name: 'Contract updated' },
    });
    await call('put', '/api/meta/dict/{pid}/items', `/api/meta/dict/${pid}/items`, {
      body: [
        { value: 'yes', label: '是', sortOrder: 0, disabled: false },
        { value: 'no', label: '否', sortOrder: 1, disabled: true },
      ],
    });
    expect(
      (await call('get', '/api/meta/dict/{pid}/data', `/api/meta/dict/${pid}/data`)).items[0].value,
    ).toBe('yes');
    expect(
      (
        await call(
          'get',
          '/api/meta/dict/by-code/{code}/data',
          `/api/meta/dict/by-code/${code}/data`,
        )
      ).items[0].label,
    ).toBe('是');
    expect(
      await call('get', '/api/meta/dict/{code}/versions', `/api/meta/dict/${code}/versions`),
    ).toEqual(expect.arrayContaining([expect.objectContaining({ pid })]));
    expect(
      await call('get', '/api/meta/dict/search', `/api/meta/dict/search?keyword=${code}`),
    ).toEqual(expect.arrayContaining([expect.objectContaining({ pid })]));
    expect(
      await call('get', '/api/meta/dict/code/{code}/unique', `/api/meta/dict/code/${code}/unique`),
    ).toBe(false);
    expect(
      await call(
        'get',
        '/api/meta/dict/code/{code}/unique',
        `/api/meta/dict/code/${code}/unique?excludePid=${pid}`,
      ),
    ).toBe(true);
    expect(
      (await call('get', '/api/meta/dict/{pid}/validate', `/api/meta/dict/${pid}/validate`)).valid,
    ).toBe(true);
    await call('get', '/api/meta/dict/{pid}/validate', `/api/meta/dict/${code}/validate`, {
      negative: true,
      status: 422,
    });
    await call('get', '/api/meta/dict/{pid}/cascade/tree', `/api/meta/dict/${pid}/cascade/tree`, {
      negative: true,
      status: 422,
    });
    await call(
      'get',
      '/api/meta/dict/{pid}/cascade/tree',
      `/api/meta/dict/${uniqueId('missing_tree')}/cascade/tree`,
      { negative: true, status: 422 },
    );
    // Published resources are protected: count zero must not be mistaken for full deletion.
    expect(await call('delete', '/api/meta/dict/batch', `/api/meta/dict/batch?pids=${pid}`)).toBe(
      0,
    );
    expect((await call('get', '/api/meta/dict/{pid}', `/api/meta/dict/${pid}`)).status).toBe(
      'published',
    );
    expect(
      (await call('post', '/api/meta/dict/{pid}/unpublish', `/api/meta/dict/${pid}/unpublish`))
        .status,
    ).toBe('deprecated');
    const manageable = await call(
      'get',
      '/api/meta/dict',
      `/api/meta/dict?code=${code}&pageNum=1&pageSize=10`,
    );
    expect(manageable.records).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ pid, status: 'deprecated', isCurrent: false }),
      ]),
    );
    expect(
      (await call('post', '/api/meta/dict/{pid}/publish', `/api/meta/dict/${pid}/publish`)).status,
    ).toBe('published');
    const tree = await create(uniqueId('e2e_contract_tree'), 'tree', [
      { value: 'parent', label: '父节点', sortOrder: 0 },
      { value: 'child', label: '子节点', parentValue: 'parent', sortOrder: 1 },
    ]);
    const nodes = await call(
      'get',
      '/api/meta/dict/{pid}/cascade/tree',
      `/api/meta/dict/${tree.pid}/cascade/tree`,
    );
    expect(nodes.value).toBe('parent');
    expect(nodes.children[0].value).toBe('child');
    expect(
      await call(
        'get',
        '/api/meta/dict/{pid}/cascade/children',
        `/api/meta/dict/${pid}/cascade/children`,
      ),
    ).toEqual([]);
    const empty = await create(uniqueId('e2e_contract_empty'), 'tree', []);
    const emptyTree = await call(
      'get',
      '/api/meta/dict/{pid}/cascade/tree',
      `/api/meta/dict/${empty.pid}/cascade/tree`,
    );
    expect(emptyTree).toMatchObject({ value: null, label: null, children: [] });
    for (const item of [tree, empty])
      await call('post', '/api/meta/dict/{pid}/unpublish', `/api/meta/dict/${item.pid}/unpublish`);
    expect(
      await call(
        'delete',
        '/api/meta/dict/batch',
        `/api/meta/dict/batch?pids=${tree.pid}&pids=${empty.pid}`,
      ),
    ).toBe(2);
    await call('post', '/api/meta/dict/{pid}/unpublish', `/api/meta/dict/${pid}/unpublish`);
    await call('delete', '/api/meta/dict/{pid}', `/api/meta/dict/${pid}`);
    expect((await call('get', '/api/meta/dict/{pid}', `/api/meta/dict/${pid}`)).status).toBe(
      'disabled',
    );
    await call('post', '/api/meta/dict', '/api/meta/dict', {
      body: { code: uniqueId('e2e_rejected'), name: 'Invalid', dictType: 'simple' },
      negative: true,
      status: 400,
    });
    const registered = catalog.profiles.dictionary.operations.map(
      (row: { method: string; path: string }) => `${row.method} ${row.path}`,
    );
    expect([...observed].sort()).toEqual([...registered].sort());
  } finally {
    // Cleanup must still run when the contract under test is stale or malformed.
    const cleanupErrors: string[] = [];
    for (const item of owned) {
      try {
        const currentResponse = await page.request.get(`/api/meta/dict/${item.pid}`);
        const current = await currentResponse.json();
        expect(current.code).toBe('0');
        if (current.data.status === 'disabled') continue;
        if (current.data.status === 'published') {
          const unpublished = await page.request.post(`/api/meta/dict/${item.pid}/unpublish`, {
            headers,
          });
          expect((await unpublished.json()).code).toBe('0');
        }
        const removed = await page.request.delete(`/api/meta/dict/${item.pid}`, { headers });
        expect((await removed.json()).code).toBe('0');
        const persisted = await page.request.get(`/api/meta/dict/${item.pid}`);
        expect((await persisted.json()).data.status).toBe('disabled');
      } catch (error) {
        cleanupErrors.push(`${item.pid}: ${String(error)}`);
      }
    }
    await info.attach('dictionary-wire-observations', {
      body: JSON.stringify(
        {
          claim: 'json-response-structure-and-listed-business-assertions',
          formatValidation: 'not-performed',
          registered: catalog.profiles.dictionary.operations.length,
          observed: [...observed].sort(),
          owned,
          rows,
          cleanupErrors,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });
    expect(
      cleanupErrors,
      'All owned fixtures must be cleaned independently of schema validation',
    ).toEqual([]);
  }
});
