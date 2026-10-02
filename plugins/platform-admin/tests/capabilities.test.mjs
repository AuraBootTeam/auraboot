import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const caps = JSON.parse(source('../config/capabilities.json'));
const constants = new Map([...source('../../../platform/src/main/java/com/auraboot/framework/permission/constants/MetaPermission.java')
  .matchAll(/public static final String (\w+) = "([^"]+)";/g)].map(match => [match[1], match[2]]));
const controllers = {
  'sys.cap.cloud_config': 'cloudconfig/controller/CloudConfigController.java',
  'sys.cap.notification_rule': 'notification/controller/NotificationRuleController.java',
  'sys.cap.integration': 'connector/controller/ApiConnectorController.java',
  'sys.cap.scheduled_task': 'scheduler/controller/ScheduledTaskController.java',
  'sys.cap.data_permission': 'meta/controller/config/DataPermissionController.java',
  'sys.cap.plugin': 'plugin/controller/PluginController.java',
};
test('platform management capabilities include the real controller guards', () => {
  for (const [code, file] of Object.entries(controllers)) {
    const cap = caps.find(item => item.code === code);
    const controller = source(`../../../platform/src/main/java/com/auraboot/framework/${file}`);
    const guards = [...controller.matchAll(/@RequirePermission\(MetaPermission\.(\w+)\)/g)].map(match => constants.get(match[1]));
    assert.ok(guards.length, `${file} must provide non-vacuous guard evidence`);
    for (const guard of guards) assert.ok(cap.includes.includes(guard), `${code} must include ${guard}`);
  }
});
test('platform DSL maintenance capabilities include model reads, CRUD and command execution', () => {
  const commands = JSON.parse(source('../config/commands.json'));
  for (const [code, models] of Object.entries({
    'sys.cap.data_permission': ['data_permission'],
    'sys.cap.integration': ['webhook', 'api_connector'],
    'sys.cap.scheduled_task': ['scheduled_task'],
  })) {
    const includes = caps.find(item => item.code === code).includes;
    assert.ok(includes.includes(constants.get('COMMAND_EXECUTE')));
    for (const model of models) {
      assert.ok(includes.includes(`model.${model}.read`));
      const modelCommands = commands.filter(command => command.modelCode === model);
      assert.ok(modelCommands.length);
      for (const command of modelCommands) assert.ok(includes.includes(`model.${model}.${command.type}`));
    }
  }
});
