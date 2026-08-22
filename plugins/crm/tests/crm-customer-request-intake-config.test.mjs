import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const readJson = (path) => JSON.parse(readFileSync(new URL(`../${path}`, import.meta.url), 'utf8'));
const fields = readJson('config/fields/crm_customer_request_common.json');
const bindings = readJson('config/bindings/crm_customer_request_common.json');
const commands = readJson('config/commands/crm_customer_request_common.json');
const detail = readJson('config/pages/crm_customer_request_common_detail.json');
const plugin = readJson('plugin.json');
const byCode = new Map(fields.map((field) => [field.code, field]));
const commandByCode = new Map(commands.map((command) => [command.code, command]));

const intakeFieldCodes = [
  'crm_cr_source_business_key',
  'crm_cr_source_system',
  'crm_cr_source_message_ref',
  'crm_cr_source_received_at',
  'crm_cr_source_content_hash',
  'crm_cr_intake_snapshot',
  'crm_cr_source_provenance',
  'crm_cr_field_evidence',
  'crm_cr_field_evidence_count',
  'crm_cr_intake_client_request_id',
  'crm_cr_ingested_at',
];

test('external intake is one handler-owned idempotent Customer Request command', () => {
  const intake = commandByCode.get('crm:intake_customer_request');
  assert.equal(intake?.type, 'custom');
  assert.equal(intake?.modelCode, 'crm_customer_request_common');
  assert.equal(intake?.handler, 'crm:intake_customer_request');
  assert.equal(intake?.handlerParams?.dslPersistence, false);
  assert.equal(intake?.idempotent, true);
  assert.match(intake?.concurrencyKey, /source_channel/);
  assert.match(intake?.concurrencyKey, /source_system/);
  assert.match(intake?.concurrencyKey, /source_message_ref/);
  assert.deepEqual(intake?.permissions, ['crm.customer_request.manage']);
  assert.ok(plugin.provides.some((item) =>
    item.type === 'command' && item.code === 'crm:intake_customer_request'));
  assert.equal(plugin.provides.filter((item) =>
    item.type === 'model' && item.code === 'crm_customer_request_common').length, 0,
  'the slice must extend the existing writer rather than advertise a second Customer Request model');
});

test('source identity, original snapshot, provenance and evidence are immutable writer facts', () => {
  for (const code of intakeFieldCodes) {
    const field = byCode.get(code);
    assert.ok(field, `${code} must exist`);
    assert.equal(field.immutable, true, `${code} must be immutable`);
    assert.deepEqual(field.allowedWriterCommands, ['crm:intake_customer_request'],
      `${code} must have one writer`);
    const binding = bindings.find((item) => item.fieldCode === code);
    assert.equal(binding?.editable, false, `${code} must not be editable`);
  }
  assert.equal(byCode.get('crm_cr_source_business_key')?.constraints?.unique, true);
  assert.equal(bindings.find((item) =>
    item.fieldCode === 'crm_cr_source_business_key')?.visible, false);
  assert.equal(bindings.find((item) =>
    item.fieldCode === 'crm_cr_intake_snapshot')?.visible, false);
  assert.equal(bindings.find((item) =>
    item.fieldCode === 'crm_cr_field_evidence')?.visible, false);
});

test('source channel becomes immutable at creation and cannot be rewritten by ordinary edit', () => {
  const sourceChannel = byCode.get('crm_cr_source_channel');
  assert.equal(sourceChannel?.immutable, true);
  assert.deepEqual(sourceChannel?.allowedWriterCommands, [
    'crm:create_customer_request',
    'crm:convert_lead',
    'crm:intake_customer_request',
  ]);
  assert.ok(commandByCode.get('crm:create_customer_request')?.inputFields
    .includes('crm_cr_source_channel'));
  assert.ok(!commandByCode.get('crm:update_customer_request')?.inputFields
    .includes('crm_cr_source_channel'));
});

test('Customer Request detail exposes a localized read-only evidence summary without raw JSON', () => {
  const tabs = detail.blocks.find((block) => block.id === 'crm_cr_tabs')?.tabs ?? [];
  const evidenceTab = tabs.find((tab) => tab.key === 'source_evidence');
  assert.ok(evidenceTab?.label?.['zh-CN']);
  assert.ok(evidenceTab?.label?.['en-US']);
  const section = evidenceTab.blocks.find((block) => block.id === 'crm_cr_source_evidence');
  assert.equal(section?.blockType, 'detail-section');
  const displayed = new Set(section?.fields?.map((field) => field.field));
  for (const code of [
    'crm_cr_source_channel',
    'crm_cr_source_system',
    'crm_cr_source_message_ref',
    'crm_cr_source_received_at',
    'crm_cr_ingested_at',
    'crm_cr_field_evidence_count',
    'crm_cr_source_content_hash',
  ]) assert.ok(displayed.has(code), `${code} must be visible in the evidence summary`);
  for (const hidden of [
    'crm_cr_source_business_key',
    'crm_cr_intake_snapshot',
    'crm_cr_source_provenance',
    'crm_cr_field_evidence',
    'crm_cr_intake_client_request_id',
  ]) assert.ok(!displayed.has(hidden), `${hidden} must not leak raw evidence or internal identity`);
});
