import path from 'node:path';
import { createRequire } from 'node:module';
import { requireCondition, sha256 } from './contract.mjs';

const ts = createRequire(new URL('./toolchain/package.json', import.meta.url))('typescript');
const normalize = file => path.posix.normalize(file.replaceAll('\\', '/'));
const asset = /\.(?:css|scss|svg|png|jpe?g|gif|webp|woff2?|html)$/;

function exportTarget(value) {
  if (typeof value === 'string') return value;
  if (!value || Array.isArray(value) || typeof value !== 'object') return undefined;
  // Environment-specific branches need an admitted target profile, not an arbitrary fallback.
  if (Object.keys(value).some(key => !['import', 'default', 'module', 'types'].includes(key))) return undefined;
  for (const key of ['import', 'default', 'module']) {
    const selected = exportTarget(value[key]);
    if (selected) return selected;
  }
}

/** A source-only graph; dynamic boundaries remain visible and never prove exclusion. */
export function traceSourceGraph({ sources, entries, packages = [], aliases = { '~/*': ['web-admin/app/*'] } }) {
  const files = new Map(sources.map(source => [normalize(source.file), source]));
  requireCondition(files.size === sources.length, 'Source graph contains duplicate files');
  requireCondition(entries.length > 0 && new Set(entries).size === entries.length, 'Source graph entries must be nonempty and unique');
  const workspace = new Map();
  for (const item of packages) {
    const name = item.manifest.name;
    requireCondition(name && !workspace.has(name), `Missing or duplicate workspace package: ${name}`);
    workspace.set(name, item);
  }
  const edges = [], reached = new Map(), queue = [];
  function sourceFile(target) {
    const normalized = normalize(target);
    const stem = normalized.replace(/\.[cm]?jsx?$/, '');
    return [normalized, ...['.ts', '.tsx', '.mts', '.cts', '/index.ts', '/index.tsx'].map(suffix => stem + suffix)]
      .find(file => files.has(file));
  }
  function resolve(from, specifier) {
    const resource = specifier.split(/[?#]/)[0];
    if (asset.test(resource)) return { boundary: 'asset' };
    if (/\.json$/.test(resource)) return { boundary: 'data-resource' };
    for (const [pattern, targets] of Object.entries(aliases)) {
      const [prefix, suffix = ''] = pattern.split('*');
      const wildcard = pattern.includes('*');
      if (wildcard ? !specifier.startsWith(prefix) || !specifier.endsWith(suffix) : specifier !== pattern) continue;
      const captured = wildcard ? specifier.slice(prefix.length, suffix.length ? -suffix.length : undefined) : '';
      const target = targets.map(candidate => sourceFile(candidate.replaceAll('*', captured))).find(Boolean);
      return { target, boundary: 'missing-app-alias' };
    }
    if (specifier.startsWith('.')) return { target: sourceFile(path.posix.join(path.posix.dirname(from), specifier)), boundary: asset.test(specifier) ? 'asset' : 'missing-relative-module' };
    const name = specifier.startsWith('@') ? specifier.split('/').slice(0, 2).join('/') : specifier.split('/')[0];
    const item = workspace.get(name);
    if (!item) return { boundary: specifier.startsWith('virtual:') ? 'virtual-module' : 'external-module' };
    const subpath = specifier.slice(name.length);
    const key = subpath ? `.${subpath}` : '.';
    const exports = item.manifest.exports;
    let target;
    if (exports !== undefined) {
      const exportsMap = exports && typeof exports === 'object' && !Array.isArray(exports) ? exports : {};
      target = exportTarget(key === '.' && (typeof exports === 'string' || !Object.keys(exportsMap).some(k => k.startsWith('.'))) ? exports : exportsMap[key]);
      if (!target && exports && typeof exports === 'object') {
        const patterns = Object.entries(exports).filter(([pattern]) => pattern.includes('*'))
          .sort(([left], [right]) => right.indexOf('*') - left.indexOf('*') || right.length - left.length);
        for (const [pattern, value] of patterns) {
          const [prefix, suffix] = pattern.split('*');
          if (!pattern.includes('*') || !key.startsWith(prefix) || !key.endsWith(suffix)) continue;
          const selected = exportTarget(value);
          if (selected) target = selected.replaceAll('*', key.slice(prefix.length, suffix.length ? -suffix.length : undefined));
          break;
        }
      }
    } else target = subpath ? `.${subpath}` : item.manifest.module ?? item.manifest.main ?? item.manifest.types;
    return { target: target && sourceFile(path.posix.join(item.root, target)), boundary: 'unresolved-workspace-export', package: name };
  }
  function link(from, specifier, kind, line, targetOverride) {
    const resolved = targetOverride ? { target: sourceFile(targetOverride), boundary: 'missing-route-file' } : resolve(from, specifier);
    const edge = { from, line, kind, specifier, ...resolved };
    edges.push(edge);
    if (resolved.target && !reached.has(resolved.target)) {
      reached.set(resolved.target, { from, line, kind, specifier });
      queue.push(resolved.target);
    }
  }
  for (const file of entries) {
    requireCondition(files.has(file), `Source graph entry is missing: ${file}`);
    reached.set(file, { kind: 'entry' }); queue.push(file);
  }
  for (let cursor = 0; cursor < queue.length; cursor++) {
    const file = queue[cursor], source = files.get(file);
    const sf = ts.createSourceFile(file, source.text, ts.ScriptTarget.ES2022, true);
    const routeFunctions = new Map();
    for (const statement of sf.statements) {
      if (ts.isImportDeclaration(statement) && statement.moduleSpecifier.text === '@react-router/dev/routes') {
        const binding = statement.importClause?.namedBindings;
        if (binding && ts.isNamedImports(binding)) for (const item of binding.elements)
          routeFunctions.set(item.name.text, (item.propertyName ?? item.name).text);
      }
    }
    function visit(node) {
      const line = sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
      if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) {
        const specifier = node.moduleSpecifier;
        const named = ts.isImportDeclaration(node) ? node.importClause?.namedBindings : node.exportClause;
        const typeOnly = node.isTypeOnly || node.importClause?.isTypeOnly
          || (named?.elements?.length && !node.importClause?.name && named.elements.every(item => item.isTypeOnly));
        if (specifier && ts.isStringLiteralLike(specifier) && !typeOnly) link(file, specifier.text, 'import', line);
      }
      if (ts.isCallExpression(node)) {
        if (node.expression.kind === ts.SyntaxKind.ImportKeyword || (ts.isIdentifier(node.expression) && node.expression.text === 'require')) {
          const target = node.arguments[0];
          if (target && ts.isStringLiteralLike(target)) link(file, target.text, 'dynamic-import', line);
          else edges.push({ from: file, line, kind: 'dynamic-import', specifier: target?.getText(sf) ?? '', boundary: 'dynamic-module-expression' });
        }
        const route = ts.isIdentifier(node.expression) && routeFunctions.get(node.expression.text);
        if (['route', 'index', 'layout'].includes(route)) {
          const target = node.arguments[route === 'route' ? 1 : 0];
          if (target && ts.isStringLiteralLike(target)) link(file, target.text, 'route-file', line, `web-admin/app/${target.text}`);
          else edges.push({ from: file, line, kind: 'route-file', specifier: target?.getText(sf) ?? '', boundary: 'indirect-route-file' });
        }
      }
      // Canonical PluginResource.file values are app-relative router modules, not imports.
      // Conservatively retaining every declared resource avoids executing route factories.
      if (/^web-admin\/app\/plugins\/core-[^/]+\/resources\.ts$/.test(file)
        && ts.isPropertyAssignment(node) && node.name.getText(sf).replace(/['"]/g, '') === 'file') {
        const value = node.initializer;
        if (ts.isStringLiteralLike(value)) link(file, value.text, 'plugin-resource-file', line, `web-admin/app/${value.text}`);
        else edges.push({ from: file, line, kind: 'plugin-resource-file', specifier: value.getText(sf), boundary: 'dynamic-resource-file' });
      }
      ts.forEachChild(node, visit);
    }
    visit(sf);
  }
  return {
    claim: 'static-source-reachability-only; dynamic-boundaries-do-not-prove-exclusion',
    entries,
    aliases,
    files: [...files].map(([file, source]) => ({ file, sha256: sha256(source.text), reachable: reached.has(file), reachedBy: reached.get(file) ?? null })),
    edges,
    unresolved: edges.filter(edge => !edge.target && !['external-module', 'asset'].includes(edge.boundary)),
    summary: { candidates: files.size, reachable: reached.size, edges: edges.length,
      unresolved: edges.filter(edge => !edge.target && !['external-module', 'asset'].includes(edge.boundary)).length },
  };
}
