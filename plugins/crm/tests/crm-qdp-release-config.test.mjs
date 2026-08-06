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
  return (await Promise.all(files.map((file) => json(`${path}${file}`)))).flatMap((value) =>
    Array.isArray(value) ? value : [value]);
}

const [models, fields, bindings, commands, pages, permissions, roles, menus, dicts, plugin, backendBuild] =
  await Promise.all([
    json('models.json'),
    jsonDir('fields/'),
    jsonDir('bindings/'),
    jsonDir('commands/'),
    jsonDir('pages/'),
    json('permissions.json'),
    json('roles.json'),
    json('menus.json'),
    json('dicts.json'),
    JSON.parse(await readFile(new URL('../plugin.json', import.meta.url), 'utf8')),
    readFile(new URL('../backend/build.gradle', import.meta.url), 'utf8'),
  ]);

const modelByCode = new Map(models.map((model) => [model.code, model]));
const fieldByCode = new Map(fields.map((field) => [field.code, field]));
const bindingByKey = new Map(bindings.map((binding) =>
  [`${binding.modelCode}:${binding.fieldCode}`, binding]));
const commandByCode = new Map(commands.map((command) => [command.code, command]));
const pageByKey = new Map(pages.map((page) => [page.pageKey, page]));
const permissionCodes = new Set(permissions.map((permission) => permission.code));
const roleByCode = new Map(roles.map((role) => [role.code, role]));
const menuByCode = new Map(menus.map((menu) => [menu.code, menu]));
const dictByCode = new Map(dicts.map((dict) => [dict.code, dict]));

const LEGACY_QDP_FIELDS = [
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

const NEW_CONTENT_FIELDS = [
  'crm_qdp_requirement_version_id',
  'crm_qdp_requirement_version',
  'crm_qdp_file_package_id',
  'crm_qdp_file_package_hash',
  'crm_qdp_customer_confirmation_id',
  'crm_qdp_customer_confirmation_ref',
  'crm_qdp_customer_confirmed_hash',
  'crm_qdp_customer_confirmed_at',
  'crm_qdp_customer_confirmed_by',
  'crm_qdp_pack_set',
  'crm_qdp_pack_set_summary',
  'crm_qdp_downstream_impact',
  'crm_qdp_downstream_impact_summary',
  'crm_qdp_assumptions',
  'crm_qdp_approved_exceptions',
  'crm_qdp_version_diff',
  'crm_qdp_version_diff_summary',
];

function command(code) {
  const found = commandByCode.get(code);
  assert.ok(found, `command ${code} should exist`);
  return found;
}

function page(pageKey) {
  const found = pageByKey.get(pageKey);
  assert.ok(found, `page ${pageKey} should exist`);
  return found;
}

function block(pageKey, blockId) {
  const found = (page(pageKey).blocks ?? []).find((candidate) => candidate.id === blockId);
  assert.ok(found, `${pageKey} should expose ${blockId}`);
  return found;
}

test('QDP Release Center is a discoverable CRM minor release with one existing writer', () => {
  assert.equal(plugin.version, '1.2.0');
  assert.equal(plugin.backend?.jarPath, 'backend/build/libs/crm-plugin-1.2.0.jar');
  assert.match(backendBuild, /^version = '1\.2\.0'$/m);

  for (const [type, code] of [
    ['model', 'crm_qdp_revision_common'],
    ['model', 'crm_requirement_version_common'],
    ['model', 'crm_file_package_common'],
    ['model', 'crm_customer_confirmation_common'],
    ['command', 'crm:prepare_qdp_draft'],
    ['command', 'crm:submit_qdp_review'],
    ['command', 'crm:publish_qdp_revision'],
    ['command', 'crm:release_qdp'],
  ]) {
    assert.ok(plugin.provides.some((entry) => entry.type === type && entry.code === code),
      `${type} ${code} should be advertised`);
  }

  for (const code of [
    'crm:prepare_qdp_draft',
    'crm:submit_qdp_review',
    'crm:publish_qdp_revision',
    'crm:release_qdp',
  ]) {
    assert.equal(command(code).handler, 'crm:release_qdp', `${code} must reuse the shipped QDP writer`);
  }
});

test('published ce55 QDP fields remain present and retain the legacy release writer bridge', () => {
  for (const fieldCode of LEGACY_QDP_FIELDS) {
    const field = fieldByCode.get(fieldCode);
    assert.ok(field, `published field ${fieldCode} must not be deleted`);
    assert.ok(bindingByKey.has(`crm_qdp_revision_common:${fieldCode}`),
      `published binding ${fieldCode} must not be deleted`);
    assert.ok(field.allowedWriterCommands?.includes('crm:release_qdp'),
      `${fieldCode} must retain the ce55 release writer compatibility`);
  }

  for (const fieldCode of LEGACY_QDP_FIELDS.filter((code) => ![
    'crm_qdp_status',
    'crm_qdp_release_note',
    'crm_qdp_released_at',
    'crm_qdp_released_by',
  ].includes(code))) {
    assert.equal(fieldByCode.get(fieldCode).immutable, true,
      `${fieldCode} content must remain immutable after creation`);
  }
});

test('supporting evidence objects are immutable while QDP only exposes exact lifecycle writers', () => {
  for (const modelCode of [
    'crm_requirement_version_common',
    'crm_file_package_common',
    'crm_customer_confirmation_common',
  ]) {
    const model = modelByCode.get(modelCode);
    assert.ok(model, `${modelCode} should exist`);
    assert.equal(model.immutable, true);
    assert.equal(model.commandOnlyCreate, true);
    assert.equal(model.extension?.dataScope?.ownerField, 'created_by');
  }

  const qdp = modelByCode.get('crm_qdp_revision_common');
  assert.equal(qdp.immutable, false,
    'the model must permit guarded lifecycle transitions under the frozen Core field-writer contract');
  assert.equal(qdp.commandOnlyCreate, true);

  for (const fieldCode of NEW_CONTENT_FIELDS) {
    const field = fieldByCode.get(fieldCode);
    assert.ok(field, `${fieldCode} should exist`);
    assert.equal(field.immutable, true, `${fieldCode} must freeze at Draft preparation`);
    assert.deepEqual(field.allowedWriterCommands, ['crm:prepare_qdp_draft']);
    assert.equal(field.constraints?.required, undefined,
      `${fieldCode} stays schema-optional only for rolling ce55 release compatibility`);
    assert.equal(bindingByKey.get(`crm_qdp_revision_common:${fieldCode}`)?.required, false);
  }

  assert.deepEqual(fieldByCode.get('crm_qdp_status').allowedWriterCommands,
    ['crm:prepare_qdp_draft', 'crm:submit_qdp_review', 'crm:publish_qdp_revision', 'crm:release_qdp']);
  assert.deepEqual(fieldByCode.get('crm_qdp_review_submitted_at').allowedWriterCommands,
    ['crm:submit_qdp_review']);
  assert.deepEqual(fieldByCode.get('crm_qdp_superseded_by_revision_id').allowedWriterCommands,
    ['crm:publish_qdp_revision']);
  assert.equal(commands.some((candidate) => candidate.modelCode === 'crm_qdp_revision_common'
    && ['create', 'update', 'delete'].includes(candidate.type)), false);
});

test('prepare, review and publish commands have exact targets, permissions and one aggregate lock', () => {
  const expected = [
    ['crm:prepare_qdp_draft', 'crm_customer_request_common', 'crm.qdp.prepare', true],
    ['crm:submit_qdp_review', 'crm_qdp_revision_common', 'crm.qdp.review', false],
    ['crm:publish_qdp_revision', 'crm_qdp_revision_common', 'crm.qdp.release', false],
  ];
  for (const [code, modelCode, permission, idempotent] of expected) {
    const found = command(code);
    assert.equal(found.type, 'custom');
    assert.equal(found.modelCode, modelCode);
    assert.equal(found.handlerParams?.dslPersistence, false);
    assert.deepEqual(found.permissions, [permission]);
    assert.equal(found.concurrencyKey, 'crm:qdp:${payload.crm_qdp_customer_request_id}');
    assert.equal(found.lockTimeoutMs, 8000);
    assert.equal(found.idempotent, idempotent);
    assert.equal(Object.hasOwn(found, 'inputFields'), false,
      'dialog-only inputs must not be misdeclared as model-bound command fields');
    assert.ok(permissionCodes.has(permission));
  }

  const legacy = command('crm:release_qdp');
  assert.equal(legacy.modelCode, 'crm_customer_request_common');
  assert.deepEqual(legacy.inputFields, [
    'crm_qdp_customer_request_id',
    'crm_qdp_primary_file_id',
    'crm_qdp_file_manifest',
    'crm_qdp_release_note',
    'crm_qdp_pcba_rfq_id',
  ]);
  assert.deepEqual(legacy.permissions, ['crm.qdp.release']);
  assert.equal(legacy.handler, 'crm:release_qdp');
  assert.equal(legacy.concurrencyKey, 'crm:qdp:${payload.crm_qdp_customer_request_id}');
  assert.equal(legacy.lockTimeoutMs, 8000);
});

test('role split keeps release on the explicit cross-owner composite duty role', () => {
  const rolePermissions = (code) => new Set(roleByCode.get(code)?.permissions ?? []);
  for (const code of ['crm_admin', 'crm_sales_manager']) {
    const granted = rolePermissions(code);
    for (const permission of ['crm.qdp.prepare', 'crm.qdp.review', 'crm.qdp.read']) {
      assert.ok(granted.has(permission), `${code} should receive ${permission}`);
    }
    assert.equal(granted.has('crm.qdp.release'), false,
      `${code} must not bypass the explicit PCBA QDP release duty role`);
  }

  const sales = rolePermissions('crm_sales');
  assert.ok(sales.has('crm.qdp.prepare'));
  assert.ok(sales.has('crm.qdp.read'));
  assert.equal(sales.has('crm.qdp.review'), false);
  assert.equal(sales.has('crm.qdp.release'), false);

  for (const code of ['crm_service', 'crm_viewer']) {
    const granted = rolePermissions(code);
    for (const permission of ['crm.qdp.prepare', 'crm.qdp.review', 'crm.qdp.release']) {
      assert.equal(granted.has(permission), false, `${code} must not receive ${permission}`);
    }
  }
});

test('Customer Request and Release Center pages expose complete lifecycle feedback without raw JSON', () => {
  const customerRequest = page('crm_customer_request_common_detail');
  const toolbar = block(customerRequest.pageKey, 'crm_cr_detail_toolbar');
  const prepare = toolbar.buttons.find((button) => button.code === 'prepare_qdp_draft');
  assert.ok(prepare);
  assert.equal(prepare.permissionCode, 'crm.qdp.prepare');
  assert.equal(prepare.action.command, 'crm:prepare_qdp_draft');
  assert.equal(prepare.action.operationType, 'update');
  assert.equal(prepare.action.targetRecordPid, '${record.pid}');
  const inputs = new Set(prepare.action.inputFields.map((field) => field.field));
  for (const fieldCode of [
    'crm_qdp_primary_file_id',
    'crm_qdp_requirement_version',
    'crm_qdp_customer_confirmation_ref',
    'crm_qdp_customer_confirmed_by',
    'crm_qdp_customer_confirmed_at',
    'crm_qdp_pack_set',
    'crm_qdp_downstream_impact',
    'crm_qdp_assumptions',
    'crm_qdp_approved_exceptions',
  ]) assert.ok(inputs.has(fieldCode), `prepare dialog should collect ${fieldCode}`);

  const history = block(customerRequest.pageKey, 'crm_cr_qdp_history');
  const historyFields = new Set(history.subTable.columns.map((column) => column.field));
  for (const fieldCode of [
    'crm_qdp_status',
    'crm_qdp_requirement_version',
    'crm_qdp_file_package_hash',
    'crm_qdp_pack_set_summary',
  ]) assert.ok(historyFields.has(fieldCode));
  const historyStatus = history.subTable.columns.find((column) => column.field === 'crm_qdp_status');
  assert.equal(historyStatus?.dictCode, 'crm_qdp_lifecycle');
  assert.equal(historyStatus?.renderType, 'tag');

  const detail = page('crm_qdp_revision_common_detail');
  assert.equal(block(detail.pageKey, 'crm_qdp_identity').blockType, 'form-section',
    'frozen Core only preloads detail dictionaries from form-section blocks');
  const lifecycleButtons = new Map(block(detail.pageKey, 'crm_qdp_release_actions').buttons
    .map((button) => [button.code, button]));
  assert.equal(lifecycleButtons.get('submit_qdp_review')?.permissionCode, 'crm.qdp.review');
  assert.match(lifecycleButtons.get('submit_qdp_review')?.visibleWhen, /draft/);
  assert.equal(lifecycleButtons.get('release_qdp')?.permissionCode, 'crm.qdp.release');
  assert.match(lifecycleButtons.get('release_qdp')?.visibleWhen, /ready_for_review/);
  assert.equal(lifecycleButtons.get('release_qdp')?.action?.command, 'crm:publish_qdp_revision');
  const lifecycle = dictByCode.get('crm_qdp_lifecycle');
  assert.deepEqual(lifecycle?.items.map((item) => item.value), [
    'draft', 'compiling', 'validation_failed', 'ready_for_review', 'released', 'superseded',
  ]);
  assert.ok(dictByCode.has('crm_qdp_gate_verdict'));
  assert.equal(fieldByCode.get('crm_qdp_status')?.dictCode, 'crm_qdp_lifecycle',
    'detail rendering resolves lifecycle labels from field metadata');
  assert.equal(fieldByCode.get('crm_qdp_gate_verdict')?.dictCode, 'crm_qdp_gate_verdict',
    'detail rendering resolves GT-D04 labels from field metadata');
  for (const fieldCode of [
    'crm_qdp_content_hash',
    'crm_qdp_version_diff_summary',
    'crm_qdp_file_package_hash',
    'crm_qdp_customer_confirmed_hash',
  ]) {
    const configured = detail.blocks.flatMap((candidate) => candidate.fields ?? [])
      .find((field) => field.field === fieldCode);
    assert.equal(configured?.layout?.colSpan, 12,
      `${fieldCode} must use the renderer-supported full-width layout contract`);
    assert.equal(Object.hasOwn(configured ?? {}, 'colSpan'), false,
      `${fieldCode} must not use the ignored top-level colSpan alias`);
  }

  const detailText = JSON.stringify(detail);
  for (const rawField of [
    'crm_qdp_request_snapshot',
    'crm_qdp_file_manifest',
    'crm_qdp_pack_set"',
    'crm_qdp_downstream_impact"',
    'crm_qdp_assumptions',
    'crm_qdp_approved_exceptions',
    'crm_qdp_version_diff"',
  ]) {
    assert.equal(detailText.includes(rawField), false, `${rawField} must not leak raw JSON into the UI`);
  }

  const menu = menuByCode.get('crm_qdp_release_center');
  assert.equal(menu?.pageKey, 'crm_qdp_revision_common_list');
  assert.equal(menu?.permissionCode, 'crm.qdp.read');
});

test('all QDP page and binding references resolve', () => {
  const fieldCodes = fields.map((field) => field.code);
  assert.equal(new Set(fieldCodes).size, fieldCodes.length,
    'field codes are plugin-global; Requirement Version must not collide with CRM Review fields');
  for (const code of ['crm_reqv_code', 'crm_reqv_customer_request_id', 'crm_reqv_status']) {
    assert.ok(fieldByCode.has(code), `${code} should use the Requirement Version-specific prefix`);
  }

  for (const binding of bindings.filter((candidate) => candidate.modelCode.startsWith('crm_')
    && candidate.modelCode.includes('qdp')
      || ['crm_requirement_version_common', 'crm_file_package_common',
        'crm_customer_confirmation_common'].includes(candidate.modelCode))) {
    assert.ok(modelByCode.has(binding.modelCode), `binding model ${binding.modelCode} should resolve`);
    assert.ok(fieldByCode.has(binding.fieldCode), `binding field ${binding.fieldCode} should resolve`);
  }

  for (const pageKey of [
    'crm_customer_request_common_detail',
    'crm_qdp_revision_common_list',
    'crm_qdp_revision_common_detail',
  ]) {
    const serialized = JSON.stringify(page(pageKey));
    for (const match of serialized.matchAll(/"field":"([^"]+)"/g)) {
      if (match[1] === 'actions') continue;
      assert.ok(fieldByCode.has(match[1]), `${pageKey} field ${match[1]} should resolve`);
    }
  }
});
