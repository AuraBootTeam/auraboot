import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const pluginRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (relative) => JSON.parse(fs.readFileSync(path.join(pluginRoot, 'config', relative), 'utf8'));
const commands = new Map(read('commands/crm_quote_summary_common.json').map((item) => [item.code, item]));
const fields = new Map(read('fields/crm_quote_summary_common.json').map((item) => [item.code, item]));
const bindings = new Map(read('bindings/crm_quote_summary_common.json')
  .map((item) => [item.fieldCode, item]));
const roles = new Map(read('roles.json').map((item) => [item.code, item]));
const models = read('models.json');
const menus = read('menus.json');
const quoteDetail = read('pages/crm_quote_summary_common_detail.json');
const qdpDetail = read('pages/crm_qdp_revision_common_detail.json');

function blocks(value) {
  const result = [];
  const visit = (candidate) => {
    if (!candidate || typeof candidate !== 'object') return;
    if (candidate.blockType) result.push(candidate);
    for (const child of candidate.blocks ?? []) visit(child);
    for (const tab of candidate.tabs ?? []) {
      for (const child of tab.blocks ?? []) visit(child);
    }
  };
  for (const block of value.blocks ?? []) visit(block);
  return result;
}

test('order commitment reuses Quote Summary and introduces no model or menu', () => {
  assert.equal(models.filter((model) => model.code === 'crm_quote_summary_common').length, 1);
  assert.equal(menus.some((menu) => menu.code.includes('order_commitment')), false);
  assert.equal(models.some((model) => model.code.includes('order_commitment')), false);
});

test('order commitment command is a handler-owned optimistic business transition', () => {
  const command = commands.get('crm:record_order_commitment');
  assert.ok(command);
  assert.equal(command.type, 'custom');
  assert.equal(command.modelCode, 'crm_quote_summary_common');
  assert.equal(command.handler, 'crm:record_order_commitment');
  assert.equal(command.handlerParams.dslPersistence, false);
  assert.deepEqual(command.permissions, ['crm.order_commitment.manage']);
  assert.equal(command.cmd_risk_level, 'L2');
  assert.equal(command.idempotent, false);
  assert.match(command.concurrencyKey, /recordPid/);
});

test('commitment fields are immutable references and audit facts', () => {
  const expected = {
    crm_qs_committed_qdp_revision_id: ['reference', 'crm_qdp_revision_common'],
    crm_qs_customer_confirmation_id: ['reference', 'crm_customer_confirmation_common'],
    crm_qs_order_committed_at: ['datetime', undefined],
    crm_qs_order_committed_by: ['string', undefined],
  };
  for (const [code, [dataType, referenceModelCode]] of Object.entries(expected)) {
    assert.equal(fields.get(code)?.dataType, dataType, code);
    assert.equal(fields.get(code)?.referenceModelCode, referenceModelCode, code);
    assert.deepEqual(fields.get(code)?.allowedWriterCommands,
      ['crm:record_order_commitment'], `${code} must be command-write-only`);
    assert.equal(bindings.get(code)?.editable, false, `${code} must not be manually editable`);
  }
});

test('formal release-manager entry records and drills through the exact evidence chain', () => {
  const toolbar = blocks(quoteDetail).find((block) => block.id === 'crm_qs_detail_toolbar');
  const record = toolbar.buttons.find((button) => button.code === 'record_order_commitment');
  assert.equal(record.permissionCode, 'crm.order_commitment.manage');
  assert.equal(record.action.command, 'crm:record_order_commitment');
  assert.match(record.visibleWhen, /accepted/);
  assert.match(record.visibleWhen, /committed_qdp_revision_id/);
  const qdpInput = record.action.inputFields.find((field) => field.field === 'crm_qdp_revision_id');
  assert.equal(qdpInput.type, 'select');
  assert.equal(qdpInput.required, true);
  assert.equal(qdpInput.dataSource.endpoint, '/api/dynamic/crm_qdp_revision_common/list');
  assert.equal(qdpInput.dataSource.valueField, 'pid');

  const open = toolbar.buttons.find((button) => button.code === 'open_committed_qdp');
  assert.equal(open.permissionCode, 'crm.qdp.read');
  assert.equal(open.action.to,
    '/p/crm_qdp_revision_common/view/{crm_qs_committed_qdp_revision_id}');

  const reverse = blocks(qdpDetail).find((block) => block.id === 'crm_qdp_order_commitments');
  assert.equal(reverse.blockType, 'sub-table');
  assert.equal(reverse.subTable.childModel, 'crm_quote_summary_common');
  assert.equal(reverse.subTable.parentField, 'crm_qs_committed_qdp_revision_id');

  const releaseManager = roles.get('crm_qdp_release_manager');
  assert.ok(releaseManager.permissions.includes('crm.order_commitment.manage'));
  assert.ok(releaseManager.permissions.includes('crm.qdp.release'));
  assert.equal(releaseManager.defaultDataScopeType, 'all');
  assert.ok(!roles.get('crm_admin').permissions.includes('crm.order_commitment.manage'));
  assert.ok(!roles.get('crm_sales_manager').permissions.includes('crm.order_commitment.manage'));
  assert.ok(!roles.get('crm_sales').permissions.includes('crm.order_commitment.manage'));
  assert.ok(!roles.get('crm_viewer').permissions.includes('crm.order_commitment.manage'));
});
