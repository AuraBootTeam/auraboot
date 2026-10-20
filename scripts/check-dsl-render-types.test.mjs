import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { registeredRenderTypes, tableColumnRenderTypes, unsupportedRenderTypes, main } from './check-dsl-render-types.mjs';
const registrySource = `cellRendererRegistry.register('text', TextRenderer); cellRendererRegistry.register("status", StatusRenderer);`;
const tableSource = `const renderCellContent = (column, row) => { if (column.renderType === 'status-pill') { return renderStatusPill('neutral', row.status); } };`;
const registry = registeredRenderTypes(registrySource), table = tableColumnRenderTypes(tableSource);
const page = (blockType, renderType) => ({ blocks: [{ blockType, columns: [{ renderType, dictCode: 'status' }] }] });

test('reads actual registry calls instead of comments or string lookalikes', () => {
  assert.deepEqual([...registeredRenderTypes(registrySource + `\n// cellRendererRegistry.register('fake', Fake);\nconst text="cellRendererRegistry.register('also-fake', Fake)";`)].sort(), ['status', 'text']);
  assert.throws(() => registeredRenderTypes(`// cellRendererRegistry.register('fake', Fake);`), /0 renderer/);
});
test('allows implemented status pills only on direct table columns', () => {
  assert.deepEqual(unsupportedRenderTypes(page('table', 'status-pill'), registry, table), []);
  assert.equal(unsupportedRenderTypes(page('form-section', 'status-pill'), registry, table).length, 1);
  assert.equal(unsupportedRenderTypes({ blockType: 'table', fields: [{ renderType: 'status-pill' }] }, registry, table).length, 1);
  assert.equal(unsupportedRenderTypes({ blockType: 'table', columns: [{ props: { renderType: 'status-pill' } }] }, registry, table).length, 1);
});
test('removing a real table renderer branch makes its page fail', () => {
  const removed = tableColumnRenderTypes(tableSource.replace('if (column.renderType', 'if (column.otherProperty'));
  assert.equal(removed.size, 0);
  assert.equal(unsupportedRenderTypes(page('table', 'status-pill'), registry, removed).length, 1);
});
test('unknown renderers still fail in table blocks and nested pages', () => {
  assert.equal(unsupportedRenderTypes([page('table', 'invented'), page('form-section', 'invented')], registry, table).length, 2);
  assert.deepEqual(unsupportedRenderTypes(page('table', 'status'), registry, table), []);
});
test('comments strings and non-rendering conditions cannot declare table capabilities', () => {
  const text = `// ${tableSource}\nconst example=${JSON.stringify(tableSource)}; const renderCellContent=(column,row)=>{if(column.renderType==='invented'){log(row)}};`;
  assert.equal(tableColumnRenderTypes(text).size, 0);
  assert.throws(() => tableColumnRenderTypes('const renderCellContent = ( =>'), /Cannot parse/);
});
test('empty and malformed page inputs fail without producing a green gate', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsl-render-gate-'));
  try {
    const registryPath = path.join(root, 'web-admin/app/framework/meta/runtime/renderers');
    const tablePath = path.join(root, 'web-admin/app/framework/meta/rendering/blocks');
    const pages = path.join(root, 'plugins/sample/config');
    for (const dir of [registryPath, tablePath, pages]) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(registryPath, 'CellRendererRegistry.tsx'), registrySource);
    fs.writeFileSync(path.join(tablePath, 'TableBlockRenderer.tsx'), tableSource);
    assert.throws(() => main(root), /Checked 0/);
    fs.writeFileSync(path.join(pages, 'pages.json'), '{broken');
    assert.throws(() => main(root), SyntaxError);
    fs.writeFileSync(path.join(pages, 'pages.json'), JSON.stringify(page('table', 'status-pill')));
    assert.doesNotThrow(() => main(root));
    fs.writeFileSync(path.join(tablePath, 'TableBlockRenderer.tsx'), 'const renderCellContent = () => null;');
    assert.throws(() => main(root), /no renderer in this context/);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
