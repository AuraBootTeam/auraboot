import type { PluginFiles } from '../utils/plugin-loader.js';
import { type ValidationResult, createResult, addMessage } from './types.js';

/**
 * Layer 3: Governance validation.
 * - i18n key coverage
 * - Circular dependency detection
 * - Permission declaration completeness
 */
export function validateGovernance(plugin: PluginFiles): ValidationResult {
  const result = createResult();

  checkI18nCoverage(plugin, result);
  checkCircularDependencies(plugin, result);
  checkPermissionCompleteness(plugin, result);

  return result;
}

function checkI18nCoverage(plugin: PluginFiles, result: ValidationResult): void {
  const i18nResources = plugin.resourceFiles.get('i18n') || [];
  const coveredKeys = new Set<string>();

  for (const entry of i18nResources) {
    if (entry.key) {
      // Check if it has at least one translation
      if (entry['zh-CN'] || entry['en-US'] || entry['en']) {
        coveredKeys.add(entry.key);
      }
    }
  }

  const models = plugin.resourceFiles.get('models') || [];
  const bindings = plugin.resourceFiles.get('bindings') || [];
  let totalExpected = 0;
  let totalCovered = 0;

  // Check model label keys
  for (const model of models) {
    if (!model.code) continue;
    totalExpected++;
    const key = `model.${model.code}._meta.label`;
    if (coveredKeys.has(key)) {
      totalCovered++;
    }
  }

  // Check field label keys
  for (const binding of bindings) {
    if (!binding.modelCode || !binding.fieldCode) continue;
    totalExpected++;
    const key = `model.${binding.modelCode}.${binding.fieldCode}.label`;
    if (coveredKeys.has(key)) {
      totalCovered++;
    }
  }

  if (totalExpected > 0) {
    const coverage = Math.round((totalCovered / totalExpected) * 100);
    const missing = totalExpected - totalCovered;
    if (missing > 0) {
      addMessage(result, {
        code: 'G-I18N',
        category: 'governance',
        severity: 'info',
        message: `i18n coverage: ${coverage}% (missing ${missing} label key${missing > 1 ? 's' : ''})`,
      });
    }
  }
}

function checkCircularDependencies(plugin: PluginFiles, result: ValidationResult): void {
  const deps = plugin.manifest.dependencies;
  if (!deps || deps.length === 0) return;

  // Build local dependency graph (single-node for now — full graph needs remote data)
  const depIds = deps.map((d) => (typeof d === 'string' ? d : d.pluginId));

  // Self-dependency check
  if (depIds.includes(plugin.manifest.pluginId)) {
    addMessage(result, {
      code: 'G-CYCLE',
      category: 'governance',
      severity: 'error',
      message: `Plugin '${plugin.manifest.pluginId}' depends on itself`,
    });
  }
}

function checkPermissionCompleteness(plugin: PluginFiles, result: ValidationResult): void {
  const permissions = plugin.resourceFiles.get('permissions') || [];
  const menus = plugin.resourceFiles.get('menus') || [];

  // Check that menus reference declared permissions
  const permCodes = new Set(permissions.map((p: any) => p.code).filter(Boolean));

  for (const menu of menus) {
    if (menu.permissionCode && !permCodes.has(menu.permissionCode)) {
      addMessage(result, {
        code: 'G-PERM-MENU',
        category: 'governance',
        severity: 'warning',
        message: `Menu '${menu.code || menu.name}' references undeclared permission '${menu.permissionCode}'`,
      });
    }
  }
}
