/**
 * Phase 3 report-export render CLI entrypoint (Option A', slice 2c — see
 * DDR-2026-06-21-report-export-rendering-source-of-truth).
 *
 * The JVM↔Node subprocess boundary. Reads a render request as JSON on stdin:
 *   { "model": ReportPrintModel, "dataSets": PrintDataSets, "options"?: { format? } }
 * and writes the resulting PDF bytes to stdout (or to the path given by --out).
 * Logs go to stderr only, so stdout stays a clean binary stream.
 *
 * Logic lives in cli-core.ts (unit-tested); this file is just the process shell.
 */
import { writeFileSync } from 'node:fs';
import { renderRequestToPdf } from './cli-core';

async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks).toString('utf8');
}

async function main(): Promise<void> {
  const outFlagIndex = process.argv.indexOf('--out');
  const outPath = outFlagIndex >= 0 ? process.argv[outFlagIndex + 1] : undefined;

  const requestJson = await readStdin();
  const pdf = await renderRequestToPdf(requestJson);

  if (outPath) {
    writeFileSync(outPath, pdf);
    process.stderr.write(`report-export CLI: wrote ${pdf.length} bytes to ${outPath}\n`);
  } else {
    process.stdout.write(pdf);
  }
}

main().catch((err) => {
  process.stderr.write(
    `report-export CLI failed: ${err instanceof Error ? err.stack : String(err)}\n`,
  );
  process.exit(1);
});
