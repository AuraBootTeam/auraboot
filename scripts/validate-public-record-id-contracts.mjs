#!/usr/bin/env node
/**
 * Public dynamic-record id contract inventory and regression gate.
 *
 * This checker is intentionally conservative. It does not claim every hit is a
 * product bug; it inventories public-boundary surfaces that still teach or leak
 * internal record ids so the platform can migrate to pid-only contracts without
 * blind spots. Baseline mode accepts known debt and fails only newly introduced
 * findings.
 *
 * Usage:
 *   node scripts/validate-public-record-id-contracts.mjs
 *   node scripts/validate-public-record-id-contracts.mjs --inventory
 *   node scripts/validate-public-record-id-contracts.mjs --baseline=scripts/public-record-id-baseline.json
 *   node scripts/validate-public-record-id-contracts.mjs --write-baseline=scripts/public-record-id-baseline.json
 *
 * Exit: 0 = no new findings, 1 = new findings (or any finding under --strict),
 *       2 = baseline/config IO failure.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const DEFAULT_BASELINE = 'scripts/public-record-id-baseline.json';
const BASELINE_VERSION = 1;
const PUBLIC_RESPONSE_FIXTURE_ROOT = 'docs/api-fixtures/public-record';

const SKIP_DIRS = new Set([
  '.git',
  '.worktrees',
  'build',
  'coverage',
  'dist',
  'node_modules',
  'target',
]);

const LEGACY_PLACEHOLDER_RE = /(\$\{recordId\}|\$record\.id|\{recordId\})/;
const SQL_SELECT_RE = /\bselect\b[\s\S]{0,1600}\bfrom\b/i;
const SQL_INTERNAL_FIELD_RE = /(^|[^\w])(?:id|tenant_id|created_by|updated_by)(?:[^\w]|$)/i;
const DYNAMIC_RECORD_MAP_RE =
  /PaginationResult<\s*Map<String,\s*Object>>|List<\s*Map<String,\s*Object>>|ResponseEntity<\s*Map<String,\s*Object>>|Map<String,\s*Object>\s+(record|row|data|result|response)\b|\bnextCursor\b/;
const RAW_DYNAMIC_CURSOR_RE =
  /WHERE id > cursor|ORDER BY id ASC|addCondition\(\s*["']id["']\s*,\s*["']GT["']\s*,\s*request\.getCursor\(\)\s*\)|last record's id|Long\s+nextCursor|nextCursor\s*=\s*\(\(Number\)/;
const PUBLIC_RESPONSE_FORBIDDEN_KEYS = new Map([
  ['id', 'id'],
  ['recordid', 'recordId'],
  ['recordids', 'recordIds'],
  ['targetrecordid', 'targetRecordId'],
  ['targetrecordids', 'targetRecordIds'],
  ['boundrecordid', 'boundRecordId'],
  ['triggerrecordid', 'triggerRecordId'],
  ['record_id', 'record_id'],
  ['target_record_id', 'target_record_id'],
  ['bound_record_id', 'bound_record_id'],
  ['trigger_record_id', 'trigger_record_id'],
  ['tenant_id', 'tenant_id'],
  ['created_by', 'created_by'],
  ['updated_by', 'updated_by'],
]);
const PUBLIC_CONFIG_FORBIDDEN_KEYS = new Map([
  ['recordId', 'recordId'],
  ['recordIds', 'recordIds'],
  ['targetRecordId', 'targetRecordId'],
  ['targetRecordIds', 'targetRecordIds'],
  ['boundRecordId', 'boundRecordId'],
  ['triggerRecordId', 'triggerRecordId'],
  ['recordIdVar', 'recordIdVar'],
  ['recordIdField', 'recordIdField'],
]);
const DYNAMIC_CONTROLLER_NON_RECORD_METHODS = new Set([
  'getFieldOptions',
  'getStats',
  'getMeta',
  'getFieldMeta',
  'getPageMetadata',
  'validate',
]);

function normalizeRel(file) {
  return file.split(path.sep).join('/');
}

function rel(root, file) {
  return normalizeRel(path.relative(root, file));
}

function safeRead(file) {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    return null;
  }
}

function safeReadJson(file) {
  const raw = safeRead(file);
  if (raw === null) return { ok: false, error: 'file not found' };
  try {
    return { ok: true, value: JSON.parse(raw) };
  } catch (error) {
    return { ok: false, error: error.message };
  }
}

function existsDir(dir) {
  try {
    return fs.statSync(dir).isDirectory();
  } catch {
    return false;
  }
}

function walk(dir, predicate, out = []) {
  if (!existsDir(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, predicate, out);
      continue;
    }
    if (entry.isFile() && predicate(full)) out.push(full);
  }
  return out;
}

function findEnterprise(ossRoot) {
  let cur = path.dirname(ossRoot);
  for (let i = 0; i < 8; i += 1) {
    const candidate = path.join(cur, 'auraboot-enterprise');
    if (existsDir(path.join(candidate, 'plugins'))) return candidate;
    const parent = path.dirname(cur);
    if (parent === cur) break;
    cur = parent;
  }
  return null;
}

function snippet(text, max = 180) {
  return text.replace(/\s+/g, ' ').trim().slice(0, max);
}

function extractSelectProjection(sql) {
  const selectMatch = /\bselect\b/i.exec(sql);
  if (!selectMatch) return null;

  let depth = 0;
  let quote = null;
  for (let i = selectMatch.index + selectMatch[0].length; i < sql.length; i += 1) {
    const char = sql[i];
    if (quote) {
      if (char === quote) quote = null;
      continue;
    }
    if (char === '\'' || char === '"' || char === '`') {
      quote = char;
      continue;
    }
    if (char === '(') {
      depth += 1;
      continue;
    }
    if (char === ')') {
      depth = Math.max(0, depth - 1);
      continue;
    }
    if (
      depth === 0 &&
      /\s/.test(char) &&
      /^from\b/i.test(sql.slice(i + 1))
    ) {
      return sql.slice(selectMatch.index + selectMatch[0].length, i);
    }
  }
  return null;
}

function selectsInternalField(sql) {
  const projection = extractSelectProjection(sql);
  return projection !== null && SQL_INTERNAL_FIELD_RE.test(projection);
}

function makeKey(finding) {
  return [
    finding.code,
    finding.file,
    finding.jsonPath ?? '',
    finding.field ?? '',
    finding.evidence ?? '',
  ].join('|');
}

function pushFinding(findings, finding) {
  const withKey = {
    severity: 'error',
    ...finding,
  };
  withKey.key = makeKey(withKey);
  findings.push(withKey);
}

function countChar(text, char) {
  return (text.match(new RegExp(`\\${char}`, 'g')) ?? []).length;
}

function stripQuotedStrings(text) {
  return text
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    .replace(/'(?:\\.|[^'\\])*'/g, "''");
}

function javaMethodBlock(lines, index) {
  let start = index;
  while (start >= 0 && !/\b(public|private|protected)\b[\s\S]*\(/.test(lines[start])) {
    start -= 1;
  }
  if (start < 0) {
    return lines.slice(Math.max(0, index - 5), Math.min(lines.length, index + 10)).join('\n');
  }

  let depth = 0;
  let seenBody = false;
  for (let end = start; end < lines.length; end += 1) {
    const line = stripQuotedStrings(lines[end]);
    const opens = countChar(line, '{');
    const closes = countChar(line, '}');
    if (opens > 0) seenBody = true;
    depth += opens - closes;
    if (seenBody && depth <= 0) {
      return lines.slice(start, end + 1).join('\n');
    }
  }

  return lines.slice(start, Math.min(lines.length, index + 30)).join('\n');
}

function javaMethodName(block) {
  const match = /\b(?:public|private|protected)\s+[\s\S]*?\s+([A-Za-z_$][\w$]*)\s*\(/.exec(block);
  return match?.[1] ?? null;
}

function dynamicControllerMapRiskIsCovered(lines, index) {
  const block = javaMethodBlock(lines, index);
  if (/\bPublicRecordSanitizer\.sanitize(?:Record|Records|Page|Batch)\s*\(/.test(block)) {
    return true;
  }
  if (/\breturn\s+create\s*\(/.test(block)) {
    return true;
  }
  const methodName = javaMethodName(block);
  return methodName !== null && DYNAMIC_CONTROLLER_NON_RECORD_METHODS.has(methodName);
}

function recordJsonStringFindings(findings, repoRoot, file, jsonPath, value) {
  if (LEGACY_PLACEHOLDER_RE.test(value)) {
    pushFinding(findings, {
      code: 'S-PUBLIC-RECORD-PLACEHOLDER-LEGACY',
      category: 'dsl-config',
      file: rel(repoRoot, file),
      jsonPath,
      field: 'recordId-placeholder',
      evidence: snippet(value),
      message: 'Public DSL/config string still uses recordId/$record.id instead of recordPid/$record.pid.',
    });
  }
  if (SQL_SELECT_RE.test(value) && selectsInternalField(value)) {
    pushFinding(findings, {
      code: 'S-PUBLIC-RECORD-SQL-INTERNAL-FIELD-RISK',
      category: 'named-query-export',
      file: rel(repoRoot, file),
      jsonPath,
      field: 'sql',
      evidence: snippet(value),
      message: 'Public query/export SQL selects internal id fields and needs a sanitizer or explicit public allowlist.',
    });
  }
}

function visitJson(value, findings, repoRoot, file, jsonPath = '$') {
  if (Array.isArray(value)) {
    value.forEach((item, index) => visitJson(item, findings, repoRoot, file, `${jsonPath}[${index}]`));
    return;
  }
  if (typeof value === 'string') {
    recordJsonStringFindings(findings, repoRoot, file, jsonPath, value);
    return;
  }
  if (!value || typeof value !== 'object') return;

  for (const key of Object.keys(value)) {
    const canonical = PUBLIC_CONFIG_FORBIDDEN_KEYS.get(key);
    if (!canonical) continue;
    pushFinding(findings, {
      code: canonical === 'targetRecordId'
        ? 'S-PUBLIC-RECORD-TARGET-ID-LEGACY'
        : 'S-PUBLIC-RECORD-CONFIG-LEGACY-KEY',
      category: 'dsl-config',
      file: rel(repoRoot, file),
      jsonPath: `${jsonPath}.${key}`,
      field: canonical,
      evidence: canonical,
      message: 'Public DSL/config still exposes a legacy record identity key; use recordPid/targetRecordPid/recordPidVar/recordPidField.',
    });
  }

  for (const [key, child] of Object.entries(value)) {
    visitJson(child, findings, repoRoot, file, `${jsonPath}.${key}`);
  }
}

function collectConfigJsonFiles(root) {
  return walk(path.join(root, 'plugins'), (file) =>
    file.endsWith('.json') && normalizeRel(file).includes('/config/'));
}

function scanJsonConfigs(findings, root) {
  for (const file of collectConfigJsonFiles(root)) {
    const parsed = safeReadJson(file);
    if (!parsed.ok) {
      pushFinding(findings, {
        code: 'S-PUBLIC-RECORD-CONFIG-INVALID-JSON',
        category: 'config',
        file: rel(root, file),
        jsonPath: '$',
        field: 'json',
        evidence: parsed.error,
        message: `Invalid JSON: ${parsed.error}`,
      });
      continue;
    }
    visitJson(parsed.value, findings, root, file);
  }
}

function collectPublicResponseFixtureFiles(root) {
  return walk(path.join(root, PUBLIC_RESPONSE_FIXTURE_ROOT), (file) => file.endsWith('.json'));
}

function isInternalResponseFixtureObject(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const audience = value.contractAudience ?? value.__contractAudience ?? value.audience;
  return typeof audience === 'string' && /^(internal|admin)$/i.test(audience.trim());
}

function visitPublicResponseFixture(value, findings, repoRoot, file, jsonPath = '$') {
  if (Array.isArray(value)) {
    value.forEach((item, index) =>
      visitPublicResponseFixture(item, findings, repoRoot, file, `${jsonPath}[${index}]`));
    return;
  }
  if (!value || typeof value !== 'object') return;
  if (isInternalResponseFixtureObject(value)) return;

  for (const [key, child] of Object.entries(value)) {
    const forbiddenKey = PUBLIC_RESPONSE_FORBIDDEN_KEYS.get(key.toLowerCase());
    if (forbiddenKey) {
      pushFinding(findings, {
        code: 'S-PUBLIC-RECORD-RESPONSE-LEGACY-KEY',
        category: 'public-response-fixture',
        file: rel(repoRoot, file),
        jsonPath: `${jsonPath}.${key}`,
        field: key,
        evidence: forbiddenKey,
        message: 'Public response fixture exposes an internal/legacy record identity key.',
      });
    }
    visitPublicResponseFixture(child, findings, repoRoot, file, `${jsonPath}.${key}`);
  }
}

function scanPublicResponseFixtures(findings, root) {
  for (const file of collectPublicResponseFixtureFiles(root)) {
    const parsed = safeReadJson(file);
    if (!parsed.ok) {
      pushFinding(findings, {
        code: 'S-PUBLIC-RECORD-RESPONSE-FIXTURE-INVALID-JSON',
        category: 'public-response-fixture',
        file: rel(root, file),
        jsonPath: '$',
        field: 'json',
        evidence: parsed.error,
        message: `Invalid public response fixture JSON: ${parsed.error}`,
      });
      continue;
    }
    visitPublicResponseFixture(parsed.value, findings, root, file);
  }
}

function isTestFile(file) {
  const norm = normalizeRel(file);
  return (
    norm.includes('/__tests__/') ||
    norm.includes('/tests/') ||
    /\.(test|spec)\.(ts|tsx|js|jsx|mjs)$/.test(norm)
  );
}

function scanJava(findings, root) {
  const files = walk(path.join(root, 'platform/src/main/java'), (file) => file.endsWith('.java'));
  for (const file of files) {
    const text = safeRead(file);
    if (text === null) continue;
    const norm = normalizeRel(file);
    if (norm.includes('/framework/test/')) continue;
    const publicBoundary = norm.includes('/controller/') || text.includes('@RestController');
    const dynamicController = /DynamicController\.java$/.test(norm);
    const dynamicDataServiceImpl = /DynamicDataServiceImpl\.java$/.test(norm);
    const lines = text.split('\n');
    lines.forEach((line, index) => {
      const lineNo = index + 1;
      if (
        publicBoundary &&
        /\b(recordId|recordIds|targetRecordId|commentId)\b/.test(line) &&
        /(@(?:PathVariable|RequestParam|RequestBody)|recordId|targetRecordId|commentId)/.test(line)
      ) {
        pushFinding(findings, {
          code: 'S-PUBLIC-RECORD-JAVA-CONTROLLER-LEGACY',
          category: 'backend-public-api',
          file: rel(root, file),
          line: lineNo,
          field: 'recordId',
          evidence: snippet(line),
          message: 'Public Java API boundary still exposes recordId/targetRecordId/commentId naming.',
        });
      }
      if (
        dynamicController &&
        DYNAMIC_RECORD_MAP_RE.test(line) &&
        !dynamicControllerMapRiskIsCovered(lines, index)
      ) {
        pushFinding(findings, {
          code: 'S-PUBLIC-RECORD-DYNAMIC-MAP-RISK',
          category: 'dynamic-read-boundary',
          file: rel(root, file),
          line: lineNo,
          field: 'dynamic-record-map',
          evidence: snippet(line),
          message: 'Dynamic record boundary uses generic maps/cursors and must be covered by public-record sanitization.',
        });
      }
      if (dynamicDataServiceImpl && RAW_DYNAMIC_CURSOR_RE.test(line)) {
        pushFinding(findings, {
          code: 'S-PUBLIC-RECORD-DYNAMIC-MAP-RISK',
          category: 'dynamic-read-boundary',
          file: rel(root, file),
          line: lineNo,
          field: 'dynamic-record-map',
          evidence: snippet(line),
          message: 'Dynamic record boundary uses generic maps/cursors and must be covered by public-record sanitization.',
        });
      }
    });
  }
}

function scanFrontend(findings, root) {
  const files = walk(path.join(root, 'web-admin/app'), (file) => {
    if (!/\.(ts|tsx|js|jsx)$/.test(file)) return false;
    return !isTestFile(file);
  });
  const legacyRe = /\brecordIdVar\b|\btargetRecordId\b|\brecordId\b|\b(row|record|recordData)\.id\b|\$record\.id/;
  for (const file of files) {
    const text = safeRead(file);
    if (text === null) continue;
    if (normalizeRel(file).endsWith('/framework/meta/utils/publicRecordId.ts')) continue;
    text.split('\n').forEach((line, index) => {
      if (/\b(getPublicRecordPid|getLegacyCompatibleRecordPid|getPublicRecordKey|buildCommandTargetParams)\b/.test(line)) return;
      if (!legacyRe.test(line)) return;
      pushFinding(findings, {
        code: 'S-PUBLIC-RECORD-FRONTEND-LEGACY',
        category: 'frontend-runtime',
        file: rel(root, file),
        line: index + 1,
        field: /\brecordIdVar\b/.test(line) ? 'recordIdVar' : 'recordId',
        evidence: snippet(line),
        message: 'Frontend runtime/config code still references recordIdVar/recordId/targetRecordId/record.id compatibility identity.',
      });
    });
  }
}

function collectFindings(repoRoot, options = {}) {
  const findings = [];
  scanJsonConfigs(findings, repoRoot);
  scanPublicResponseFixtures(findings, repoRoot);
  scanJava(findings, repoRoot);
  scanFrontend(findings, repoRoot);

  if (options.includeEnterprise) {
    const enterpriseRoot = options.enterpriseRoot ? path.resolve(options.enterpriseRoot) : findEnterprise(repoRoot);
    if (enterpriseRoot) {
      scanJsonConfigs(findings, enterpriseRoot);
    }
  }

  const seen = new Set();
  return findings.filter((finding) => {
    if (seen.has(finding.key)) return false;
    seen.add(finding.key);
    return true;
  }).sort((a, b) => a.key.localeCompare(b.key));
}

function readBaseline(baselinePath) {
  if (!baselinePath) return { ok: true, keys: new Set(), entries: [] };
  const parsed = safeReadJson(baselinePath);
  if (!parsed.ok) {
    return { ok: false, error: `Cannot read baseline ${baselinePath}: ${parsed.error}` };
  }
  const entries = Array.isArray(parsed.value?.accepted)
    ? parsed.value.accepted
    : Array.isArray(parsed.value?.findings)
      ? parsed.value.findings
      : Array.isArray(parsed.value)
        ? parsed.value
        : [];
  const keys = new Set(entries.map((entry) => typeof entry === 'string' ? entry : entry.key).filter(Boolean));
  return { ok: true, keys, entries };
}

export function createBaseline(findings, now = new Date()) {
  return {
    version: BASELINE_VERSION,
    generatedAt: now.toISOString(),
    accepted: findings.map((finding) => ({
      key: finding.key,
      code: finding.code,
      category: finding.category,
      file: finding.file,
      line: finding.line,
      jsonPath: finding.jsonPath,
      field: finding.field,
      evidence: finding.evidence,
    })),
  };
}

function defaultBaselinePath(repoRoot) {
  const candidate = path.join(repoRoot, DEFAULT_BASELINE);
  return fs.existsSync(candidate) ? candidate : null;
}

export function auditRepo(repoRoot, options = {}) {
  const root = path.resolve(repoRoot);
  const requestedBaseline =
    Object.prototype.hasOwnProperty.call(options, 'baselinePath')
      ? options.baselinePath
      : defaultBaselinePath(root);
  const baselinePath = requestedBaseline ? path.resolve(root, requestedBaseline) : null;
  const findings = collectFindings(root, options);
  const baseline = readBaseline(baselinePath);
  if (!baseline.ok) {
    return {
      findings,
      newFindings: findings,
      acceptedFindings: [],
      baselinePath,
      configError: true,
      error: baseline.error,
      summary: summarize(findings, findings, []),
      exitCode: 2,
    };
  }

  const acceptedFindings = [];
  const newFindings = [];
  for (const finding of findings) {
    if (!options.strict && baseline.keys.has(finding.key)) {
      acceptedFindings.push({ ...finding, severity: 'warning', acceptedByBaseline: true });
    } else {
      newFindings.push(finding);
    }
  }

  return {
    findings,
    newFindings,
    acceptedFindings,
    baselinePath,
    baselineEntries: baseline.entries.length,
    configError: false,
    summary: summarize(findings, newFindings, acceptedFindings),
    exitCode: newFindings.length > 0 ? 1 : 0,
  };
}

function summarize(findings, newFindings, acceptedFindings) {
  const byCategory = {};
  for (const finding of findings) {
    byCategory[finding.category] = (byCategory[finding.category] ?? 0) + 1;
  }
  return {
    total: findings.length,
    new: newFindings.length,
    accepted: acceptedFindings.length,
    byCategory,
  };
}

function parseArgs(argv) {
  const options = {
    inventory: false,
    json: false,
    quiet: false,
    strict: false,
    help: false,
    includeEnterprise: false,
    baselinePath: undefined,
    writeBaselinePath: null,
  };
  for (const arg of argv) {
    if (arg === '--inventory') options.inventory = true;
    else if (arg === '--json') options.json = true;
    else if (arg === '--quiet') options.quiet = true;
    else if (arg === '--strict') options.strict = true;
    else if (arg === '--oss-only') options.includeEnterprise = false;
    else if (arg === '--include-enterprise') options.includeEnterprise = true;
    else if (arg === '--no-baseline') options.baselinePath = null;
    else if (arg === '--help' || arg === '-h') options.help = true;
    else if (arg.startsWith('--baseline=')) options.baselinePath = arg.slice('--baseline='.length);
    else if (arg.startsWith('--write-baseline=')) options.writeBaselinePath = arg.slice('--write-baseline='.length);
    else if (arg.startsWith('--enterprise=')) options.enterpriseRoot = arg.slice('--enterprise='.length);
  }
  return options;
}

function printUsage() {
  console.log(`Usage: node scripts/validate-public-record-id-contracts.mjs [options]

Options:
  --baseline=<path>         Accept findings listed in a baseline file.
  --write-baseline=<path>   Write the current inventory as the baseline and exit 0.
  --inventory               Print categorized inventory; does not change exit semantics.
  --strict                  Ignore baseline and fail on any finding.
  --json                    Print machine-readable result.
  --quiet                   Suppress detailed human findings.
  --oss-only                Scan only the OSS checkout (default).
  --include-enterprise      Also scan enterprise plugin configs when a sibling checkout is available.
  --no-baseline             Treat all findings as new.

Default baseline: ${DEFAULT_BASELINE}, when present.`);
}

function printHuman(result, options) {
  if (result.configError) {
    console.error(result.error);
  }

  if (!options.quiet) {
    console.log('Public record id contract inventory:');
    for (const [category, count] of Object.entries(result.summary.byCategory).sort()) {
      console.log(`  - ${category}: ${count}`);
    }

    if (result.newFindings.length) {
      console.log('\nNew findings:');
      for (const finding of result.newFindings) {
        const loc = finding.line ? `:${finding.line}` : finding.jsonPath ? ` ${finding.jsonPath}` : '';
        console.log(`  ERROR ${finding.code} ${finding.file}${loc} -- ${finding.message}`);
      }
    }

    if (options.inventory && result.acceptedFindings.length) {
      console.log('\nAccepted by baseline:');
      for (const finding of result.acceptedFindings) {
        const loc = finding.line ? `:${finding.line}` : finding.jsonPath ? ` ${finding.jsonPath}` : '';
        console.log(`  BASELINE ${finding.code} ${finding.file}${loc}`);
      }
    }
  }

  const baseline = result.baselinePath ? path.relative(process.cwd(), result.baselinePath) : 'none';
  console.log(`\nSummary: ${result.summary.total} finding(s), ${result.summary.accepted} accepted, ${result.summary.new} new. Baseline: ${baseline}`);
  console.log(result.exitCode === 0 ? 'PASSED.' : 'FAILED.');
}

function writeBaselineFile(repoRoot, targetPath, options) {
  const result = auditRepo(repoRoot, { ...options, baselinePath: null, strict: false });
  const baseline = createBaseline(result.findings);
  const abs = path.resolve(repoRoot, targetPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, `${JSON.stringify(baseline, null, 2)}\n`);
  return { result, abs };
}

if (process.argv[1] && path.resolve(process.argv[1]) === SCRIPT_PATH) {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printUsage();
    process.exit(0);
  }

  const repoRoot = path.resolve(path.dirname(SCRIPT_PATH), '..');
  if (options.writeBaselinePath) {
    const { result, abs } = writeBaselineFile(repoRoot, options.writeBaselinePath, options);
    if (options.json) {
      console.log(JSON.stringify({ ...result, wroteBaseline: abs }, null, 2));
    } else {
      console.log(`Wrote public record id baseline: ${path.relative(repoRoot, abs)} (${result.findings.length} finding(s))`);
    }
    process.exit(0);
  }

  const result = auditRepo(repoRoot, options);
  if (options.json) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    printHuman(result, options);
  }
  process.exit(result.exitCode);
}
