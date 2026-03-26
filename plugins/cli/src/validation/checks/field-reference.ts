import { ResourceIndex } from '../../utils/resource-index.js';
import { DiagnosticMessage } from './menu-route.js';

/**
 * REF_FIELD_TARGET: REFERENCE field target model exists.
 */
export function checkRefFieldTarget(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const f of idx.raw.fields) {
    if (f.dataType === 'reference' && f.extension?.referenceModel) {
      if (!idx.models.has(f.extension.referenceModel)) {
        messages.push({
          code: 'ref_field_target',
          severity: 'error',
          message: `REFERENCE field '${f.code}' targets non-existent model '${f.extension.referenceModel}'`,
          path: `fields.json#${f.code}`,
          suggestion: `Add model '${f.extension.referenceModel}' or fix the referenceModel value`,
        });
      }
    }
  }

  return messages;
}

/**
 * CMD_MODEL_EXISTS: Command modelCode exists.
 */
export function checkCmdModelExists(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const c of idx.raw.commands) {
    if (c.modelCode && !idx.models.has(c.modelCode)) {
      messages.push({
        code: 'cmd_model_exists',
        severity: 'error',
        message: `Command '${c.code}' references non-existent model '${c.modelCode}'`,
        path: `commands.json#${c.code}`,
      });
    }
  }

  return messages;
}

/**
 * CMD_INPUT_FIELDS: Command inputFields reference bound fields.
 */
export function checkCmdInputFields(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const c of idx.raw.commands) {
    if (!c.inputFields || !c.modelCode) continue;
    const modelBindings = idx.bindingsByModel.get(c.modelCode) || [];
    const boundFieldCodes = new Set(modelBindings.map((b: any) => b.fieldCode));

    for (const fc of c.inputFields) {
      if (!boundFieldCodes.has(fc)) {
        messages.push({
          code: 'cmd_input_fields',
          severity: 'warning',
          message: `Command '${c.code}' inputField '${fc}' is not bound to model '${c.modelCode}'`,
          path: `commands.json#${c.code}`,
          suggestion: `Add binding for '${fc}' in bindings.json or remove from inputFields`,
        });
      }
    }
  }

  return messages;
}

/**
 * BINDING_COMPLETE: Fields are bound to at least one model.
 */
export function checkBindingComplete(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const f of idx.raw.fields) {
    const models = idx.bindingsByField.get(f.code) || [];
    if (models.length === 0) {
      messages.push({
        code: 'binding_complete',
        severity: 'warning',
        message: `Field '${f.code}' is not bound to any model`,
        path: `fields.json#${f.code}`,
        suggestion: `Add a binding in bindings.json or remove the field`,
      });
    }
  }

  return messages;
}

/**
 * PAGE_MODEL_EXISTS: Page modelCode exists.
 */
export function checkPageModelExists(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const p of idx.raw.pages) {
    const mc = p.modelCode || p.dslSchema?.modelCode;
    if (mc && !idx.models.has(mc)) {
      messages.push({
        code: 'page_model_exists',
        severity: 'warning',
        message: `Page '${p.pageKey}' references non-existent model '${mc}'`,
        path: `pages.json#${p.pageKey}`,
      });
    }
  }

  return messages;
}

/**
 * PAGE_KEY_CONVENTION: pageKey follows {code}_{type} convention.
 */
export function checkPageKeyConvention(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];
  const validSuffixes = ['_list', '_form', '_detail', '_dashboard', '_kanban', '_gantt', '_calendar', '_gallery'];

  for (const p of idx.raw.pages) {
    const hasValidSuffix = validSuffixes.some(s => p.pageKey.endsWith(s));
    if (!hasValidSuffix) {
      messages.push({
        code: 'page_key_convention',
        severity: 'warning',
        message: `Page key '${p.pageKey}' doesn't follow {modelCode}_{type} convention`,
        path: `pages.json#${p.pageKey}`,
        suggestion: `Recommended suffixes: ${validSuffixes.join(', ')}`,
      });
    }
  }

  return messages;
}

/**
 * DICT_REFERENCED: Dict is referenced by at least one field.
 */
export function checkDictReferenced(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const d of idx.raw.dicts) {
    const usedBy = idx.raw.fields.filter((f: any) =>
      f.extension?.dictCode === d.code || f.dictCode === d.code,
    );
    if (usedBy.length === 0) {
      messages.push({
        code: 'dict_referenced',
        severity: 'info',
        message: `Dict '${d.code}' is not referenced by any field`,
        path: `dicts.json#${d.code}`,
      });
    }
  }

  return messages;
}
