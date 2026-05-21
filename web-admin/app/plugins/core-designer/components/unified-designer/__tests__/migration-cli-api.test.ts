import { spawn } from 'node:child_process';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

interface CliResult {
  code: number | null;
  stdout: string;
  stderr: string;
}

interface RecordedRequest {
  method: string;
  url: string;
  body?: unknown;
}

const tempRoots: string[] = [];
const closeServers: Array<() => Promise<void>> = [];

afterEach(async () => {
  await Promise.all(closeServers.splice(0).map((close) => close()));
  await Promise.all(tempRoots.splice(0).map((path) => rm(path, { recursive: true, force: true })));
});

describe('migrate-page-schema-v3 API CLI', () => {
  it('plans page migrations in dry-run mode without writing back to the API', async () => {
    const requests: RecordedRequest[] = [];
    const server = await createMockApiServer(requests, async (req, res) => {
      if (req.method === 'GET' && req.url?.startsWith('/api/pages?')) {
        return json(res, {
          code: '0',
          data: {
            records: [{ pid: 'page_1', pageKey: 'legacy_form' }],
            total: 1,
            page: 1,
            pageSize: 100,
            totalPages: 1,
          },
        });
      }
      if (req.method === 'GET' && req.url === '/api/pages/page_1') {
        return json(res, {
          code: '0',
          data: {
            pid: 'page_1',
            name: 'legacy_form',
            pageKey: 'legacy_form',
            title: 'Legacy Form',
            kind: 'form',
            modelCode: 'customer',
            schemaVersion: 2,
            blocks: [{ blockType: 'form-section', fields: ['name'] }],
          },
        });
      }
      return json(res, { code: '404', message: `Unexpected ${req.method} ${req.url}` }, 404);
    });
    const reportPath = await tempReportPath();

    const result = await runMigrationCli([
      '--api-base-url',
      server.baseUrl,
      '--api-resource',
      'pages',
      '--report',
      reportPath,
      '--dry-run',
    ]);

    expect(result).toMatchObject({ code: 0, stderr: '' });
    expect(requests.filter((request) => request.method === 'PUT')).toEqual([]);

    const report = await readJson(reportPath);
    expect(report).toMatchObject({
      schemaVersion: 3,
      mode: 'api',
      resource: 'pages',
      dryRun: true,
      total: 1,
      migrated: 0,
      dryRunCount: 1,
      failed: 0,
    });
    expect(report.results).toEqual([
      expect.objectContaining({
        status: 'dry-run',
        pid: 'page_1',
        kind: 'form',
        id: 'legacy_form',
      }),
    ]);
  });

  it('continues API page migration after a failed page and writes the remaining V3 pages', async () => {
    const requests: RecordedRequest[] = [];
    const server = await createMockApiServer(requests, async (req, res) => {
      if (req.method === 'GET' && req.url?.startsWith('/api/pages?')) {
        return json(res, {
          code: '0',
          data: {
            records: [
              { pid: 'page_bad', pageKey: 'bad_page' },
              { pid: 'page_ok', pageKey: 'ok_page' },
            ],
            total: 2,
            page: 1,
            pageSize: 100,
            totalPages: 1,
          },
        });
      }
      if (req.method === 'GET' && req.url === '/api/pages/page_bad') {
        return json(res, { code: '500', message: 'broken legacy page' }, 500);
      }
      if (req.method === 'GET' && req.url === '/api/pages/page_ok') {
        return json(res, {
          code: '0',
          data: {
            pid: 'page_ok',
            name: 'ok_page',
            pageKey: 'ok_page',
            title: 'OK Page',
            kind: 'list',
            schemaVersion: 2,
            blocks: [{ blockType: 'table', columns: ['name'] }],
          },
        });
      }
      if (req.method === 'PUT' && req.url === '/api/pages/page_ok') {
        const body = await readRequestBody(req);
        expect(body).toMatchObject({
          pageKey: 'ok_page',
          kind: 'list',
          schemaVersion: 3,
          blocks: [expect.objectContaining({ blockType: 'list' })],
        });
        return json(res, { code: '0', data: { pid: 'page_ok', ...body } });
      }
      return json(res, { code: '404', message: `Unexpected ${req.method} ${req.url}` }, 404);
    });
    const reportPath = await tempReportPath();

    const result = await runMigrationCli([
      '--api-base-url',
      server.baseUrl,
      '--api-resource',
      'pages',
      '--report',
      reportPath,
      '--apply',
      '--continue-on-error',
    ]);

    expect(result.code).toBe(1);
    expect(result.stderr).toContain('V3 API migration completed with 1 failure');
    expect(requests.filter((request) => request.method === 'PUT')).toHaveLength(1);

    const report = await readJson(reportPath);
    expect(report).toMatchObject({
      mode: 'api',
      resource: 'pages',
      dryRun: false,
      total: 2,
      migrated: 1,
      failed: 1,
    });
    expect(report.results).toEqual([
      expect.objectContaining({ pid: 'page_bad', status: 'failed', error: 'broken legacy page' }),
      expect.objectContaining({ pid: 'page_ok', status: 'migrated', kind: 'list', id: 'ok_page' }),
    ]);
  });

  it('converts dashboard resources into PageSchema V3 pages and creates missing page records', async () => {
    const requests: RecordedRequest[] = [];
    const server = await createMockApiServer(requests, async (req, res) => {
      if (req.method === 'GET' && req.url?.startsWith('/api/dashboards?')) {
        return json(res, {
          code: '0',
          data: {
            records: [{ pid: 'dash_1', code: 'ops_dashboard' }],
            total: 1,
            current: 1,
            size: 100,
            pages: 1,
          },
        });
      }
      if (req.method === 'GET' && req.url === '/api/dashboards/dash_1') {
        return json(res, {
          code: '0',
          data: {
            pid: 'dash_1',
            code: 'ops_dashboard',
            title: 'Ops Dashboard',
            layoutConfig: { columns: 12, rowHeight: 80, gap: 16 },
            widgets: [{ id: 'revenue', type: 'number', x: 0, y: 0, w: 3, h: 2, config: { metric: 'revenue' } }],
          },
        });
      }
      if (req.method === 'GET' && req.url === '/api/pages/page-key/ops_dashboard') {
        return json(res, { code: '404', message: 'page not found' }, 404);
      }
      if (req.method === 'POST' && req.url === '/api/pages') {
        const body = await readRequestBody(req);
        expect(body).toMatchObject({
          name: 'ops_dashboard',
          pageKey: 'ops_dashboard',
          title: 'Ops Dashboard',
          kind: 'dashboard',
          schemaVersion: 3,
          blocks: [expect.objectContaining({ blockType: 'dashboard' })],
        });
        return json(res, { code: '0', data: { pid: 'page_ops_dashboard', ...body } });
      }
      return json(res, { code: '404', message: `Unexpected ${req.method} ${req.url}` }, 404);
    });
    const reportPath = await tempReportPath();

    const result = await runMigrationCli([
      '--api-base-url',
      server.baseUrl,
      '--api-resource',
      'dashboards',
      '--report',
      reportPath,
      '--apply',
    ]);

    expect(result).toMatchObject({ code: 0, stderr: '' });
    expect(requests.filter((request) => request.method === 'POST' && request.url === '/api/pages')).toHaveLength(1);

    const report = await readJson(reportPath);
    expect(report).toMatchObject({
      mode: 'api',
      resource: 'dashboards',
      total: 1,
      migrated: 1,
      failed: 0,
    });
    expect(report.results).toEqual([
      expect.objectContaining({
        pid: 'dash_1',
        status: 'migrated',
        kind: 'dashboard',
        id: 'ops_dashboard',
        target: expect.stringContaining('/api/pages'),
      }),
    ]);
  });

  it('limits API page apply to the selected page key before fetching details', async () => {
    const requests: RecordedRequest[] = [];
    const server = await createMockApiServer(requests, async (req, res) => {
      if (req.method === 'GET' && req.url?.startsWith('/api/pages?')) {
        return json(res, {
          code: '0',
          data: {
            records: [
              { pid: 'page_skip', pageKey: 'skip_page' },
              { pid: 'page_target', pageKey: 'target_page' },
              { pid: 'page_extra', pageKey: 'extra_page' },
            ],
            total: 3,
            page: 1,
            pageSize: 100,
            totalPages: 1,
          },
        });
      }
      if (req.method === 'GET' && req.url === '/api/pages/page_target') {
        return json(res, {
          code: '0',
          data: {
            pid: 'page_target',
            name: 'target_page',
            pageKey: 'target_page',
            title: 'Target Page',
            kind: 'form',
            schemaVersion: 2,
            blocks: [{ blockType: 'form-section', fields: ['name'] }],
          },
        });
      }
      if (req.method === 'PUT' && req.url === '/api/pages/page_target') {
        const body = await readRequestBody(req);
        expect(body).toMatchObject({
          pageKey: 'target_page',
          schemaVersion: 3,
          blocks: [expect.objectContaining({ blockType: 'form' })],
        });
        return json(res, { code: '0', data: { pid: 'page_target', ...body } });
      }
      return json(res, { code: '404', message: `Unexpected ${req.method} ${req.url}` }, 404);
    });
    const reportPath = await tempReportPath();

    const result = await runMigrationCli([
      '--api-base-url',
      server.baseUrl,
      '--api-resource',
      'pages',
      '--page-key',
      'target_page',
      '--limit',
      '1',
      '--report',
      reportPath,
      '--apply',
    ]);

    expect(result).toMatchObject({ code: 0, stderr: '' });
    expect(requests.filter((request) => request.url === '/api/pages/page_skip')).toEqual([]);
    expect(requests.filter((request) => request.url === '/api/pages/page_extra')).toEqual([]);
    expect(requests.filter((request) => request.method === 'PUT')).toHaveLength(1);

    const report = await readJson(reportPath);
    expect(report).toMatchObject({
      resource: 'pages',
      total: 1,
      migrated: 1,
      failed: 0,
    });
    expect(report.results).toEqual([
      expect.objectContaining({
        pid: 'page_target',
        pageKey: 'target_page',
        status: 'migrated',
      }),
    ]);
  });

  it('filters dashboard API migration by source pid before creating V3 pages', async () => {
    const requests: RecordedRequest[] = [];
    const server = await createMockApiServer(requests, async (req, res) => {
      if (req.method === 'GET' && req.url?.startsWith('/api/dashboards?')) {
        return json(res, {
          code: '0',
          data: {
            records: [
              { pid: 'dash_skip', code: 'skip_dashboard' },
              { pid: 'dash_target', code: 'target_dashboard' },
            ],
            total: 2,
            current: 1,
            size: 100,
            pages: 1,
          },
        });
      }
      if (req.method === 'GET' && req.url === '/api/dashboards/dash_target') {
        return json(res, {
          code: '0',
          data: {
            pid: 'dash_target',
            code: 'target_dashboard',
            title: 'Target Dashboard',
            layoutConfig: { columns: 12 },
            widgets: [{ id: 'w1', type: 'number' }],
          },
        });
      }
      if (req.method === 'GET' && req.url === '/api/pages/page-key/target_dashboard') {
        return json(res, { code: '404', message: 'page not found' }, 404);
      }
      if (req.method === 'POST' && req.url === '/api/pages') {
        const body = await readRequestBody(req);
        expect(body).toMatchObject({
          pageKey: 'target_dashboard',
          kind: 'dashboard',
          schemaVersion: 3,
        });
        return json(res, { code: '0', data: { pid: 'page_target_dashboard', ...body } });
      }
      return json(res, { code: '404', message: `Unexpected ${req.method} ${req.url}` }, 404);
    });
    const reportPath = await tempReportPath();

    const result = await runMigrationCli([
      '--api-base-url',
      server.baseUrl,
      '--api-resource',
      'dashboards',
      '--pid',
      'dash_target',
      '--report',
      reportPath,
      '--apply',
    ]);

    expect(result).toMatchObject({ code: 0, stderr: '' });
    expect(requests.filter((request) => request.url === '/api/dashboards/dash_skip')).toEqual([]);
    expect(requests.filter((request) => request.method === 'POST' && request.url === '/api/pages')).toHaveLength(1);

    const report = await readJson(reportPath);
    expect(report).toMatchObject({
      resource: 'dashboards',
      total: 1,
      migrated: 1,
      failed: 0,
    });
    expect(report.results).toEqual([
      expect.objectContaining({
        pid: 'dash_target',
        pageKey: 'target_dashboard',
        status: 'migrated',
      }),
    ]);
  });
});

async function tempReportPath(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'auraboot-v3-api-migration-'));
  tempRoots.push(root);
  return join(root, 'migration-report.json');
}

async function createMockApiServer(
  requests: RecordedRequest[],
  handler: (req: IncomingMessage, res: ServerResponse) => Promise<void> | void,
): Promise<{ baseUrl: string }> {
  const server = createServer(async (req, res) => {
    requests.push({ method: req.method ?? 'GET', url: req.url ?? '' });
    await handler(req, res);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  closeServers.push(
    () =>
      new Promise<void>((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()));
      }),
  );
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('Failed to bind mock API server.');
  }
  return { baseUrl: `http://127.0.0.1:${address.port}` };
}

function json(res: ServerResponse, value: unknown, status = 200): void {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(value));
}

async function readRequestBody(req: IncomingMessage): Promise<any> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

async function readJson(path: string): Promise<any> {
  return JSON.parse(await readFile(path, 'utf8'));
}

async function runMigrationCli(args: string[]): Promise<CliResult> {
  return new Promise((resolve, reject) => {
    const child = spawn('pnpm', ['exec', 'tsx', 'scripts/migrate-page-schema-v3.ts', ...args], {
      cwd: process.cwd(),
      env: { ...process.env, NO_COLOR: '1' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk) => {
      stdout += String(chunk);
    });
    child.stderr.on('data', (chunk) => {
      stderr += String(chunk);
    });
    child.on('error', reject);
    child.on('close', (code) => {
      resolve({ code, stdout, stderr });
    });
  });
}
