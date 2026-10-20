#!/usr/bin/env node
/**
 * Validate renderType against actual TSX registrations and table-column branches.
 * Source is parsed, never executed; capabilities apply only in their renderer context.
 * Install the pinned parser first: npm ci --prefix scripts/internal-api/toolchain --ignore-scripts.
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { run } from 'node:test';
import { auditTests } from './internal-api/foundation.mjs';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ts = createRequire(new URL('./internal-api/toolchain/package.json', import.meta.url))('typescript');
const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function parse(source, file) {
  const sf = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  if (sf.parseDiagnostics.length) throw new Error(`Cannot parse renderer source: ${file}`);
  return sf;
}

/** Read actual registration calls; comments and string lookalikes are not registrations. */
export function registeredRenderTypes(source) {
  const sf = parse(source, 'CellRendererRegistry.tsx'), found = new Set();
  function visit(node) {
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)
      && node.expression.expression.getText(sf) === 'cellRendererRegistry'
      && node.expression.name.text === 'register' && node.arguments[0]
      && ts.isStringLiteralLike(node.arguments[0])) found.add(node.arguments[0].text);
    ts.forEachChild(node, visit);
  }
  visit(sf);
  if (!found.size) throw new Error('Parsed 0 renderer registrations; source shape needs reconciliation');
  return found;
}

/** TableBlockRenderer also implements explicit column branches outside the generic registry. */
export function tableColumnRenderTypes(source) {
  const sf = parse(source, 'TableBlockRenderer.tsx'), found = new Set();
  function hasReturn(node) {
    if (ts.isFunctionLike(node)) return false;
    if (ts.isReturnStatement(node) && node.expression) return true;
    return ts.forEachChild(node, child => hasReturn(child) || undefined) === true;
  }
  function branch(node) {
    if (ts.isFunctionLike(node)) return;
    if (ts.isIfStatement(node) && ts.isBinaryExpression(node.expression)) {
      const { left, right, operatorToken } = node.expression;
      if (operatorToken.kind === ts.SyntaxKind.EqualsEqualsEqualsToken
        && ts.isPropertyAccessExpression(left) && left.expression.getText(sf) === 'column'
        && left.name.text === 'renderType' && ts.isStringLiteralLike(right)
        && hasReturn(node.thenStatement)) found.add(right.text);
    }
    ts.forEachChild(node, branch);
  }
  function visit(node) {
    if (ts.isVariableDeclaration(node) && node.name.getText(sf) === 'renderCellContent'
      && node.initializer && (ts.isArrowFunction(node.initializer) || ts.isFunctionExpression(node.initializer)))
      branch(node.initializer.body);
    ts.forEachChild(node, visit);
  }
  visit(sf);
  return found;
}

/** Only direct columns of a table block use that block's special renderer branches. */
export function collectRenderTypes(node, trail = '$', hits = [], context = 'registry') {
  if (Array.isArray(node)) {
    node.forEach((child, i) => collectRenderTypes(child, `${trail}[${i}]`, hits,
      context === 'table-columns' ? 'table-column' : 'registry'));
    return hits;
  }
  if (!node || typeof node !== 'object') return hits;
  for (const [key, value] of Object.entries(node)) {
    if (key === 'renderType' && typeof value === 'string') {
      hits.push({ value, at: `${trail}.renderType`, context });
    } else collectRenderTypes(value, `${trail}.${key}`, hits,
      node.blockType === 'table' && key === 'columns' ? 'table-columns' : 'registry');
  }
  return hits;
}

export function unsupportedRenderTypes(schema, registry, tableColumns) {
  return collectRenderTypes(schema).filter(hit => !registry.has(hit.value)
    && !(hit.context === 'table-column' && tableColumns.has(hit.value)));
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.isFile() && entry.name.endsWith('.json')) out.push(full);
  }
  return out;
}

export function main(root = REPO_ROOT) {
  const registry = registeredRenderTypes(fs.readFileSync(path.join(root,
    'web-admin/app/framework/meta/runtime/renderers/CellRendererRegistry.tsx'), 'utf8'));
  const tableColumns = tableColumnRenderTypes(fs.readFileSync(path.join(root,
    'web-admin/app/framework/meta/rendering/blocks/TableBlockRenderer.tsx'), 'utf8'));
  const failures = [];
  let checked = 0;
  for (const file of walk(path.join(root, 'plugins'))) {
    const split = file.includes(`${path.sep}config${path.sep}pages${path.sep}`);
    const single = file.endsWith(`${path.sep}config${path.sep}pages.json`);
    if (!split && !single) continue;
    // A malformed page cannot silently disappear from the gate denominator.
    const schema = JSON.parse(fs.readFileSync(file, 'utf8'));
    checked += collectRenderTypes(schema).length;
    for (const hit of unsupportedRenderTypes(schema, registry, tableColumns))
      failures.push({ file: path.relative(root, file), ...hit });
  }
  console.log('=== DSL renderType gate ===');
  console.log(`registered renderers: ${[...registry].sort().join(', ')}`);
  console.log(`table-column branches: ${[...tableColumns].sort().join(', ') || '(none)'}`);
  console.log(`renderType usages checked: ${checked}`);
  if (!checked) throw new Error('Checked 0 renderer usages; source denominator needs reconciliation');
  if (failures.length) throw new Error(failures.map(f => `${f.file} ${f.at} = "${f.value}" (${f.context}): no renderer in this context`).join('\n'));
  console.log('PASSED.');
}

export async function selfTest() {
  const profile = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts/internal-api/catalog.json'), 'utf8')).profiles.dslRendererGate;
  const actual = [];
  for await (const event of run({ files: [path.join(REPO_ROOT, profile.unitFile)], concurrency: 1 })) {
    if (!['test:pass', 'test:fail'].includes(event.type) || event.data.details?.type === 'suite') continue;
    actual.push({ name: event.data.name, status: event.type === 'test:pass' ? 'passed' : 'failed', skip: Boolean(event.data.skip), todo: Boolean(event.data.todo) });
    if (event.type === 'test:fail') console.error(event.data.details?.error);
  }
  const counts = auditTests(profile.unitTests, actual);
  console.log(`DSL renderer gate: ${counts.passed} registered fixture tests passed without skips`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(fs.realpathSync(process.argv[1])).href) {
  try {
    const args = process.argv.slice(2);
    if (args.length === 1 && args[0] === '--self-test') await selfTest();
    else if (args.length === 0) main();
    else throw new Error('Usage: node scripts/check-dsl-render-types.mjs [--self-test]');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
