import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { execSync, spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { resolve } from 'node:path';
import { expect, test } from '@playwright/test';

/**
 * MCP × Cursor end-to-end demonstrator.
 *
 * Simulates exactly what Cursor does at runtime: spawns `aura mcp serve`
 * as a stdio child process, attaches an MCP Client over stdio, and walks
 * an LLM-style prompt through the 5 tool calls that build a `leave_request`
 * module from scratch. Every assertion runs against the real backend
 * (PostgreSQL, REST API, browser). When this spec passes, GAP-300 Layer 1
 * is verifiably end-to-end functional.
 *
 * ⚠️ NOT part of the default OSS test grep — running it requires:
 *   1. AuraBoot platform stack up (`./scripts/reset-and-init.sh`)
 *   2. CLI built (`pnpm --filter @auraboot/plugin-cli build`)
 *   3. AURA_TOKEN env var pointing at a tenant-pinned admin JWT
 *   4. Tenant must NOT already contain `leave_request` model (fresh DB)
 *
 * Run with:
 *   AURA_TOKEN=… npx playwright test mcp-cursor-demo.spec.ts --project=chromium
 *
 * The screencast spec at
 *   auraboot-enterprise/docs/system-reference/screencasts/2026-05-05-mcp-30s-demo.md
 * mirrors the on-screen flow so the marketing video stays aligned with what
 * this Playwright spec actually verifies.
 */

const CLI_DIST = resolve(
  __dirname,
  '../../../plugins/cli/dist/index.js',
);

const PG_HOST = process.env.PGHOST ?? 'localhost';
const PG_PORT = process.env.PGPORT ?? '5432';
const PG_DB = process.env.PGDATABASE ?? 'auraboot';
const PG_USER = process.env.PGUSER ?? 'auraboot';

let mcpProcess: ChildProcessWithoutNullStreams | undefined;
let mcpClient: Client | undefined;

test.describe.configure({ mode: 'serial' });

test.describe('GAP-300 Layer 1 — MCP × Cursor demo (real backend)', () => {
  test.beforeAll(async () => {
    test.skip(!process.env.AURA_TOKEN, 'AURA_TOKEN env required for live MCP smoke');

    const transport = new StdioClientTransport({
      command: 'node',
      args: [CLI_DIST, 'mcp', 'serve'],
      env: {
        ...process.env,
        AURA_API_URL: process.env.AURA_API_URL ?? 'http://localhost:6443',
      } as Record<string, string>,
    });

    mcpClient = new Client(
      { name: 'cursor-demo-spec', version: '1.0.0' },
      { capabilities: {} },
    );
    await mcpClient.connect(transport);
  });

  test.afterAll(async () => {
    await mcpClient?.close().catch(() => undefined);
    mcpProcess?.kill('SIGTERM');
  });

  test('discovers AuraBoot DSL capabilities + existing models', async () => {
    const caps = await mcpClient!.callTool({
      name: 'query_dsl_capabilities',
      arguments: {},
    });
    expect(caps.isError).toBeFalsy();

    const models = await mcpClient!.callTool({
      name: 'query_existing_models',
      arguments: { limit: 200, keyword: 'leave' },
    });
    expect(models.isError).toBeFalsy();
    const text = (models.content as Array<{ text: string }>)[0].text;
    expect(text).not.toMatch(/"code"\s*:\s*"leave_request"/);
  });

  test('create_model leave_request with structured fields', async () => {
    const result = await mcpClient!.callTool({
      name: 'create_model',
      arguments: {
        code: 'leave_request',
        displayName: 'Leave Request',
        modelType: 'entity',
        fields: [
          { code: 'requester', dataType: 'reference', required: true },
          { code: 'start_date', dataType: 'date', required: true },
          { code: 'end_date', dataType: 'date', required: true },
          { code: 'reason', dataType: 'text' },
          { code: 'status', dataType: 'enum' },
        ],
        autoPublish: true,
        dryRun: false,
      },
    });
    expect(result.isError).toBeFalsy();

    // Verify the dynamic table was actually created.
    const tableExists = execSync(
      `psql -h ${PG_HOST} -p ${PG_PORT} -U ${PG_USER} -d ${PG_DB} -At -c "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='mt_leave_request')"`,
      { encoding: 'utf-8' },
    ).trim();
    expect(tableExists).toBe('t');
  });

  test('create_page_schema list + form pages over MCP', async () => {
    for (const kind of ['list', 'form'] as const) {
      const result = await mcpClient!.callTool({
        name: 'create_page_schema',
        arguments: {
          pageKey: `leave_request_${kind}`,
          name: `leave_request_${kind}`,
          title: `Leave Request ${kind}`,
          kind,
          modelCode: 'leave_request',
          blocks:
            kind === 'list'
              ? [
                  { blockType: 'filters', blockId: 'top' },
                  { blockType: 'toolbar', blockId: 'tb' },
                  { blockType: 'table', blockId: 'main' },
                ]
              : [{ blockType: 'form-section', blockId: 'main' }],
          dryRun: false,
        },
      });
      expect(result.isError).toBeFalsy();
    }
  });

  test('audit log records every MCP tool invocation', async () => {
    const auditCount = execSync(
      `psql -h ${PG_HOST} -p ${PG_PORT} -U ${PG_USER} -d ${PG_DB} -At -c "SELECT COUNT(*) FROM ab_command_audit_log WHERE command_code LIKE 'mcp.%'"`,
      { encoding: 'utf-8' },
    ).trim();
    expect(Number(auditCount)).toBeGreaterThanOrEqual(5);
  });

  test('UI: /p/leave_request renders a usable list page', async ({ page }) => {
    // Navigate via sidebar (NOT page.goto direct) to mirror real user.
    // The test seed must include a tenant admin who can see the menu;
    // see scripts/reset-and-init.sh for the demo bootstrap.
    await page.goto(process.env.AURA_WEB_URL ?? 'http://localhost:5173/');
    // Sidebar may take a beat to populate from /api/menu.
    await page.getByRole('link', { name: /leave request/i }).first().click();

    await expect(page).toHaveURL(/\/p\/leave_request/);
    await expect(page.getByRole('button', { name: /new|create|新建/i }).first()).toBeVisible();
    // Filters block + table block both rendered (UI-side proof of V2 schema).
    await expect(page.locator('[data-block-type="filters"]').first()).toBeVisible();
    await expect(page.locator('[data-block-type="table"]').first()).toBeVisible();
  });
});
