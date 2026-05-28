// web-admin/scripts/validate-flow.driver.ts
//
// TS half of the validate-flow CLI. Invoked via `tsx` from validate-flow.mjs.
// Kept thin: parses argv, reads files, delegates to the SDK validators.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  validateGraphDocument,
  type GraphDocumentValidationError,
} from '../app/plugins/core-designer/components/flow-designer-sdk/validation/validateGraphDocument';
import {
  diffGraphDocuments,
  type GrammarDivergence,
} from '../app/plugins/core-designer/components/flow-designer-sdk/validation/diffGraphDocuments';

interface CliArgs {
  mode: 'validate' | 'diff' | 'help';
  files: string[];
}

function parseArgs(argv: string[]): CliArgs {
  if (argv.length === 0 || argv.includes('--help') || argv.includes('-h')) {
    return { mode: 'help', files: [] };
  }
  if (argv[0] === '--diff') {
    return { mode: 'diff', files: argv.slice(1) };
  }
  return { mode: 'validate', files: argv };
}

function printHelp(): void {
  console.log(
    [
      'validate-flow — lint GraphDocument JSON against the unified grammar',
      '',
      'Usage:',
      '  validate-flow <file.json> [<file2.json> ...]',
      '  validate-flow --diff <automation.json> <bpmn.json>',
      '',
      'Spec: auraboot/docs/backlog/2026-05-23-unified-graph-grammar-spec.md',
    ].join('\n'),
  );
}

function readJson(path: string): unknown {
  const abs = resolve(process.cwd(), path);
  const raw = readFileSync(abs, 'utf8');
  return JSON.parse(raw);
}

function formatError(e: GraphDocumentValidationError): string {
  const loc = e.path ? `  at ${e.path}` : e.nodeId
    ? `  at node=${e.nodeId}`
    : e.edgeId
      ? `  at edge=${e.edgeId}`
      : '';
  return `  [${e.code}] ${e.message}${loc ? `\n   ${loc}` : ''}`;
}

function formatDivergence(d: GrammarDivergence): string {
  const where = d.path ? `  at ${d.side}${d.path}` : `  (${d.side})`;
  const evid = d.evidence ? `\n     evidence: ${d.evidence}` : '';
  return `  [${d.code}] ${d.message}\n   ${where}${evid}`;
}

function runValidate(files: string[]): number {
  if (files.length === 0) {
    console.error('error: at least one JSON file required');
    return 1;
  }
  let bad = 0;
  for (const file of files) {
    let doc: unknown;
    try {
      doc = readJson(file);
    } catch (err) {
      console.error(`${file}: failed to read/parse — ${(err as Error).message}`);
      bad += 1;
      continue;
    }
    const result = validateGraphDocument(doc);
    if (result.valid) {
      console.log(`${file}: OK (0 errors)`);
    } else {
      bad += 1;
      console.log(`${file}: FAIL (${result.errors.length} errors)`);
      for (const e of result.errors) console.log(formatError(e));
    }
  }
  return bad === 0 ? 0 : 1;
}

function runDiff(files: string[]): number {
  if (files.length !== 2) {
    console.error('error: --diff requires exactly 2 JSON files');
    return 1;
  }
  const [aPath, bPath] = files;
  let a: unknown;
  let b: unknown;
  try {
    a = readJson(aPath);
    b = readJson(bPath);
  } catch (err) {
    console.error(`failed to read inputs — ${(err as Error).message}`);
    return 1;
  }
  const report = diffGraphDocuments(a, b);
  console.log(`a (${aPath}): detected kind=${report.aKind}`);
  console.log(`b (${bPath}): detected kind=${report.bKind}`);
  if (report.divergences.length === 0) {
    console.log('no grammar divergences — both inputs conform to GraphDocument 1.0');
    return 0;
  }
  // Bucket by code for a tidy summary, then print details.
  const bucket = new Map<string, GrammarDivergence[]>();
  for (const d of report.divergences) {
    const arr = bucket.get(d.code) ?? [];
    arr.push(d);
    bucket.set(d.code, arr);
  }
  console.log(`\n${report.divergences.length} divergences found:`);
  for (const [code, items] of bucket) {
    console.log(`  ${code}: ${items.length}`);
  }
  console.log('\nDetails:');
  for (const d of report.divergences) console.log(formatDivergence(d));
  return 2;
}

function main(): void {
  const args = parseArgs(process.argv.slice(2));
  if (args.mode === 'help') {
    printHelp();
    process.exit(0);
  }
  const code = args.mode === 'diff' ? runDiff(args.files) : runValidate(args.files);
  process.exit(code);
}

main();
