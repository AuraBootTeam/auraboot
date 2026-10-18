import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { assertSameContract, canonicalJson, normalizeDocument, operations, requireCondition, resolvePointer, selectOperations, sha256 } from './contract.mjs';

/** Project the selected operations and their complete referenced component/security closure. */
export function projectCoreDocument(document, selectors) {
  const selected = selectOperations(document, selectors);
  const components = {}, retained = new Set();
  function retain(group, name) {
    const reference = `#/components/${group}/${name.replaceAll('~', '~0').replaceAll('/', '~1')}`;
    if (retained.has(reference)) return;
    retained.add(reference);
    const value = structuredClone(resolvePointer(document, reference));
    (components[group] ??= {})[name] = value;
    collect(value);
  }
  function collect(value) {
    if (!value || typeof value !== 'object') return;
    if (value.$ref) {
      requireCondition(value.$ref.startsWith('#/components/'), `Core projection requires bundled component references: ${value.$ref}`);
      const [, , group, encodedName] = value.$ref.split('/');
      retain(group, encodedName.replaceAll('~1', '/').replaceAll('~0', '~'));
    }
    for (const child of Object.values(value)) collect(child);
  }
  const security = requirements => {
    for (const requirement of requirements ?? []) for (const scheme of Object.keys(requirement)) retain('securitySchemes', scheme);
  };
  collect(selected.paths);
  security(selected.security);
  const tags = new Set();
  for (const row of operations(selected)) {
    security(row.operation.security);
    for (const tag of row.operation.tags ?? []) tags.add(tag);
  }
  if (selected.tags) selected.tags = selected.tags.filter(tag => tags.has(tag.name));
  if (Object.keys(components).length) selected.components = components;
  else delete selected.components;
  return normalizeDocument(selected);
}

/** Compare a captured release-container document. Caller binds container/source identity. */
export function compareCaptured({ expected, observed, receipt, mode = 'exact' }) {
  requireCondition(receipt && !fs.existsSync(receipt), 'Use a fresh snapshot-drift receipt');
  requireCondition(['exact', 'core-operations'].includes(mode), 'Unknown captured contract comparison mode');
  const snapshot = JSON.parse(fs.readFileSync(expected, 'utf8'));
  const actual = normalizeDocument(JSON.parse(fs.readFileSync(observed, 'utf8')));
  const selectors = operations(snapshot).map(({ method, path }) => ({ method, path }));
  const hash = mode === 'exact' ? assertSameContract(snapshot, actual)
    : assertSameContract(projectCoreDocument(snapshot, selectors), projectCoreDocument(actual, selectors));
  const result = { schemaVersion:1, claim: mode === 'exact' ? 'captured-snapshot-drift-only' : 'captured-core-operation-closure-drift-only', verdict:'pass', contractSha256:hash,
    observedSha256:sha256(fs.readFileSync(observed)), operations:operations(actual).length };
  if (mode === 'core-operations') Object.assign(result, { coreOperations: selectors.length,
    productAdditionalOperations: operations(actual).length - selectors.length,
    expectedSnapshotSha256: sha256(fs.readFileSync(expected)), productContractAcceptance: 'not-assessed' });
  fs.writeFileSync(receipt, canonicalJson(result), {flag:'wx'});
  return result;
}

export function main(args) {
  requireCondition(args.length === 2 || args.length === 3 && args[2] === '--core-operations', 'Usage: node drift.mjs <observed-openapi.json> <fresh-receipt.json> [--core-operations]');
  const expected = fileURLToPath(new URL('../../contracts/internal-web-admin/openapi.json', import.meta.url));
  console.log(canonicalJson(compareCaptured({expected, observed:args[0], receipt:args[1], mode: args[2] ? 'core-operations' : 'exact'})).trim());
}
if (process.argv[1] && import.meta.url === pathToFileURL(fs.realpathSync(process.argv[1])).href) {
  try { main(process.argv.slice(2)); } catch(error) { console.error(error.message); process.exitCode=1; }
}
