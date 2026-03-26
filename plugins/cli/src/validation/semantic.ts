import { readFileSync, existsSync } from 'fs';
import { resolve } from 'path';
import type { PluginFiles } from '../utils/plugin-loader.js';
import { type ValidationResult, createResult, addMessage } from './types.js';
import { getEnumCodes } from '../utils/dsl-registry-loader.js';

const SCHEMA_DIR = resolve(import.meta.dirname, '../../../../schemas');

let VALID_COMMAND_TYPES: Set<string>;
let VALID_AUTO_SET_STRATEGIES: Set<string>;
let VALID_PAGE_KINDS: Set<string>;
let enumsLoaded = false;
let enumLoadError: string | null = null;

function ensureEnums(): void {
  if (enumsLoaded) return;
  try {
    VALID_COMMAND_TYPES = getEnumCodes('CommandType');
    VALID_AUTO_SET_STRATEGIES = getEnumCodes('AutoSetStrategy');
    VALID_PAGE_KINDS = getEnumCodes('PageKind');
    enumsLoaded = true;
  } catch (err) {
    enumLoadError = err instanceof Error ? err.message : String(err);
  }
}

/**
 * Layer 2: Semantic validation.
 * - Cross-reference integrity (commands reference existing models)
 * - Namespace consistency
 * - ExecutionConfig validity
 * - Page DSL structure
 */
export function validateSemantic(plugin: PluginFiles): ValidationResult {
  const result = createResult();

  ensureEnums();
  if (!enumsLoaded) {
    addMessage(result, {
      code: 'S-REGISTRY',
      category: 'semantic',
      severity: 'warning',
      message: `DSL registry unavailable — skipping enum validation: ${enumLoadError}`,
    });
    return result;
  }
  const ns = plugin.manifest.namespace;

  // Collect known codes
  const modelCodes = new Set<string>();
  const fieldCodes = new Set<string>();

  for (const model of plugin.resourceFiles.get('models') || []) {
    if (model.code) modelCodes.add(model.code);
  }
  for (const field of plugin.resourceFiles.get('fields') || []) {
    if (field.code) fieldCodes.add(field.code);
  }

  // S-NS: Namespace consistency
  if (ns) {
    for (const model of plugin.resourceFiles.get('models') || []) {
      if (!model.code) continue;
      // Exempt models with tableName binding
      if (model.extension?.tableName) continue;
      if (!model.code.startsWith(`${ns}_`)) {
        addMessage(result, {
          code: 'S-NS-MODEL',
          category: 'semantic',
          severity: 'warning',
          message: `Model '${model.code}' does not follow namespace prefix '${ns}_'`,
        });
      }
    }

    for (const cmd of plugin.resourceFiles.get('commands') || []) {
      if (!cmd.code) continue;
      if (!cmd.code.startsWith(`${ns}:`)) {
        addMessage(result, {
          code: 'S-NS-COMMAND',
          category: 'semantic',
          severity: 'warning',
          message: `Command '${cmd.code}' does not follow namespace prefix '${ns}:'`,
        });
      }
    }
  }

  // S-REF: Cross-reference checks
  for (const cmd of plugin.resourceFiles.get('commands') || []) {
    if (cmd.modelCode && !modelCodes.has(cmd.modelCode)) {
      addMessage(result, {
        code: 'S-REF-MODEL',
        category: 'semantic',
        severity: 'error',
        message: `Command '${cmd.code}' references unknown model '${cmd.modelCode}'`,
      });
    }
  }

  for (const binding of plugin.resourceFiles.get('bindings') || []) {
    if (binding.modelCode && !modelCodes.has(binding.modelCode)) {
      addMessage(result, {
        code: 'S-REF-BINDING-MODEL',
        category: 'semantic',
        severity: 'error',
        message: `Binding references unknown model '${binding.modelCode}'`,
      });
    }
    if (binding.fieldCode && !fieldCodes.has(binding.fieldCode)) {
      addMessage(result, {
        code: 'S-REF-BINDING-FIELD',
        category: 'semantic',
        severity: 'error',
        message: `Binding for model '${binding.modelCode}' references unknown field '${binding.fieldCode}'`,
      });
    }
  }

  // S-EXEC: Command executionConfig validation
  for (const cmd of plugin.resourceFiles.get('commands') || []) {
    const type = cmd.type || cmd.executionConfig?.type;
    if (type && !VALID_COMMAND_TYPES.has(type)) {
      addMessage(result, {
        code: 'S-EXEC-TYPE',
        category: 'semantic',
        severity: 'error',
        message: `Command '${cmd.code}' has invalid type: '${type}'`,
      });
    }

    if (type === 'state_transition') {
      const stateField = cmd.stateField || cmd.executionConfig?.stateField;
      const toState = cmd.toState || cmd.executionConfig?.toState;
      const rules = cmd.stateTransitionRules || cmd.executionConfig?.stateTransitionRules;
      if (!stateField) {
        addMessage(result, {
          code: 'S-EXEC-ST',
          category: 'semantic',
          severity: 'error',
          message: `Command '${cmd.code}': STATE_TRANSITION requires 'stateField'`,
        });
      }
      if (!toState && (!rules || rules.length === 0)) {
        addMessage(result, {
          code: 'S-EXEC-ST',
          category: 'semantic',
          severity: 'error',
          message: `Command '${cmd.code}': STATE_TRANSITION requires 'toState' or 'stateTransitionRules'`,
        });
      }
    }

    // Check autoSetFields strategies
    const autoSet = cmd.autoSetFields || cmd.executionConfig?.autoSetFields;
    if (autoSet && typeof autoSet === 'object') {
      for (const [fieldCode, spec] of Object.entries(autoSet)) {
        const strategy = (spec as any)?.strategy;
        if (strategy && !VALID_AUTO_SET_STRATEGIES.has(strategy)) {
          addMessage(result, {
            code: 'S-EXEC-AUTOSET',
            category: 'semantic',
            severity: 'warning',
            message: `Command '${cmd.code}' autoSetFields.${fieldCode} uses unknown strategy '${strategy}'`,
          });
        }
      }
    }
  }

  // S-PAGE: Page DSL structure
  for (const page of plugin.resourceFiles.get('pages') || []) {
    const dsl = page.dslSchema || page.dsl_schema;
    if (!dsl) continue;

    const kind = dsl.kind;
    if (!kind) {
      addMessage(result, {
        code: 'S-PAGE-KIND',
        category: 'semantic',
        severity: 'error',
        message: `Page '${page.pageKey || page.page_key}' DSL is missing 'kind'`,
      });
    } else if (!VALID_PAGE_KINDS.has(kind)) {
      addMessage(result, {
        code: 'S-PAGE-KIND',
        category: 'semantic',
        severity: 'warning',
        message: `Page '${page.pageKey || page.page_key}' has unknown kind: '${kind}'`,
      });
    }

    if (!dsl.layout) {
      addMessage(result, {
        code: 'S-PAGE-LAYOUT',
        category: 'semantic',
        severity: 'error',
        message: `Page '${page.pageKey || page.page_key}' DSL is missing 'layout'`,
      });
    }
  }

  // S-DSL-SEMANTIC: Validate x- semantic constraints from generated schema
  validateDslSemanticConstraints(plugin, result);

  // S-PAGE-BLOCKS: Validate block types match page kind recommendations
  validateBlockTypesByPageKind(plugin, result);

  return result;
}

// ---------------------------------------------------------------------------
// S-DSL-SEMANTIC: Validate x- semantic constraint annotations
// ---------------------------------------------------------------------------

/**
 * Walk all blocks in a DSL schema, invoking visitor on each block.
 */
function walkBlocks(dsl: any, visitor: (block: any) => void): void {
  if (!dsl.areas) return;
  for (const area of Object.values(dsl.areas) as any[]) {
    if (!area?.blocks) continue;
    for (const block of area.blocks) {
      visitor(block);
      // Also walk sub-table columns
      if (block.subTable) {
        visitor(block.subTable);
      }
    }
  }
}

/**
 * Collect x-requiresCommand annotations from a schema definition's properties.
 */
function collectRequiresCommand(def: any): Map<string, string> {
  const result = new Map<string, string>();
  if (!def?.properties) return result;
  for (const [propName, propSchema] of Object.entries(def.properties)) {
    const req = (propSchema as any)['x-requiresCommand'];
    if (req) result.set(propName, req);
  }
  return result;
}

/**
 * S-PAGE-BLOCKS: Validate that pages contain block types appropriate for their kind.
 * Reads x-recommended-block-types from the generated schema's pageType discriminator
 * (allOf if/then rules on DslSchema) and warns when no recommended block type is found.
 */
function validateBlockTypesByPageKind(plugin: PluginFiles, result: ValidationResult): void {
  const schemaPath = resolve(SCHEMA_DIR, 'dsl-schema.generated.json');
  if (!existsSync(schemaPath)) return;

  let schema: any;
  try {
    schema = JSON.parse(readFileSync(schemaPath, 'utf-8'));
  } catch {
    return;
  }

  const dslDef = schema.definitions?.DslSchema;
  if (!dslDef?.allOf) return;

  // Build kind → recommended block types map from if/then rules
  const recommendedByKind = new Map<string, Set<string>>();
  for (const rule of dslDef.allOf) {
    const kind = rule.if?.properties?.kind?.const;
    const recommended: string[] | undefined = rule.then?.properties?.areas?.['x-recommended-block-types'];
    if (kind && recommended) {
      recommendedByKind.set(kind, new Set(recommended));
    }
  }

  if (recommendedByKind.size === 0) return;

  // Check each page
  for (const page of plugin.resourceFiles.get('pages') || []) {
    const dsl = page.dslSchema || page.dsl_schema;
    if (!dsl?.kind || !dsl?.areas) continue;

    const recommended = recommendedByKind.get(dsl.kind);
    if (!recommended) continue;

    // Collect actual block types across all areas
    const actualBlockTypes = new Set<string>();
    for (const area of Object.values(dsl.areas) as any[]) {
      if (!area?.blocks) continue;
      for (const block of area.blocks) {
        if (block.blockType) actualBlockTypes.add(block.blockType);
      }
    }

    // Only warn if page has blocks but none match recommended types
    const hasRecommended = [...recommended].some(bt => actualBlockTypes.has(bt));
    if (!hasRecommended && actualBlockTypes.size > 0) {
      const pageKey = page.pageKey || page.page_key;
      addMessage(result, {
        code: 'S-PAGE-BLOCKS',
        category: 'semantic',
        severity: 'warning',
        message: `Page '${pageKey}' (kind=${dsl.kind}) has no recommended block types. Expected one of: ${[...recommended].join(', ')}. Found: ${[...actualBlockTypes].join(', ')}`,
      });
    }
  }
}

function validateDslSemanticConstraints(plugin: PluginFiles, result: ValidationResult): void {
  const schemaPath = resolve(SCHEMA_DIR, 'dsl-schema.generated.json');
  if (!existsSync(schemaPath)) return;

  let schema: any;
  try {
    schema = JSON.parse(readFileSync(schemaPath, 'utf-8'));
  } catch {
    return;
  }

  // Find x-requiresCommand in ColumnConfig and SubTableConfig definitions
  const columnReqs = collectRequiresCommand(schema.definitions?.ColumnConfig);
  const subTableReqs = collectRequiresCommand(schema.definitions?.SubTableConfig);

  if (columnReqs.size === 0 && subTableReqs.size === 0) return;

  // Collect model commands by model code → set of command types
  const commandsByModel = new Map<string, Set<string>>();
  for (const cmd of plugin.resourceFiles.get('commands') || []) {
    if (!cmd.modelCode || !cmd.type) continue;
    if (!commandsByModel.has(cmd.modelCode)) commandsByModel.set(cmd.modelCode, new Set());
    commandsByModel.get(cmd.modelCode)!.add(cmd.type);
  }

  // Check pages
  for (const page of plugin.resourceFiles.get('pages') || []) {
    const dsl = page.dslSchema || page.dsl_schema;
    if (!dsl) continue;
    const pageKey = page.pageKey || page.page_key;
    const modelCode = dsl.modelCode || page.modelCode;
    if (!modelCode) continue;

    walkBlocks(dsl, (block: any) => {
      // Check column-level annotations
      const columns = block.columns;
      if (Array.isArray(columns)) {
        for (const col of columns) {
          for (const [propName, requiredType] of columnReqs) {
            if (col[propName] === true) {
              const modelCmds = commandsByModel.get(modelCode);
              if (!modelCmds || !modelCmds.has(requiredType)) {
                addMessage(result, {
                  code: 'S-DSL-REQUIRES-CMD',
                  category: 'semantic',
                  severity: 'warning',
                  message: `Page '${pageKey}' column '${col.field}' has ${propName}=true but model '${modelCode}' has no '${requiredType}' command`,
                });
              }
            }
          }
        }
      }

      // Check sub-table level annotations
      if (block.childModel) {
        const stModelCode = block.childModel;
        for (const [propName, requiredType] of subTableReqs) {
          if (block[propName] === true) {
            const modelCmds = commandsByModel.get(stModelCode);
            if (!modelCmds || !modelCmds.has(requiredType)) {
              addMessage(result, {
                code: 'S-DSL-REQUIRES-CMD',
                category: 'semantic',
                severity: 'warning',
                message: `Page '${pageKey}' sub-table '${stModelCode}' has ${propName}=true but model '${stModelCode}' has no '${requiredType}' command`,
              });
            }
          }
        }
      }
    });
  }
}
