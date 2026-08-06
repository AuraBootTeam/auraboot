import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import test from 'node:test';

const configRoot = new URL('../config/', import.meta.url);

async function json(path) {
  return JSON.parse(await readFile(new URL(path, configRoot), 'utf8'));
}

async function jsonDir(path) {
  const root = new URL(path, configRoot);
  const files = (await readdir(root)).filter((file) => file.endsWith('.json')).sort();
  return (await Promise.all(files.map((file) => json(`${path}${file}`)))).flat();
}

const [models, fields, bindings, commands, pages, permissions, roles, plugin, backendBuild] = await Promise.all([
  json('models.json'),
  jsonDir('fields/'),
  jsonDir('bindings/'),
  jsonDir('commands/'),
  jsonDir('pages/'),
  json('permissions.json'),
  json('roles.json'),
  JSON.parse(await readFile(new URL('../plugin.json', import.meta.url), 'utf8')),
  readFile(new URL('../backend/build.gradle', import.meta.url), 'utf8'),
]);

const modelByCode = new Map(models.map((model) => [model.code, model]));
const fieldByCode = new Map(fields.map((field) => [field.code, field]));
const commandByCode = new Map(commands.map((command) => [command.code, command]));
const pageByKey = new Map(pages.map((page) => [page.pageKey, page]));
const permissionCodes = new Set(permissions.map((permission) => permission.code));

test('QDP ships as a discoverable backward-compatible CRM minor release', () => {
  assert.equal(plugin.version, '1.1.0',
    'a new public model/command capability must not be republished under the installed 1.0.0 version');
  assert.equal(plugin.backend?.jarPath, 'backend/build/libs/crm-plugin-1.1.0.jar');
  assert.match(backendBuild, /^version = '1\.1\.0'$/m,
    'PF4J manifest and archive version must stay in lockstep with plugin.json');
});

test('CRM owns one immutable QDP revision model under the Customer Request aggregate', () => {
  const model = modelByCode.get('crm_qdp_revision_common');
  assert.ok(model, 'crm_qdp_revision_common should exist in CRM');
  assert.equal(model.immutable, true,
    'the platform runtime must reject every update/delete path for released QDP rows');
  assert.equal(model.commandOnlyCreate, true,
    'generic dynamic create must not forge a released QDP row outside an authorized command');
  assert.equal(model.extension?.dataScope?.ownerField, 'created_by',
    'SELF scope must use the trusted numeric system owner, not numeric text in a string/user-pid field');
  assert.match(`${model.description} ${model.semantic_description}`, /Customer Request/i);
  assert.match(model.lifecycle_description, /immutable/i);

  const expectedFields = [
    'crm_qdp_code',
    'crm_qdp_customer_request_id',
    'crm_qdp_revision_no',
    'crm_qdp_schema_version',
    'crm_qdp_expected_request_version',
    'crm_qdp_source_revision',
    'crm_qdp_qualification_verdict',
    'crm_qdp_qualification_evidence_refs',
    'crm_qdp_content_hash',
    'crm_qdp_request_snapshot',
    'crm_qdp_file_manifest',
    'crm_qdp_primary_file_id',
    'crm_qdp_primary_filename',
    'crm_qdp_file_names',
    'crm_qdp_client_request_id',
    'crm_qdp_owner_scope',
    'crm_qdp_status',
    'crm_qdp_release_note',
    'crm_qdp_released_at',
    'crm_qdp_released_by',
  ];
  const bound = new Set(bindings
    .filter((binding) => binding.modelCode === 'crm_qdp_revision_common')
    .map((binding) => binding.fieldCode));
  for (const fieldCode of expectedFields) {
    assert.ok(fieldByCode.has(fieldCode), `field ${fieldCode} should exist`);
    assert.ok(bound.has(fieldCode), `field ${fieldCode} should be bound to QDP revision`);
    assert.deepEqual(fieldByCode.get(fieldCode).allowedWriterCommands, ['crm:release_qdp'],
      `field ${fieldCode} must only be materialized by the exact QDP release command`);
  }
  assert.equal(fieldByCode.get('crm_qdp_customer_request_id').referenceModelCode,
    'crm_customer_request_common');
  assert.equal(fieldByCode.get('crm_qdp_file_manifest').dataType, 'json');
  assert.equal(fieldByCode.get('crm_qdp_request_snapshot').dataType, 'json');
  assert.equal(fieldByCode.get('crm_qdp_content_hash').constraints.pattern, '^[a-f0-9]{64}$');
  assert.notEqual(fieldByCode.get('crm_qdp_code').constraints.unique, true,
    'global field uniqueness would incorrectly collide across tenants; composite uniqueness is an L1 gap');
  assert.ok(bindings.filter((binding) => binding.modelCode === 'crm_qdp_revision_common')
    .every((binding) => binding.editable === false));
  assert.equal(commands.some((command) => command.modelCode === 'crm_qdp_revision_common'
    && ['create', 'update', 'delete'].includes(command.type)), false,
  'immutable QDP revisions must not expose generic write commands');
});

test('ReleaseQdp is a CRM-owned handler command with permission, lock, and handler-only persistence', () => {
  const command = commandByCode.get('crm:release_qdp');
  assert.ok(command, 'crm:release_qdp should exist');
  assert.equal(command.modelCode, 'crm_customer_request_common');
  assert.equal(command.type, 'custom');
  assert.equal(command.handler, 'crm:release_qdp');
  assert.equal(command.handlerParams?.dslPersistence, false);
  assert.equal(Object.hasOwn(command, 'supportsDryRun'), false,
    'dry-run support is declared by the handler SPI, not an unsupported command JSON property');
  assert.deepEqual(command.permissions, ['crm.qdp.release']);
  assert.equal(command.concurrencyKey, 'crm:qdp:${payload.crm_qdp_customer_request_id}');
  for (const fieldCode of [
    'crm_qdp_customer_request_id',
    'crm_qdp_primary_file_id',
    'crm_qdp_file_manifest',
    'crm_qdp_release_note',
    'crm_qdp_pcba_rfq_id',
  ]) {
    assert.ok(command.inputFields.includes(fieldCode), `release command should accept ${fieldCode}`);
  }
  assert.equal(command.inputFields.includes('crm_qdp_primary_filename'), false,
    'client filenames are never accepted as command input; FileAccessor metadata is authoritative');
  assert.equal(command.inputFields.includes('crm_qdp_idempotency_key'), false,
    'idempotency comes from the server-owned clientRequestId command context');
  assert.equal(command.inputFields.includes('crm_qdp_expected_request_version'), false,
    'optimistic concurrency comes from the server-owned expectedVersion command context');
  assert.equal(command.inputFields.includes('crm_qdp_source_revision'), false,
    'the Customer Request row version is the only release concurrency token');
  assert.ok(permissionCodes.has('crm.qdp.release'));
  assert.ok(permissionCodes.has('crm.qdp.read'));
  assert.ok(permissionCodes.has('model.crm_qdp_revision_common.read'));
  for (const roleCode of ['crm_admin', 'crm_sales', 'crm_sales_manager']) {
    const role = roles.find((candidate) => candidate.code === roleCode);
    assert.ok(role?.permissions.includes('crm.qdp.read'), `${roleCode} should be able to read QDP history`);
    assert.ok(role?.permissions.includes('model.crm_qdp_revision_common.read'),
      `${roleCode} should receive model read permission`);
  }
  for (const roleCode of ['crm_admin', 'crm_sales', 'crm_sales_manager', 'crm_service', 'crm_viewer']) {
    const role = roles.find((candidate) => candidate.code === roleCode);
    assert.equal(role?.permissions.includes('crm.qdp.release'), false,
      `${roleCode} must not bypass the explicit PCBA QDP composite duty role`);
    assert.equal(role?.permissions.includes('sys.file.upload'), false,
      `${roleCode} must not gain the broad file-controller capability from base CRM`);
  }
  assert.ok(plugin.provides.some((entry) => entry.type === 'model'
    && entry.code === 'crm_qdp_revision_common'));
  assert.ok(plugin.provides.some((entry) => entry.type === 'command'
    && entry.code === 'crm:release_qdp'));
});

test('QDP revision history has explicit read-only list/detail/form DSL pages', () => {
  for (const kind of ['list', 'form', 'detail']) {
    const page = pageByKey.get(`crm_qdp_revision_common_${kind}`);
    assert.ok(page, `QDP ${kind} page should exist`);
    assert.equal(page.modelCode, 'crm_qdp_revision_common');
    assert.equal(page.kind, kind);
  }
  const form = pageByKey.get('crm_qdp_revision_common_form');
  const serialized = JSON.stringify(form);
  assert.equal(serialized.includes('crm:update_qdp'), false);
  assert.equal(serialized.includes('crm:delete_qdp'), false);
  const detail = pageByKey.get('crm_qdp_revision_common_detail');
  const readerSafePages = JSON.stringify([form, detail]);
  for (const internalField of [
    'crm_qdp_client_request_id',
    'crm_qdp_primary_file_id',
    'crm_qdp_file_manifest',
    'crm_qdp_request_snapshot',
    'crm_qdp_owner_scope',
    'crm_qdp_qualification_evidence_refs',
  ]) {
    assert.equal(readerSafePages.includes(internalField), false,
      `${internalField} must not be rendered to general QDP readers`);
    const binding = bindings.find((candidate) => candidate.modelCode === 'crm_qdp_revision_common'
      && candidate.fieldCode === internalField);
    assert.equal(binding?.visible, false,
      `${internalField} must stay out of generated general-reader surfaces`);
  }
});

test('Customer Request detail exposes generic Release QDP action with upload and command inputs', () => {
  const page = pageByKey.get('crm_customer_request_common_detail');
  const toolbar = page.blocks.find((block) => block.id === 'crm_cr_detail_toolbar');
  const button = toolbar.buttons.find((candidate) => candidate.code === 'release_qdp');
  assert.ok(button, 'Customer Request detail should expose Release QDP');
  assert.equal(button.permissionCode, 'crm.qdp.release');
  assert.equal(button.action.command, 'crm:release_qdp');
  assert.equal(button.action.operationType, 'update',
    'the action runtime derives expectedVersion from the targeted Customer Request row');
  assert.equal(button.action.targetRecordPid, '${record.pid}');
  assert.equal(button.action.payload.crm_qdp_customer_request_id, '${record.pid}');
  assert.equal(Object.hasOwn(button.action.payload, 'crm_qdp_expected_request_version'), false,
    'the browser must not copy a spoofable version into business payload');
  assert.equal(Object.hasOwn(button.action.payload, 'crm_qdp_source_revision'), false,
    'PCBA sidecar revisions are not release concurrency tokens');
  assert.equal(button.promptUpload.key, 'crm_qdp_primary_file_id');
  assert.doesNotMatch(button.visibleWhen, /submitted/,
    'unrouted submitted requests must not expose QDP release');
  assert.match(button.visibleWhen, /crm_cr_route_status === 'routed'/,
    'stale route target facts must not expose release after routing has failed or closed');
  assert.match(button.visibleWhen, /crm_cr_routed_package === 'pcba-crm'/);
  assert.match(button.visibleWhen,
    /crm_cr_routed_object_type === 'crm_customer_request_pcba_rfq'/);
  assert.match(button.visibleWhen, /crm_cr_routed_object_id/);
  const routeBlock = page.blocks
    .find((block) => block.id === 'crm_cr_tabs')
    .tabs.find((tab) => tab.key === 'routing')
    .blocks.find((block) => block.id === 'crm_cr_route_info');
  const routeStatus = routeBlock.fields.find((field) => field.field === 'crm_cr_route_status');
  assert.match(JSON.stringify(routeStatus.helpText), /PCBA.*DFM/i,
    'the routing section must explain why QDP release is unavailable before qualification');
  assert.equal(button.action.inputFields.some((field) => field.field === 'crm_qdp_idempotency_key'), false);

  const history = page.blocks.find((block) => block.id === 'crm_cr_qdp_history');
  assert.ok(history, 'Customer Request detail should show its QDP release history');
  const historyFields = new Set(history.subTable.columns.map((column) => column.field));
  for (const field of [
    'crm_qdp_revision_no',
    'crm_qdp_qualification_verdict',
    'crm_qdp_content_hash',
    'crm_qdp_file_names',
    'crm_qdp_released_by',
    'crm_qdp_released_at',
  ]) assert.ok(historyFields.has(field));
  assert.equal(historyFields.has('crm_qdp_primary_file_id'), false,
    'history table must not expose a raw public file pid');
});
