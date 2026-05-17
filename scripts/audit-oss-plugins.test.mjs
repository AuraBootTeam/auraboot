import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..');
const optionalEnterpriseRoot = path.resolve(repoRoot, '..', 'auraboot-enterprise');

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(repoRoot, relativePath), 'utf8'));
}

function collectValues(value, predicate, matches = []) {
  if (Array.isArray(value)) {
    for (const item of value) collectValues(item, predicate, matches);
    return matches;
  }
  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      if (predicate(key, child)) {
        matches.push({ key, value: child });
      }
      collectValues(child, predicate, matches);
    }
  }
  return matches;
}

describe('OSS plugin config audit', () => {
  it('keeps ACP priority fields namespaced away from core announcement priority', () => {
    const fields = readJson('plugins/agent-control-plane/config/fields.json');
    const bindings = readJson('plugins/agent-control-plane/config/bindings.json');
    const commands = readJson('plugins/agent-control-plane/config/commands.json');
    const pages = readJson('plugins/agent-control-plane/config/pages.json');
    const namedQueries = readJson('plugins/agent-control-plane/config/named-queries.json');

    assert.equal(
      fields.some((field) => field.code === 'priority'),
      false,
      'ACP must not define global field code "priority"; use acp_priority for ACP-specific priority.',
    );

    const bareBindingModels = bindings
      .filter((binding) => binding.fieldCode === 'priority')
      .map((binding) => binding.modelCode);
    assert.deepEqual(bareBindingModels, [], 'ACP bindings must use acp_priority instead of priority.');

    const commandRefs = commands
      .filter((command) => Array.isArray(command.inputFields) && command.inputFields.includes('priority'))
      .map((command) => command.code);
    assert.deepEqual(commandRefs, [], 'ACP commands must use acp_priority instead of priority.');

    const pageRefs = collectValues(pages, (key, value) => key === 'field' && value === 'priority');
    assert.deepEqual(pageRefs, [], 'ACP pages must use acp_priority instead of priority.');

    const queryFieldRefs = collectValues(
      namedQueries,
      (key, value) => (key === 'code' || key === 'columnExpr') && value === 'priority',
    );
    assert.deepEqual(queryFieldRefs, [], 'ACP named-query fields must expose acp_priority instead of priority.');
  });

  it('keeps global priority field definitions type-compatible across mounted plugins', () => {
    const pluginRoots = [path.join(repoRoot, 'plugins')];
    const enterprisePlugins = path.join(optionalEnterpriseRoot, 'plugins');
    if (fs.existsSync(enterprisePlugins)) {
      pluginRoots.push(enterprisePlugins);
    }

    const definitions = [];
    for (const pluginRoot of pluginRoots) {
      for (const plugin of fs.readdirSync(pluginRoot)) {
        const fieldsPath = path.join(pluginRoot, plugin, 'config', 'fields.json');
        if (!fs.existsSync(fieldsPath)) continue;

        const fields = JSON.parse(fs.readFileSync(fieldsPath, 'utf8'));
        for (const field of fields) {
          if (field?.code === 'priority') {
            definitions.push({
              plugin,
              dataType: field.dataType,
              dictCode: field.dictCode ?? null,
            });
          }
        }
      }
    }

    const signatures = new Set(definitions.map((field) => `${field.dataType}:${field.dictCode ?? ''}`));
    assert.ok(
      signatures.size <= 1,
      `Global field code "priority" must not mix incompatible definitions: ${JSON.stringify(definitions)}`,
    );
  });

  it('orders the PCBA isolated-stack profile by plugin dependencies', () => {
    const script = fs.readFileSync(path.join(repoRoot, 'scripts/dev/import-isolated-plugins.sh'), 'utf8');
    const match = script.match(/PCBA_AGENT_PLUGINS=\(\n([\s\S]*?)\n\)/);
    assert.ok(match, 'PCBA_AGENT_PLUGINS profile must exist.');

    const plugins = match[1]
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'));

    const indexOf = (plugin) => plugins.indexOf(plugin);
    const before = (dependency, plugin) => {
      assert.ok(indexOf(dependency) >= 0, `${dependency} must be in PCBA_AGENT_PLUGINS.`);
      assert.ok(indexOf(plugin) >= 0, `${plugin} must be in PCBA_AGENT_PLUGINS.`);
      assert.ok(indexOf(dependency) < indexOf(plugin), `${dependency} must be imported before ${plugin}.`);
    };

    before('quality', 'procurement');
    before('procurement', 'pcba-solution');
    before('pcba-solution', 'pcba-sales');
    before('pcba-solution', 'pcba-manufacturing');
    before('pcba-industry', 'pcba-manufacturing');
    before('pcba-procurement', 'pcba-warehouse');
    before('pcba-manufacturing', 'pcba-warehouse');
    before('pcba-manufacturing', 'pcba-compliance');
  });

  it('defines an enterprise-demo isolated-stack profile without OSS demo templates', () => {
    const script = fs.readFileSync(path.join(repoRoot, 'scripts/dev/import-isolated-plugins.sh'), 'utf8');
    const match = script.match(/ENTERPRISE_DEMO_PLUGINS=\(\n([\s\S]*?)\n\)/);
    assert.ok(match, 'ENTERPRISE_DEMO_PLUGINS profile must exist.');

    const plugins = match[1]
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'));

    for (const forbidden of ['crm-quick-start', 'crm-starter', 'golden-path', 'hr-essentials', 'simple-inventory']) {
      assert.equal(plugins.includes(forbidden), false, `${forbidden} must not be in enterprise-demo.`);
    }

    for (const required of ['project-management', 'crm', 'product-catalog', 'sales', 'procurement', 'pcba-solution']) {
      assert.equal(plugins.includes(required), true, `${required} must be in enterprise-demo.`);
    }
  });

  it('prefers enterprise plugin directories for isolated-stack name collisions', () => {
    const script = fs.readFileSync(path.join(repoRoot, 'scripts/dev/import-isolated-plugins.sh'), 'utf8');
    const enterpriseBlock = script.match(/auto\|enterprise\)([\s\S]*?;;)/);

    assert.match(script, /container_plugin_path\(\)/, 'container_plugin_path function must exist.');
    assert.ok(enterpriseBlock, 'auto|enterprise edition branch must exist.');
    assert.ok(
      enterpriseBlock[1].indexOf('/app/plugins-enterprise/$plugin/plugin.json') <
        enterpriseBlock[1].indexOf('/app/plugins/$plugin/plugin.json'),
      'enterprise plugin directory must win over OSS templates for duplicate plugin names',
    );
  });

  it('supports explicit isolated-stack edition modes', () => {
    const script = fs.readFileSync(path.join(repoRoot, 'scripts/dev/import-isolated-plugins.sh'), 'utf8');

    assert.match(script, /--edition=auto\|oss\|enterprise/);
    assert.match(script, /case "\$EDITION" in/);
    assert.match(script, /auto\|oss\|enterprise/);
    assert.match(script, /oss\)/);
    assert.match(script, /auto\|enterprise\)/);
  });
});
