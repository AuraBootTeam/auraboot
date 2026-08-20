import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const activityDetail = JSON.parse(
  await readFile(new URL('../config/pages/crm_activity_common_detail.json', import.meta.url), 'utf8'),
);

test('CRM activity detail enables the platform record-comment journey through DSL', () => {
  assert.equal(activityDetail.kind, 'detail');
  assert.equal(activityDetail.modelCode, 'crm_activity_common');

  const block = activityDetail.blocks.find((candidate) => candidate.id === 'activity_followup_comments');
  assert.ok(block, 'activity detail should expose the follow-up comment block');
  assert.equal(block.blockType, 'record-comments');
  assert.equal(block.title['zh-CN'], '跟进评论');
  assert.equal(block.title['en-US'], 'Follow-up Comments');
});
