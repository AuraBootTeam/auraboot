import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const pluginRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const readJson = (relativePath) =>
  JSON.parse(fs.readFileSync(path.join(pluginRoot, relativePath), 'utf8'));

function findBlock(blocks, id) {
  const queue = [...(blocks ?? [])];
  while (queue.length > 0) {
    const block = queue.shift();
    if (block.id === id) return block;
    queue.push(...(block.blocks ?? []), ...(block.children ?? []));
    for (const tab of block.tabs ?? []) queue.push(...(tab.blocks ?? []));
  }
  return null;
}

test('lead and contact timelines request named-query records instead of dictionary options', () => {
  const cases = [
    ['crm_lead_common_detail.json', 'lead'],
    ['crm_contact_common_detail.json', 'contact'],
  ];

  for (const [file, objectType] of cases) {
    const page = readJson(`config/pages/${file}`);
    const timeline = findBlock(page.blocks, 'block_activities');
    assert.equal(timeline?.blockType, 'activity-timeline', `${file} must expose its timeline`);
    const params = timeline?.subTable?.dataSource?.params;
    assert.equal(params?.datasourceId, 'nq:crm_activities_by_object');
    assert.equal(params?.objectType, objectType);
    assert.equal(params?.objectId, '${recordPid}');
    assert.equal(
      params?.format,
      'records',
      `${file} must not let /api/datasource/list coerce activity rows into dictionary options`,
    );
  }
});

test('lead detail requires explicit confirmation before irreversible graph conversion', () => {
  const page = readJson('config/pages/crm_lead_common_detail.json');
  const toolbar = findBlock(page.blocks, 'crm_lead_detail_toolbar');
  const convert = toolbar?.buttons?.find((button) => button.code === 'convert');
  assert.equal(convert?.action?.command, 'crm:convert_lead');
  assert.match(convert?.confirm?.['zh-CN'] ?? '', /客户、联系人、商机和客户需求/);
  assert.match(convert?.confirm?.['zh-CN'] ?? '', /跟进记录与评论将继续保留/);
  assert.match(convert?.confirm?.['en-US'] ?? '', /follow-up records and comments will be preserved/i);
});

test('account timeline deduplicates one activity linked through account, contact and opportunity', () => {
  const queries = readJson('config/named-queries.json');
  const timeline = queries.find((query) => query.code === 'crm_account_timeline');
  assert.ok(timeline, 'crm_account_timeline must remain registered');
  assert.match(timeline.fromSql, /DISTINCT ON \(a\.pid\)/);
  assert.match(timeline.fromSql, /WHEN 'account' THEN 0 WHEN 'contact' THEN 1 ELSE 2 END/);
  assert.match(timeline.fromSql, /ORDER BY timeline\.crm_act_date DESC$/);
  assert.doesNotMatch(timeline.fromSql, /^SELECT DISTINCT a\.\*/);
});
