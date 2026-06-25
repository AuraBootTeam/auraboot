import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';

const root = path.resolve(import.meta.dirname, '..');
const readJson = (relativePath) =>
  JSON.parse(fs.readFileSync(path.join(root, relativePath), 'utf8'));

test('team CRUD is declared as a DSL facade over the platform ab_team table', () => {
  const models = readJson('config/models.json');
  const teamModel = models.find((model) => model.code === 'ab_team');

  assert.ok(teamModel, 'ab_team model must be imported by org-management');
  assert.equal(teamModel.extension.tableName, 'ab_team');
  assert.equal(teamModel.extension.skipTableCreation, true);
  assert.equal(teamModel.extension.titleField, 'name');
});

test('team list/form/detail pages are present and route to the platform team model', () => {
  const pages = ['ab_team_list', 'ab_team_form', 'ab_team_detail'].map((pageKey) =>
    readJson(`config/pages/${pageKey}.json`),
  );

  assert.deepEqual(
    pages.map((page) => [page.pageKey, page.kind, page.modelCode]),
    [
      ['ab_team_list', 'list', 'ab_team'],
      ['ab_team_form', 'form', 'ab_team'],
      ['ab_team_detail', 'detail', 'ab_team'],
    ],
  );

  const listPage = pages[0];
  const tableColumns = listPage.blocks.find((block) => block.id === 'team_table').columns;
  for (const column of tableColumns) {
    assert.ok(column.label?.['zh-CN'], `team list column ${column.field} must define zh-CN label`);
  }

  const createButton = listPage.blocks
    .find((block) => block.id === 'team_toolbar')
    .buttons.find((button) => button.code === 'create');
  assert.equal(createButton.action.to, '/organization/teams/new');
  assert.equal(createButton.action.command, 'org:create_team');

  const formPage = pages[1];
  const formFields = formPage.blocks.find((block) => block.id === 'team_basic').fields;
  assert.equal(
    formFields.find((field) => field.field === 'status')?.required,
    true,
    'status is a required model field and must be required in editable forms',
  );
  assert.equal(
    formFields.find((field) => field.field === 'status')?.dictCode,
    'org_team_status',
    'team status form field must not fall back to the shared tenant member status dictionary',
  );

  const detailPage = pages[2];
  const detailFields = detailPage.blocks.find((block) => block.id === 'team_basic').fields;
  assert.equal(
    detailFields.find((field) => field.field === 'status')?.dictCode,
    'org_team_status',
    'team status detail field must use the team status dictionary',
  );

  const memberBlock = detailPage.blocks.find((block) => block.id === 'team_members');
  assert.ok(memberBlock, 'team detail should include the members management block');
  assert.equal(memberBlock.blockType, 'custom');
  assert.equal(memberBlock.component, 'TeamMembersBlock');
  assert.equal(memberBlock.props?.teamPidField, 'pid');
});

test('team commands whitelist only fields that are written to ab_team', () => {
  const createCommand = readJson('config/commands/org_create_team.json');
  const updateCommand = readJson('config/commands/org_update_team.json');
  const deleteCommand = readJson('config/commands/org_delete_team.json');

  assert.equal(createCommand.modelCode, 'ab_team');
  assert.deepEqual(createCommand.inputFields, ['code', 'name', 'description', 'leader_id']);
  assert.equal(createCommand.autoSetFields.status.value, 'active');

  assert.equal(updateCommand.modelCode, 'ab_team');
  assert.deepEqual(updateCommand.inputFields, ['name', 'description', 'leader_id', 'status']);

  assert.equal(deleteCommand.modelCode, 'ab_team');
  assert.equal(deleteCommand.type, 'delete');
});
