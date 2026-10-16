import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
const json = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const page = json('../config/pages/tpm_customer_ai_intake.json');
const menus = json('../config/menus.json');
const commands = json('../config/commands/tpm_customer.json');
const bindings = json('../config/bindings/tpm_customer.json');
const blocks = page.blocks;
test('AI intake is an explicit guarded custom page backed by the real customer model', () => {
  assert.equal(page.kind, 'form');
  assert.equal(page.schemaVersion, 4);
  assert.equal(page.modelCode, 'tpm_customer');
  const menu = menus.find((item) => item.code === page.pageKey);
  assert.ok(menu);
  assert.equal(menu.path, `/p/c/${page.pageKey}`);
  assert.equal(menu.parentCode, 'tpm_root');
  assert.equal(menu.permissionCode, 'tpm.project.manage');
  assert.equal(page.extension.backTo, '/p/tpm_customer');
  assert.equal(page.extension.afterSubmitRedirect, '/p/tpm_customer');
});
test('only the dedicated page opts into the existing conversation-backed AI fill block', () => {
  assert.equal(blocks.filter((block) => block.blockType === 'ai-fill-banner').length, 1);
  assert.equal(blocks.find((block) => block.blockType === 'ai-fill-banner').endpoint, undefined);
  for (const key of ['tpm_customer_form', 'tpm_project_form']) {
    assert.ok(
      !json(`../config/pages/${key}.json`).blocks.some(
        (block) => block.blockType === 'ai-fill-banner',
      ),
    );
  }
});
test('the reviewed form and save command share actual model fields and required bindings', () => {
  const fields = blocks.find((block) => block.blockType === 'form-section').fields;
  assert.deepEqual(
    fields.map((field) => field.field),
    ['tpm_cu_name', 'tpm_cu_contact'],
  );
  for (const field of fields) {
    const binding = bindings.find((item) => item.fieldCode === field.field);
    assert.ok(binding);
    if (binding.required) assert.equal(field.required, true);
    assert.ok(field.props.placeholder['zh-CN']);
    assert.ok(field.props.helpText['zh-CN']);
  }
  const button = blocks.find((block) => block.blockType === 'form-buttons').buttons[0];
  assert.equal(button.commandCode, 'tpm:create_customer');
  const command = commands.find((item) => item.code === button.commandCode);
  assert.equal(command.type, 'create');
  assert.equal(command.modelCode, page.modelCode);
  assert.deepEqual(
    command.inputFields,
    fields.map((field) => field.field),
  );
  assert.ok(command.permissions.includes('tpm.project.manage'));
});
