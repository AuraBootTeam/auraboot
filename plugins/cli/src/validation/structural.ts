import Ajv from 'ajv';
import { readFileSync, existsSync } from 'fs';
import { join, resolve } from 'path';
import type { PluginFiles } from '../utils/plugin-loader.js';
import { type ValidationResult, createResult, addMessage } from './types.js';

const SCHEMA_DIR = resolve(import.meta.dirname, '../../../../schemas');

const RESOURCE_SCHEMAS: Record<string, string> = {
  models: 'models.schema.json',
  fields: 'fields.schema.json',
  commands: 'commands.schema.json',
  pages: 'pages.schema.json',
  permissions: 'permissions.schema.json',
  roles: 'roles.schema.json',
  menus: 'menus.schema.json',
  dicts: 'dicts.schema.json',
  bindings: 'bindings.schema.json',
};

/**
 * Layer 1: Structural validation.
 * - plugin.json schema validation
 * - All resource files exist and parse
 * - Resource files match their schemas
 */
export function validateStructural(plugin: PluginFiles): ValidationResult {
  const result = createResult();
  const ajv = new Ajv({ allErrors: true, strict: false });

  // Pre-load DSL schema for $ref resolution from plugin-manifest.schema.json
  const dslSchemaPath = join(SCHEMA_DIR, 'dsl-schema.generated.json');
  if (existsSync(dslSchemaPath)) {
    try {
      const dslSchema = JSON.parse(readFileSync(dslSchemaPath, 'utf-8'));
      ajv.addSchema(dslSchema, 'dsl-schema.generated.json');
    } catch {
      // Graceful degradation: dslSchema validation skipped if schema unavailable
    }
  }

  // 1. Validate plugin.json against schema
  const manifestSchemaPath = join(SCHEMA_DIR, 'plugin-manifest.schema.json');
  if (existsSync(manifestSchemaPath)) {
    try {
      const schema = JSON.parse(readFileSync(manifestSchemaPath, 'utf-8'));
      const validate = ajv.compile(schema);
      const manifestRaw = JSON.parse(readFileSync(join(plugin.dir, 'plugin.json'), 'utf-8'));
      if (!validate(manifestRaw)) {
        for (const err of validate.errors || []) {
          addMessage(result, {
            code: 'L1-MANIFEST',
            category: 'structural',
            severity: 'error',
            message: `plugin.json${err.instancePath}: ${err.message}`,
            path: `plugin.json${err.instancePath}`,
          });
        }
      }
    } catch (e) {
      addMessage(result, {
        code: 'L1-MANIFEST-PARSE',
        category: 'structural',
        severity: 'error',
        message: `Failed to validate plugin.json: ${(e as Error).message}`,
      });
    }
  }

  // 2. Validate required fields
  if (!plugin.manifest.pluginId) {
    addMessage(result, {
      code: 'L1-REQUIRED',
      category: 'structural',
      severity: 'error',
      message: 'plugin.json missing required field: pluginId',
    });
  }
  if (!plugin.manifest.namespace) {
    addMessage(result, {
      code: 'L1-REQUIRED',
      category: 'structural',
      severity: 'error',
      message: 'plugin.json missing required field: namespace',
    });
  }
  if (!plugin.manifest.version) {
    addMessage(result, {
      code: 'L1-REQUIRED',
      category: 'structural',
      severity: 'error',
      message: 'plugin.json missing required field: version',
    });
  }

  // 3. Validate resource files against their schemas
  for (const [resourceType, resources] of plugin.resourceFiles) {
    const schemaFile = RESOURCE_SCHEMAS[resourceType];
    if (!schemaFile) continue;

    const schemaPath = join(SCHEMA_DIR, schemaFile);
    if (!existsSync(schemaPath)) continue;

    try {
      const schema = JSON.parse(readFileSync(schemaPath, 'utf-8'));
      const validate = ajv.compile(schema);

      // Resource files are arrays wrapped
      if (!validate(resources)) {
        for (const err of validate.errors || []) {
          addMessage(result, {
            code: 'L1-RESOURCE',
            category: 'structural',
            severity: 'error',
            message: `${resourceType}.json${err.instancePath}: ${err.message}`,
            path: `config/${resourceType}.json${err.instancePath}`,
          });
        }
      }
    } catch (e) {
      addMessage(result, {
        code: 'L1-RESOURCE-SCHEMA',
        category: 'structural',
        severity: 'warning',
        message: `Could not validate ${resourceType}: ${(e as Error).message}`,
      });
    }
  }

  // 4. Check JSON syntax of all files (already done by plugin-loader, but count files)
  let fileCount = 1; // plugin.json
  for (const [, resources] of plugin.resourceFiles) {
    fileCount += resources.length > 0 ? 1 : 0;
  }

  if (result.errorCount === 0) {
    addMessage(result, {
      code: 'L1-OK',
      category: 'structural',
      severity: 'info',
      message: `JSON syntax valid (${fileCount} files)`,
    });
  }

  return result;
}
