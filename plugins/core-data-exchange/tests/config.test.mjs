import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const json = (file) => JSON.parse(readFileSync(resolve(root, file), 'utf8'));

test('formal document artifacts are immutable, command-only, and owner scoped', () => {
  const model = json('config/models.json').find((item) => item.code === 'ab_document_artifact');
  assert.ok(model);
  assert.equal(model.immutable, true);
  assert.equal(model.commandOnlyCreate, true);
  assert.equal(model.extension?.dataScope?.ownerField, 'created_by');

  const bindings = json('config/bindings.json')
    .filter((item) => item.modelCode === 'ab_document_artifact');
  const byField = new Map(bindings.map((item) => [item.fieldCode, item]));
  for (const required of [
    'ab_da_job_key', 'ab_da_definition_code', 'ab_da_definition_version',
    'ab_da_document_version', 'ab_da_business_version', 'ab_da_renderer_version',
    'ab_da_source_model', 'ab_da_source_record_pid', 'ab_da_filename', 'ab_da_format',
    'ab_da_file_id', 'ab_da_download_url', 'ab_da_file_size',
    'ab_da_checksum_sha256', 'ab_da_status',
  ]) {
    assert.equal(byField.get(required)?.required, true, `${required} must be required`);
    assert.equal(byField.get(required)?.editable, false, `${required} must be read-only`);
  }
});

test('job center is a reachable owner task projection with refresh and artifact actions', () => {
  const page = json('config/pages.json').find((item) => item.pageKey === 'exchange_job_center');
  assert.ok(page);
  assert.equal(page.dataSources?.jobs?.endpoint, '/api/exchange/jobs?limit=50');
  assert.equal(page.dataSources?.jobs?.method, 'get');

  const toolbar = page.blocks.find((block) => block.id === 'job_actions');
  const refresh = toolbar?.buttons?.find((button) => button.code === 'refresh_jobs');
  assert.equal(refresh?.action?.steps?.[0]?.action, 'reloadDataSource');
  assert.equal(refresh?.action?.steps?.[0]?.args?.dataSourceId, 'jobs');

  const table = page.blocks.find((block) => block.id === 'job_table');
  const columns = new Map(table?.columns?.map((column) => [column.field, column]));
  for (const field of ['type', 'status', 'progress', 'summary', 'createdAt', 'completedAt', 'downloadUrl']) {
    assert.ok(columns.has(field), `job table must expose ${field}`);
  }
  assert.equal(columns.get('summary')?.width, 220, 'job result must remain readable without premature truncation');
  assert.equal(columns.get('downloadUrl')?.valueType, 'link');
  for (const forbidden of ['fileKey', 'localPath', 'cloudKey', 'errorDetails']) {
    assert.equal(columns.has(forbidden), false, `job table must hide ${forbidden}`);
  }

  const menu = json('config/menus.json').find((item) => item.pageKey === 'exchange_job_center');
  assert.equal(menu?.path, '/p/c/exchange_job_center');
  assert.equal(menu?.visible, true);
});
