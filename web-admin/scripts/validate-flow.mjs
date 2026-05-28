#!/usr/bin/env node
// web-admin/scripts/validate-flow.mjs
/**
 * CLI lint tool for the unified GraphDocument grammar
 * (spec: auraboot/docs/backlog/2026-05-23-unified-graph-grammar-spec.md).
 *
 * Two modes:
 *   1. validate-flow <file.json> [<file2.json> ...]
 *        Structural + semantic validation. Exit 0 on all-valid, 1 otherwise.
 *
 *   2. validate-flow --diff <automation.json> <bpmn.json>
 *        Audit the pair for the 4 known grammar divergences
 *        (D1 envelope / D2 data.type / D3 bare-string condition / D4 root meta).
 *        Exit 0 if both already conform to the unified grammar, 2 otherwise.
 *
 * Implementation: shells out to `tsx` so we can call the SDK TS sources
 * directly without a separate build. The actual work lives in
 *   web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const driver = resolve(here, 'validate-flow.driver.ts');

const result = spawnSync(
  process.execPath,
  [
    '--import',
    'tsx',
    driver,
    ...process.argv.slice(2),
  ],
  { stdio: 'inherit' },
);
process.exit(result.status ?? 1);
