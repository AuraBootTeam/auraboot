#!/usr/bin/env tsx

import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  migrateDashboardResourceToV3,
  migratePageSchemaV2ToV3,
} from '../app/plugins/core-designer/components/unified-designer/migration/migrateToV3';
import { validatePageSchemaV3 } from '../app/plugins/core-designer/components/unified-designer/validation/validatePageSchemaV3';
import type {
  LegacyDashboardResource,
  LegacyPageSchemaV2,
  PageSchemaV3,
} from '../app/plugins/core-designer/components/unified-designer/types';

type MigrationType = 'auto' | 'page' | 'dashboard';
type ApiMigrationResource = 'pages' | 'dashboards';
type MigrationMode = 'batch' | 'api';

interface CliArgs {
  input?: string;
  output?: string;
  inputDir?: string;
  outputDir?: string;
  report?: string;
  apiBaseUrl?: string;
  apiResource: ApiMigrationResource;
  apply: boolean;
  dryRun: boolean;
  pageSize: number;
  limit?: number;
  pid?: string;
  pageKey?: string;
  authToken?: string;
  cookie?: string;
  type: MigrationType;
  continueOnError: boolean;
}

interface MigrationReport {
  schemaVersion: 3;
  mode: MigrationMode;
  resource?: ApiMigrationResource;
  dryRun?: boolean;
  total: number;
  migrated: number;
  dryRunCount?: number;
  skipped?: number;
  failed: number;
  results: MigrationReportEntry[];
}

interface MigrationReportEntry {
  input: string;
  output?: string;
  target?: string;
  status: 'migrated' | 'failed' | 'dry-run' | 'skipped';
  pid?: string;
  pageKey?: string;
  kind?: PageSchemaV3['kind'] | 'array';
  id?: string;
  error?: string;
}

interface ApiResponse<T> {
  code?: string;
  message?: string;
  desc?: string;
  data?: T | null;
}

interface ApiPageResult<T> {
  records?: T[];
  total?: number;
  page?: number;
  pageSize?: number;
  totalPages?: number;
  current?: number;
  size?: number;
  pages?: number;
}

interface ApiPageSummary {
  pid?: string;
  pageKey?: string;
  code?: string;
}

interface LegacyPageSchemaApiDocument extends LegacyPageSchemaV2 {
  pid?: string;
  name?: string;
  description?: string;
  metaInfo?: Record<string, unknown>;
  isTemplate?: boolean;
  templateCategory?: string;
  sortWeight?: number;
  tags?: Record<string, unknown>;
  semver?: string;
}

class CliExitError extends Error {
  constructor(
    message: string,
    readonly exitCode: number,
  ) {
    super(message);
  }
}

const USAGE = `Usage:
  pnpm tsx scripts/migrate-page-schema-v3.ts --input legacy.json --output page-schema-v3.json [--type auto|page|dashboard]
  pnpm tsx scripts/migrate-page-schema-v3.ts --input-dir legacy-pages --output-dir page-schema-v3 --report report.json [--type auto|page|dashboard] [--continue-on-error]
  pnpm tsx scripts/migrate-page-schema-v3.ts --api-base-url http://localhost:5226 --api-resource pages|dashboards --report report.json [--dry-run|--apply] [--pid sourcePid] [--page-key pageKeyOrDashboardCode] [--limit n] [--continue-on-error]

Notes:
  - --type defaults to auto.
  - --input-dir migrates every *.json file in lexical order.
  - API migration defaults to --dry-run; --apply is required to write V3 back.
  - --pid, --page-key, and --limit scope API migration before resource details are fetched.
  - --continue-on-error keeps processing the directory but still exits non-zero when any file fails.
  - Input can be a single JSON object or an array.
  - Existing PageSchema V3 documents are validated and emitted unchanged.`;

export async function runPageSchemaV3MigrationCli(
  argv: string[] = process.argv.slice(2),
): Promise<number> {
  await runCli(argv);
  return 0;
}

async function main(): Promise<void> {
  const exitCode = await runPageSchemaV3MigrationCli(process.argv.slice(2));
  process.exitCode = exitCode;
}

async function runCli(argv: string[]): Promise<void> {
  const args = parseArgs(argv);
  validateCliArgs(args);

  if (args.apiBaseUrl) {
    await runApiMigration(args);
    return;
  }

  if (args.inputDir) {
    await runBatchMigration(args);
    return;
  }

  if (!args.input) {
    throw new Error(`${USAGE}\n\nMissing required --input.`);
  }

  const inputPath = resolve(process.cwd(), args.input);
  const raw = await readFile(inputPath, 'utf8');
  const source = JSON.parse(raw) as unknown;
  const migrated = migrateInput(source, args.type);
  validateMigrationOutput(migrated);

  const output = `${JSON.stringify(migrated, null, 2)}\n`;
  if (args.output) {
    const outputPath = resolve(process.cwd(), args.output);
    await ensureParentDir(outputPath);
    await writeFile(outputPath, output, 'utf8');
  } else {
    process.stdout.write(output);
  }
}

function parseArgs(argv: string[]): CliArgs {
  const args: CliArgs = {
    type: 'auto',
    apiResource: 'pages',
    apply: false,
    dryRun: false,
    pageSize: 100,
    continueOnError: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--') {
      continue;
    }
    if (arg === '--help' || arg === '-h') {
      process.stdout.write(`${USAGE}\n`);
      process.exit(0);
    }
    if (arg === '--input' || arg === '-i') {
      args.input = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--output' || arg === '-o') {
      args.output = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--input-dir') {
      args.inputDir = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--output-dir') {
      args.outputDir = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--report') {
      args.report = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--api-base-url') {
      args.apiBaseUrl = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--api-resource') {
      args.apiResource = parseApiResource(readArgValue(argv, index, arg));
      index += 1;
      continue;
    }
    if (arg === '--page-size') {
      args.pageSize = parsePositiveInteger(readArgValue(argv, index, arg), '--page-size');
      index += 1;
      continue;
    }
    if (arg === '--limit') {
      args.limit = parsePositiveInteger(readArgValue(argv, index, arg), '--limit');
      index += 1;
      continue;
    }
    if (arg === '--pid') {
      args.pid = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--page-key') {
      args.pageKey = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--auth-token') {
      args.authToken = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--cookie') {
      args.cookie = readArgValue(argv, index, arg);
      index += 1;
      continue;
    }
    if (arg === '--type') {
      args.type = parseMigrationType(readArgValue(argv, index, arg));
      index += 1;
      continue;
    }
    if (arg === '--apply') {
      args.apply = true;
      continue;
    }
    if (arg === '--dry-run') {
      args.dryRun = true;
      continue;
    }
    if (arg === '--continue-on-error') {
      args.continueOnError = true;
      continue;
    }
    throw new Error(`${USAGE}\n\nUnknown argument: ${arg}`);
  }

  return args;
}

function readArgValue(argv: string[], index: number, flag: string): string {
  const value = argv[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`${USAGE}\n\nMissing value for ${flag}.`);
  }
  return value;
}

function parseMigrationType(value: string | undefined): MigrationType {
  if (value === 'auto' || value === 'page' || value === 'dashboard') return value;
  throw new Error(`${USAGE}\n\nInvalid --type: ${String(value)}`);
}

function parseApiResource(value: string | undefined): ApiMigrationResource {
  if (value === 'pages' || value === 'dashboards') return value;
  throw new Error(`${USAGE}\n\nInvalid --api-resource: ${String(value)}`);
}

function parsePositiveInteger(value: string, flag: string): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${USAGE}\n\nInvalid ${flag}: ${value}`);
  }
  return parsed;
}

function validateCliArgs(args: CliArgs): void {
  if (args.apply && args.dryRun) {
    throw new Error(`${USAGE}\n\nUse either --apply or --dry-run, not both.`);
  }
  if (args.apiBaseUrl) {
    if (args.input || args.inputDir || args.output || args.outputDir) {
      throw new Error(
        `${USAGE}\n\nAPI migration cannot be combined with --input, --input-dir, --output, or --output-dir.`,
      );
    }
    args.dryRun = !args.apply;
    return;
  }
  if (
    args.apply ||
    args.dryRun ||
    args.authToken ||
    args.cookie ||
    args.limit ||
    args.pid ||
    args.pageKey
  ) {
    throw new Error(
      `${USAGE}\n\n--apply, --dry-run, --auth-token, --cookie, --limit, --pid, and --page-key are only valid with --api-base-url.`,
    );
  }
  if (args.input && args.inputDir) {
    throw new Error(`${USAGE}\n\nUse either --input or --input-dir, not both.`);
  }
  if (args.output && args.outputDir) {
    throw new Error(`${USAGE}\n\nUse either --output or --output-dir, not both.`);
  }
  if (args.inputDir && !args.outputDir) {
    throw new Error(`${USAGE}\n\n--input-dir requires --output-dir.`);
  }
  if (!args.input && !args.inputDir) {
    throw new Error(`${USAGE}\n\nMissing required --input or --input-dir.`);
  }
}

async function runBatchMigration(args: CliArgs): Promise<void> {
  if (!args.inputDir || !args.outputDir) return;

  const inputDir = resolve(process.cwd(), args.inputDir);
  const outputDir = resolve(process.cwd(), args.outputDir);
  await mkdir(outputDir, { recursive: true });

  const entries = (await readdir(inputDir, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
    .map((entry) => entry.name)
    .sort((left, right) => left.localeCompare(right));

  if (!entries.length) {
    throw new Error(`No *.json files found in input directory: ${inputDir}`);
  }

  const results: MigrationReportEntry[] = [];
  for (const entry of entries) {
    const inputPath = join(inputDir, entry);
    const outputPath = join(outputDir, basename(entry));

    try {
      const raw = await readFile(inputPath, 'utf8');
      const source = JSON.parse(raw) as unknown;
      const migrated = migrateInput(source, args.type);
      validateMigrationOutput(migrated);
      await writeFile(outputPath, `${JSON.stringify(migrated, null, 2)}\n`, 'utf8');
      results.push({
        input: inputPath,
        output: outputPath,
        status: 'migrated',
        kind: describeMigrationKind(migrated),
        id: describeMigrationId(migrated),
      });
    } catch (error) {
      results.push({
        input: inputPath,
        output: outputPath,
        status: 'failed',
        error: error instanceof Error ? error.message : String(error),
      });

      if (!args.continueOnError) {
        break;
      }
    }
  }

  const report = buildBatchReport(entries.length, results);
  if (args.report) {
    const reportPath = resolve(process.cwd(), args.report);
    await ensureParentDir(reportPath);
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  }

  if (report.failed > 0) {
    const suffix = args.report ? ` Report: ${resolve(process.cwd(), args.report)}` : '';
    throw new CliExitError(
      `V3 batch migration completed with ${report.failed} failure${report.failed === 1 ? '' : 's'}.${suffix}`,
      1,
    );
  }
}

async function runApiMigration(args: CliArgs): Promise<void> {
  const results =
    args.apiResource === 'dashboards'
      ? await migrateDashboardsFromApi(args)
      : await migratePagesFromApi(args);

  const report = buildApiReport(args.apiResource, args.dryRun, results);
  if (args.report) {
    const reportPath = resolve(process.cwd(), args.report);
    await ensureParentDir(reportPath);
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  } else {
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  }

  if (report.failed > 0) {
    const suffix = args.report ? ` Report: ${resolve(process.cwd(), args.report)}` : '';
    throw new CliExitError(
      `V3 API migration completed with ${report.failed} failure${report.failed === 1 ? '' : 's'}.${suffix}`,
      1,
    );
  }
}

async function migratePagesFromApi(args: CliArgs): Promise<MigrationReportEntry[]> {
  const pages = filterApiSummaries(await listApiResources<ApiPageSummary>(args, 'pages'), args);
  const results: MigrationReportEntry[] = [];

  for (const page of pages) {
    const pid = requirePid(page, 'page');
    const input = apiUrl(args, `/api/pages/${encodeURIComponent(pid)}`).toString();
    const target = input;

    try {
      const dto = await apiRequest<LegacyPageSchemaApiDocument>(
        args,
        `/api/pages/${encodeURIComponent(pid)}`,
      );
      if (dto.schemaVersion === 3) {
        results.push({
          input,
          target,
          status: 'skipped',
          pid,
          pageKey: dto.pageKey,
          kind: normalizeKind(dto.kind),
          id: dto.pageKey || dto.id || dto.name || pid,
        });
        continue;
      }

      const migrated = pageDtoToV3(dto);
      validateMigrationOutput(migrated);

      if (args.dryRun) {
        results.push({
          input,
          target,
          status: 'dry-run',
          pid,
          pageKey: migrated.pageKey,
          kind: migrated.kind,
          id: migrated.id,
        });
        continue;
      }

      await apiRequest(args, `/api/pages/${encodeURIComponent(pid)}`, {
        method: 'PUT',
        body: JSON.stringify(pageV3ToUpdateRequest(migrated, dto)),
      });
      results.push({
        input,
        target,
        status: 'migrated',
        pid,
        pageKey: migrated.pageKey,
        kind: migrated.kind,
        id: migrated.id,
      });
    } catch (error) {
      results.push({
        input,
        target,
        status: 'failed',
        pid,
        pageKey: page.pageKey,
        error: error instanceof Error ? error.message : String(error),
      });

      if (!args.continueOnError) {
        break;
      }
    }
  }

  return results;
}

async function migrateDashboardsFromApi(args: CliArgs): Promise<MigrationReportEntry[]> {
  const dashboards = filterApiSummaries(
    await listApiResources<ApiPageSummary>(args, 'dashboards'),
    args,
  );
  const results: MigrationReportEntry[] = [];

  for (const dashboard of dashboards) {
    const pid = requirePid(dashboard, 'dashboard');
    const input = apiUrl(args, `/api/dashboards/${encodeURIComponent(pid)}`).toString();

    try {
      const dto = await apiRequest<Record<string, unknown>>(
        args,
        `/api/dashboards/${encodeURIComponent(pid)}`,
      );
      const migrated = migrateDashboardResourceToV3(dto as unknown as LegacyDashboardResource);
      validateMigrationOutput(migrated);
      const pageKey = migrated.pageKey || migrated.id;

      if (args.dryRun) {
        results.push({
          input,
          target: apiUrl(args, '/api/pages').toString(),
          status: 'dry-run',
          pid,
          pageKey,
          kind: migrated.kind,
          id: migrated.id,
        });
        continue;
      }

      const existingPage = await findPageByPageKey(args, pageKey);
      const targetPath = existingPage?.pid
        ? `/api/pages/${encodeURIComponent(existingPage.pid)}`
        : '/api/pages';
      await apiRequest(args, targetPath, {
        method: existingPage?.pid ? 'PUT' : 'POST',
        body: JSON.stringify(
          existingPage?.pid
            ? pageV3ToUpdateRequest(migrated, existingPage as LegacyPageSchemaApiDocument)
            : pageV3ToCreateRequest(migrated),
        ),
      });

      results.push({
        input,
        target: apiUrl(args, targetPath).toString(),
        status: 'migrated',
        pid,
        pageKey,
        kind: migrated.kind,
        id: migrated.id,
      });
    } catch (error) {
      results.push({
        input,
        target: apiUrl(args, '/api/pages').toString(),
        status: 'failed',
        pid,
        pageKey: dashboard.code || dashboard.pageKey,
        error: error instanceof Error ? error.message : String(error),
      });

      if (!args.continueOnError) {
        break;
      }
    }
  }

  return results;
}

async function listApiResources<T extends ApiPageSummary>(
  args: CliArgs,
  resource: ApiMigrationResource,
): Promise<T[]> {
  const records: T[] = [];
  let page = resource === 'dashboards' ? 0 : 1;
  let fetchedPages = 0;
  let totalPages = 1;

  do {
    const path =
      resource === 'dashboards'
        ? `/api/dashboards?page=${page}&size=${args.pageSize}`
        : `/api/pages?pageNum=${page}&pageSize=${args.pageSize}`;
    const data = await apiRequest<ApiPageResult<T>>(args, path);
    records.push(...(data.records ?? []));
    totalPages = normalizeTotalPages(data);
    fetchedPages += 1;
    page += 1;
  } while (fetchedPages < totalPages);

  return records;
}

function filterApiSummaries<T extends ApiPageSummary>(records: T[], args: CliArgs): T[] {
  const filtered = records.filter((record) => {
    if (args.pid && record.pid !== args.pid) return false;
    if (args.pageKey && record.pageKey !== args.pageKey && record.code !== args.pageKey)
      return false;
    return true;
  });
  return args.limit ? filtered.slice(0, args.limit) : filtered;
}

function normalizeTotalPages<T>(data: ApiPageResult<T>): number {
  return Math.max(1, Number(data.totalPages ?? data.pages ?? 1));
}

async function findPageByPageKey(
  args: CliArgs,
  pageKey: string,
): Promise<LegacyPageSchemaApiDocument | null> {
  const result = await apiTryRequest<LegacyPageSchemaApiDocument>(
    args,
    `/api/pages/page-key/${encodeURIComponent(pageKey)}`,
  );
  return result.ok ? result.data : null;
}

async function apiTryRequest<T>(
  args: CliArgs,
  path: string,
  init?: RequestInit,
): Promise<{ ok: true; data: T } | { ok: false; error: string }> {
  try {
    return { ok: true, data: await apiRequest<T>(args, path, init) };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  }
}

async function apiRequest<T>(args: CliArgs, path: string, init: RequestInit = {}): Promise<T> {
  const url = apiUrl(args, path);
  const response = await fetch(url, {
    ...init,
    headers: {
      accept: 'application/json',
      ...(init.body ? { 'content-type': 'application/json' } : {}),
      ...(args.authToken ? { authorization: `Bearer ${args.authToken}` } : {}),
      ...(args.cookie ? { cookie: args.cookie } : {}),
      ...(init.headers ?? {}),
    },
  });
  const body = (await response.json().catch(() => null)) as ApiResponse<T> | null;

  if (!response.ok || body?.code !== '0') {
    throw new Error(body?.message || body?.desc || `API request failed: ${response.status} ${url}`);
  }
  if (body.data === undefined || body.data === null) {
    throw new Error(`API response has no data: ${url}`);
  }
  return body.data;
}

function apiUrl(args: CliArgs, path: string): URL {
  return new URL(path, normalizeApiBaseUrl(args));
}

function normalizeApiBaseUrl(args: CliArgs): string {
  if (!args.apiBaseUrl) {
    throw new Error('Missing --api-base-url.');
  }
  return args.apiBaseUrl.endsWith('/') ? args.apiBaseUrl : `${args.apiBaseUrl}/`;
}

function requirePid(summary: ApiPageSummary, resource: string): string {
  if (typeof summary.pid === 'string' && summary.pid) return summary.pid;
  throw new Error(`Cannot migrate ${resource} without pid.`);
}

function migrateInput(source: unknown, type: MigrationType): PageSchemaV3 | PageSchemaV3[] {
  if (Array.isArray(source)) {
    return source.map((item) => migrateDocument(item, type));
  }
  return migrateDocument(source, type);
}

function migrateDocument(source: unknown, type: MigrationType): PageSchemaV3 {
  if (!isRecord(source)) {
    throw new Error('Migration input must be a JSON object or an array of JSON objects.');
  }

  if (isPageSchemaV3(source)) {
    return source;
  }

  const resolvedType = type === 'auto' ? detectMigrationType(source) : type;
  if (resolvedType === 'dashboard') {
    return migrateDashboardResourceToV3(source as LegacyDashboardResource);
  }
  return migratePageSchemaV2ToV3(source as unknown as LegacyPageSchemaV2);
}

function pageDtoToV3(dto: LegacyPageSchemaApiDocument): PageSchemaV3 {
  if (dto.schemaVersion === 3 || hasRecursiveV3Blocks(dto.blocks)) {
    return {
      schemaVersion: 3,
      kind: normalizeKind(dto.kind),
      id: dto.pageKey || dto.id || dto.name || dto.pid || 'page',
      pageKey: dto.pageKey,
      modelCode: dto.modelCode,
      title: dto.title,
      layout: dto.layout,
      blocks: (dto.blocks ?? []) as PageSchemaV3['blocks'],
      extension: dto.extension,
    };
  }

  return migratePageSchemaV2ToV3({
    schemaVersion: dto.schemaVersion,
    kind: dto.kind,
    id: dto.pageKey || dto.id || dto.name || dto.pid || 'page',
    pageKey: dto.pageKey,
    modelCode: dto.modelCode,
    title: dto.title,
    layout: dto.layout,
    blocks: dto.blocks,
    extension: dto.extension,
  });
}

function pageV3ToUpdateRequest(
  document: PageSchemaV3,
  source: LegacyPageSchemaApiDocument,
): Record<string, unknown> {
  return {
    name: document.pageKey || source.pageKey || source.name || document.id,
    pageKey: document.pageKey || source.pageKey || document.id,
    title: document.title ?? source.title,
    description: source.description,
    kind: document.kind,
    blocks: document.blocks,
    layout: document.layout,
    schemaVersion: 3,
    metaInfo: source.metaInfo,
    isTemplate: source.isTemplate,
    templateCategory: source.templateCategory,
    sortWeight: source.sortWeight,
    tags: source.tags,
    semver: source.semver,
    extension: document.extension ?? source.extension,
  };
}

function pageV3ToCreateRequest(document: PageSchemaV3): Record<string, unknown> {
  const pageKey = document.pageKey || document.id;
  return {
    name: pageKey,
    pageKey,
    title: resolveTitle(document.title, pageKey),
    kind: document.kind,
    blocks: document.blocks,
    layout: document.layout,
    schemaVersion: 3,
    extension: document.extension,
  };
}

function resolveTitle(title: PageSchemaV3['title'], fallback: string): string {
  if (!title) return fallback;
  if (typeof title === 'string') return title;
  return title.en || title['zh-CN'] || fallback;
}

function normalizeKind(kind: unknown): PageSchemaV3['kind'] {
  if (
    kind === 'list' ||
    kind === 'detail' ||
    kind === 'form' ||
    kind === 'dashboard' ||
    kind === 'composite'
  ) {
    return kind;
  }
  return 'composite';
}

function detectMigrationType(source: Record<string, unknown>): Exclude<MigrationType, 'auto'> {
  if (Array.isArray(source.widgets) || isRecord(source.layoutConfig)) return 'dashboard';
  if (typeof source.kind === 'string' || Array.isArray(source.blocks)) return 'page';
  throw new Error('Cannot auto-detect migration input type. Pass --type page or --type dashboard.');
}

function collectSchemas(output: PageSchemaV3 | PageSchemaV3[]): PageSchemaV3[] {
  return Array.isArray(output) ? output : [output];
}

function validateMigrationOutput(output: PageSchemaV3 | PageSchemaV3[]): void {
  const schemas = collectSchemas(output);

  const failures = schemas.flatMap((schema, index) => {
    const result = validatePageSchemaV3(schema);
    return result.valid
      ? []
      : result.errors.map((error) => ({
          index,
          ...error,
        }));
  });

  if (failures.length) {
    throw new Error(`V3 migration output is invalid:\n${JSON.stringify(failures, null, 2)}`);
  }
}

function buildBatchReport(total: number, results: MigrationReportEntry[]): MigrationReport {
  const migrated = results.filter((result) => result.status === 'migrated').length;
  const failed = results.filter((result) => result.status === 'failed').length;
  return {
    schemaVersion: 3,
    mode: 'batch',
    total,
    migrated,
    failed,
    results,
  };
}

function buildApiReport(
  resource: ApiMigrationResource,
  dryRun: boolean,
  results: MigrationReportEntry[],
): MigrationReport {
  const migrated = results.filter((result) => result.status === 'migrated').length;
  const dryRunCount = results.filter((result) => result.status === 'dry-run').length;
  const skipped = results.filter((result) => result.status === 'skipped').length;
  const failed = results.filter((result) => result.status === 'failed').length;
  return {
    schemaVersion: 3,
    mode: 'api',
    resource,
    dryRun,
    total: results.length,
    migrated,
    dryRunCount,
    skipped,
    failed,
    results,
  };
}

function describeMigrationKind(
  output: PageSchemaV3 | PageSchemaV3[],
): PageSchemaV3['kind'] | 'array' {
  return Array.isArray(output) ? 'array' : output.kind;
}

function describeMigrationId(output: PageSchemaV3 | PageSchemaV3[]): string {
  return Array.isArray(output) ? `${output.length} documents` : output.id;
}

function isPageSchemaV3(source: unknown): source is PageSchemaV3 {
  return isRecord(source) && source.schemaVersion === 3 && Array.isArray(source.blocks);
}

function hasRecursiveV3Blocks(blocks: unknown): boolean {
  if (!Array.isArray(blocks) || blocks.length === 0) return false;
  return blocks.some((block) => {
    if (!isRecord(block)) return false;
    return (
      typeof block.id === 'string' &&
      typeof block.blockType === 'string' &&
      Array.isArray(block.blocks) &&
      ['list', 'detail', 'form', 'dashboard', 'composite'].includes(block.blockType)
    );
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

async function ensureParentDir(path: string): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`${message}\n`);
    process.exit(error instanceof CliExitError ? error.exitCode : 1);
  });
}
