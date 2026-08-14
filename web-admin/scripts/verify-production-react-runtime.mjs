#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FORBIDDEN_FEDERATION_REACT =
  /__federation_shared_react(?:-dom)?(?:[-.]|$)/;

export function auditProductionReactRuntimeFiles(fileNames) {
  const forbidden = fileNames
    .map((fileName) => fileName.replaceAll('\\', '/'))
    .filter((fileName) =>
      FORBIDDEN_FEDERATION_REACT.test(path.basename(fileName)),
    );
  return {
    pass: forbidden.length === 0,
    forbidden,
  };
}

export function verifyProductionReactRuntime(assetDirectory) {
  if (!fs.existsSync(assetDirectory)) {
    throw new Error(
      `production client asset directory is missing: ${assetDirectory}`,
    );
  }
  const files = fs
    .readdirSync(assetDirectory, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith('.js'))
    .map((entry) => entry.name);
  const result = auditProductionReactRuntimeFiles(files);
  if (!result.pass) {
    throw new Error(
      `production client contains a second federation React runtime: ${result.forbidden.join(', ')}`,
    );
  }
  return result;
}

const isMain = process.argv[1]
  ? path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
  : false;

if (isMain) {
  const buildRoot =
    process.env.AURA_REACT_ROUTER_BUILD_DIR || path.resolve('build');
  const assetDirectory =
    process.argv[2] || path.join(buildRoot, 'client', 'assets');
  const result = verifyProductionReactRuntime(assetDirectory);
  console.log(
    JSON.stringify({
      status: 'PASS',
      check: 'single-production-react-runtime',
      assetDirectory: path.resolve(assetDirectory),
      forbiddenCount: result.forbidden.length,
    }),
  );
}
