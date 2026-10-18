import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { assertSameContract, canonicalJson, normalizeDocument, operations, requireCondition, sha256 } from './contract.mjs';

/** Compare a captured release-container document. Caller binds container/source identity. */
export function compareCaptured({ expected, observed, receipt }) {
  requireCondition(receipt && !fs.existsSync(receipt), 'Use a fresh snapshot-drift receipt');
  const snapshot = JSON.parse(fs.readFileSync(expected, 'utf8'));
  const actual = normalizeDocument(JSON.parse(fs.readFileSync(observed, 'utf8')));
  const hash = assertSameContract(snapshot, actual);
  const result = { schemaVersion:1, claim:'captured-snapshot-drift-only', verdict:'pass', contractSha256:hash,
    observedSha256:sha256(fs.readFileSync(observed)), operations:operations(actual).length };
  fs.writeFileSync(receipt, canonicalJson(result), {flag:'wx'});
  return result;
}

export function main(args) {
  requireCondition(args.length === 2, 'Usage: node drift.mjs <observed-openapi.json> <fresh-receipt.json>');
  const expected = fileURLToPath(new URL('../../contracts/internal-web-admin/openapi.json', import.meta.url));
  console.log(canonicalJson(compareCaptured({expected, observed:args[0], receipt:args[1]})).trim());
}
if (process.argv[1] && import.meta.url === pathToFileURL(fs.realpathSync(process.argv[1])).href) {
  try { main(process.argv.slice(2)); } catch(error) { console.error(error.message); process.exitCode=1; }
}
