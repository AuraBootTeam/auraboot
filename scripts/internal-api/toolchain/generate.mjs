import { realpathSync } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';
import { canonicalJson, normalizeDocument, operations, requireCondition, resolvePointer, sha256 } from '../contract.mjs';

export async function generateTypes(document) {
  const normalized = normalizeDocument(document);
  for (const row of operations(normalized)) {
    for (const raw of [...(row.pathItem.parameters ?? []), ...(row.operation.parameters ?? [])]) {
      const parameter = raw.$ref ? resolvePointer(normalized, raw.$ref) : raw;
      if (parameter.in !== 'query') continue;
      requireCondition((parameter.style ?? 'form') === 'form' && (parameter.explode ?? true) === true,
        `Internal query serializer does not support ${parameter.style}/${parameter.explode}: ${row.method} ${row.path}`);
    }
  }
  const nodes = await openapiTS(normalized, {
    alphabetize: true,
    // A bare object must not silently become an unrestricted record.
    additionalProperties: false,
  });
  return '/**\n * Generated from the internal OpenAPI contract. Do not edit.\n'
    + ` * Contract SHA-256: ${sha256(canonicalJson(normalized))}\n */\n` + astToString(nodes);
}

export async function main(args) {
  const [input, output, mode = '--write'] = args;
  if (!input || !output || !['--write', '--check'].includes(mode)) {
    throw new Error('Usage: node generate.mjs <openapi.json> <schema.d.ts> [--write|--check]');
  }
  const document = JSON.parse(await fs.readFile(input, 'utf8'));
  const generated = await generateTypes(document);
  if (mode === '--check') {
    if (await fs.readFile(output, 'utf8') !== generated) throw new Error('Generated internal API types are stale');
  } else {
    await fs.mkdir(path.dirname(output), { recursive: true });
    await fs.writeFile(output, generated);
  }
  console.log(`Internal API types ${mode === '--check' ? 'verified' : 'generated'}: ${sha256(generated)}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
