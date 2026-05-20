import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

interface CliResult {
  code: number | null;
  stdout: string;
  stderr: string;
}

const tempRoots: string[] = [];

afterEach(async () => {
  await Promise.all(tempRoots.splice(0).map((path) => rm(path, { recursive: true, force: true })));
});

describe('migrate-page-schema-v3 batch CLI', () => {
  it('migrates every JSON document in an input directory and writes an audit report', async () => {
    const root = await createTempRoot();
    const inputDir = join(root, 'input');
    const outputDir = join(root, 'output');
    const reportPath = join(root, 'migration-report.json');
    await mkdir(inputDir);

    await writeJson(join(inputDir, '01-form.json'), {
      id: 'legacy_form',
      kind: 'form',
      title: { zh_CN: '客户表单' },
      modelCode: 'customer',
      blocks: [
        {
          id: 'basic',
          blockType: 'form-section',
          title: { zh_CN: '基础信息' },
          fields: ['name|span:12', { field: 'email', span: 12, widget: 'email' }],
        },
        {
          blockType: 'form-buttons',
          buttons: ['submit'],
        },
      ],
    });
    await writeJson(join(inputDir, '02-dashboard.json'), {
      code: 'ops_dashboard',
      title: { zh_CN: '运营看板' },
      layoutConfig: { columns: 12, rowHeight: 72, gap: 12 },
      widgets: [{ id: 'revenue', type: 'number', x: 0, y: 0, w: 3, h: 2, config: { metric: 'revenue' } }],
    });

    const result = await runMigrationCli([
      '--input-dir',
      inputDir,
      '--output-dir',
      outputDir,
      '--report',
      reportPath,
    ]);

    expect(result).toMatchObject({ code: 0, stderr: '' });

    const migratedForm = await readJson(join(outputDir, '01-form.json'));
    const migratedDashboard = await readJson(join(outputDir, '02-dashboard.json'));
    const report = await readJson(reportPath);

    expect(migratedForm).toMatchObject({ schemaVersion: 3, kind: 'form', id: 'legacy_form' });
    expect(migratedDashboard).toMatchObject({ schemaVersion: 3, kind: 'dashboard', id: 'ops_dashboard' });
    expect(report).toMatchObject({
      schemaVersion: 3,
      mode: 'batch',
      total: 2,
      migrated: 2,
      failed: 0,
    });
    expect(report.results).toEqual([
      expect.objectContaining({
        input: join(inputDir, '01-form.json'),
        output: join(outputDir, '01-form.json'),
        status: 'migrated',
        kind: 'form',
        id: 'legacy_form',
      }),
      expect.objectContaining({
        input: join(inputDir, '02-dashboard.json'),
        output: join(outputDir, '02-dashboard.json'),
        status: 'migrated',
        kind: 'dashboard',
        id: 'ops_dashboard',
      }),
    ]);
  });

  it('continues after a bad document, writes a report, and returns a failing exit code', async () => {
    const root = await createTempRoot();
    const inputDir = join(root, 'input');
    const outputDir = join(root, 'output');
    const reportPath = join(root, 'migration-report.json');
    await mkdir(inputDir);

    await writeJson(join(inputDir, '01-form.json'), {
      id: 'legacy_form',
      kind: 'form',
      blocks: [{ blockType: 'form-section', fields: ['name'] }],
    });
    await writeJson(join(inputDir, '02-invalid.json'), { unknown: true });

    const result = await runMigrationCli([
      '--input-dir',
      inputDir,
      '--output-dir',
      outputDir,
      '--report',
      reportPath,
      '--continue-on-error',
    ]);

    expect(result.code).toBe(1);
    expect(result.stderr).toContain('V3 batch migration completed with 1 failure');

    const migratedForm = await readJson(join(outputDir, '01-form.json'));
    const report = await readJson(reportPath);

    expect(migratedForm).toMatchObject({ schemaVersion: 3, id: 'legacy_form' });
    expect(report).toMatchObject({
      mode: 'batch',
      total: 2,
      migrated: 1,
      failed: 1,
    });
    expect(report.results).toEqual([
      expect.objectContaining({ input: join(inputDir, '01-form.json'), status: 'migrated' }),
      expect.objectContaining({ input: join(inputDir, '02-invalid.json'), status: 'failed' }),
    ]);
    expect(report.results[1].error).toContain('Cannot auto-detect migration input type');
  });
});

async function createTempRoot(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'auraboot-v3-migration-'));
  tempRoots.push(root);
  return root;
}

async function writeJson(path: string, value: unknown): Promise<void> {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
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
