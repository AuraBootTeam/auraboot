import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { generateTypes } from './toolchain/generate.mjs';
const ts = createRequire(new URL('./toolchain/package.json', import.meta.url))('typescript');
const document = { openapi: '3.1.0', info: { title: 'Internal', version: '1' }, paths: {
  '/api/example': { post: { operationId: 'create', requestBody: { required: true, content: {
    'application/json': { schema: { $ref: '#/components/schemas/Create' } },
  } }, responses: { 200: { description: 'OK' } } } },
}, components: { schemas: { Create: { type: 'object', required: ['title'], properties: {
  title: { type: 'string' }, state: { type: ['string', 'null'], enum: ['draft', null] },
} } } } };

test('generates deterministic required, enum and nullable types that reject invalid consumers', async () => {
  const generated = await generateTypes(document);
  assert.equal(generated, await generateTypes(document));
  const consumer = `import type {components} from './generated';
    type Create=components['schemas']['Create'];
    const valid:Create={title:'Example',state:null};
    // @ts-expect-error required title
    const missing:Create={};
    // @ts-expect-error schema enum
    const wrong:Create={title:'Example',state:'published'};
  `;
  const files = new Map([['/consumer.ts', consumer], ['/generated.ts', generated]]);
  const options = { strict: true, noEmit: true, target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Bundler };
  const host = ts.createCompilerHost(options), original = host.getSourceFile.bind(host);
  host.getSourceFile = (file, version, ...rest) => files.has(file)
    ? ts.createSourceFile(file, files.get(file), version, true) : original(file, version, ...rest);
  const fileExists = host.fileExists.bind(host); host.fileExists = file => files.has(file) || fileExists(file);
  const readFile = host.readFile.bind(host); host.readFile = file => files.get(file) ?? readFile(file);
  const program = ts.createProgram(['/consumer.ts', '/generated.ts'], options, host);
  const errors = ts.getPreEmitDiagnostics(program).filter(d => d.category === ts.DiagnosticCategory.Error);
  assert.deepEqual(errors.map(d => ts.flattenDiagnosticMessageText(d.messageText, '\n')), []);
});

test('rejects unsupported query encodings including referenced parameters', async () => {
  const changed = structuredClone(document);
  changed.components.parameters = { filter: { name: 'values', in: 'query', style: 'pipeDelimited',
    explode: false, schema: { type: 'array', items: { type: 'string' } } } };
  changed.paths['/api/example'].post.parameters = [{ $ref: '#/components/parameters/filter' }];
  await assert.rejects(generateTypes(changed), /serializer does not support/);
});
