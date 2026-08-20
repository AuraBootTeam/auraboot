import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const commands = JSON.parse(
  await readFile(new URL('../config/commands/crm_context_actions.json', import.meta.url), 'utf8'),
);

test('CRM contextual activity commands require a source PID and create the relation atomically', () => {
  assert.ok(commands.length > 0, 'context action command catalog should not be empty');

  for (const command of commands) {
    assert.equal(command.autoSetFields?.crm_act_date?.strategy, 'current_datetime');
    assert.equal(
      command.autoSetFields?.crm_act_date?.preserveInput,
      true,
      `${command.code} should default a missing activity timestamp without overwriting user input`,
    );

    const sourcePreconditions = (command.preconditions ?? []).filter(
      (item) => item.field === 'sourceRecordPid',
    );
    assert.deepEqual(
      sourcePreconditions.map((item) => item.operator),
      ['NOT_NULL', 'NEQ'],
      `${command.code} should reject missing and blank sourceRecordPid values before persistence`,
    );
    assert.equal(sourcePreconditions[1]?.value, '');

    assert.equal(command.sideEffects?.length, 1, `${command.code} should have one relation effect`);
    const effect = command.sideEffects[0];
    assert.equal(
      effect.condition,
      undefined,
      `${command.code} relation creation must not be skipped after its preconditions pass`,
    );
    assert.equal(effect.actions?.length, 1, `${command.code} should create exactly one relation`);
    assert.equal(effect.actions[0]?.type, 'create_record');
    assert.equal(effect.actions[0]?.modelCode, 'crm_activity_relation_common');
    assert.equal(effect.actions[0]?.fields?.crm_ar_activity_id, '${recordPid}');
    assert.equal(effect.actions[0]?.fields?.crm_ar_object_id, '${sourceRecordPid}');
  }
});
