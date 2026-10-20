import { test } from 'node:test';
import assert from 'node:assert/strict';
import { traceSourceGraph } from './source-graph.mjs';
const file = (file, text) => ({ file, text });

test('source graph follows router resources and workspace exports while retaining type-only candidates', () => {
  const graph = traceSourceGraph({
    entries: ['web-admin/app/routes.ts'],
    packages: [{ root: 'packages/client', manifest: { name: '@auraboot/client', exports: { './read': { import: './src/read.ts', types: './types/read.d.ts' } } } }],
    sources: [
      file('web-admin/app/routes.ts', `import {route as page} from '@react-router/dev/routes'; import './plugins/core-sample/resources.js'; import type {Unused} from './legacy'; page('/home', './pages/home.tsx');`),
      file('web-admin/app/plugins/core-sample/resources.ts', `export const RESOURCES=[{file:'./pages/profile.tsx'}];`),
      file('web-admin/app/pages/home.tsx', `export {read} from '@auraboot/client/read';`),
      file('web-admin/app/pages/profile.tsx', `import {read} from '@auraboot/client/read';`),
      file('packages/client/src/read.ts', `export const read=()=>fetch('/api/profile');`),
      file('packages/client/types/read.d.ts', 'export declare const read: unknown;'),
      file('web-admin/app/legacy.ts', `import './missing';`),
    ],
  });
  assert.equal(graph.summary.reachable, 5);
  assert.deepEqual(graph.unresolved, []);
  assert.equal(graph.files.find(row => row.file === 'web-admin/app/legacy.ts').reachable, false);
  assert.equal(graph.files.find(row => row.file === 'packages/client/types/read.d.ts').reachable, false);
  assert.equal(graph.files.find(row => row.file === 'web-admin/app/pages/profile.tsx').reachedBy.kind, 'plugin-resource-file');
  assert.equal(graph.edges.some(row => row.specifier === './missing'), false, 'Dead modules cannot add live dependencies');
});

test('source graph keeps missing imports dynamic modules and indirect route files unresolved', () => {
  const graph = traceSourceGraph({ entries: ['web-admin/app/routes.ts'], sources: [
    file('web-admin/app/routes.ts', `import {route} from '@react-router/dev/routes'; import '~/missing'; import '~/app.css'; import '~/dsl.json'; import 'virtual:contribution'; import('./found'); import(dynamicModule); route('/dynamic', component);`),
    file('web-admin/app/found.ts', 'export const available=true;'),
  ] });
  assert.equal(graph.summary.reachable, 2);
  assert.deepEqual(graph.unresolved.map(row => row.boundary), ['missing-app-alias', 'data-resource', 'virtual-module', 'dynamic-module-expression', 'indirect-route-file']);
  assert.throws(() => traceSourceGraph({ sources: [], entries: [] }), /nonempty/);
  assert.throws(() => traceSourceGraph({ sources: [], entries: ['missing.ts'] }), /entry is missing/);
});

test('source graph respects package export boundaries and rejects ambiguous source identities', () => {
  const sources = [file('web-admin/app/root.tsx', `import '@auraboot/client/private'; import '@auraboot/client/feature/read';`), file('packages/client/private.ts', 'export const privateValue=1;'), file('packages/client/src/read.ts', 'export const read=1;')];
  const item = { root: 'packages/client', manifest: { name: '@auraboot/client', exports: { './feature/*': { import: './src/*.ts' } } } };
  const graph = traceSourceGraph({ sources, entries: ['web-admin/app/root.tsx'], packages: [item] });
  assert.equal(graph.summary.reachable, 2);
  assert.equal(graph.unresolved.length, 1);
  assert.equal(graph.unresolved[0].boundary, 'unresolved-workspace-export');
  assert.equal(graph.files.find(row => row.file === 'packages/client/private.ts').reachable, false);
  assert.throws(() => traceSourceGraph({ sources: [...sources, sources[0]], entries: ['web-admin/app/root.tsx'] }), /duplicate files/);
  assert.throws(() => traceSourceGraph({ sources, entries: ['web-admin/app/root.tsx'], packages: [item, item] }), /duplicate workspace package/);
  const specific = traceSourceGraph({ sources, entries: ['web-admin/app/root.tsx'], packages: [{ ...item, manifest: { name: '@auraboot/client', exports: { './feature/*': './src/*.ts', './*': './missing/*.ts' } } }] });
  assert.equal(specific.files.find(row => row.file === 'packages/client/src/read.ts').reachable, true, 'Specific exports win over broad wildcard declarations');
  const conditional = traceSourceGraph({ sources, entries: ['web-admin/app/root.tsx'], packages: [{ ...item, manifest: { name: '@auraboot/client', exports: { './feature/read': { browser: './browser.ts', default: './src/read.ts' } } } }] });
  assert.equal(conditional.files.find(row => row.file === 'packages/client/src/read.ts').reachable, false, 'Unknown execution conditions cannot silently select a default');
  assert.equal(conditional.unresolved.length, 2);
});
