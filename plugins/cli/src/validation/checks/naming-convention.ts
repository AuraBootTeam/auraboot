import { ResourceIndex } from '../../utils/resource-index.js';
import { DiagnosticMessage } from './menu-route.js';

/**
 * NS_CONSISTENCY: Resource codes follow a consistent prefix convention.
 * Detects the dominant prefix from models and checks consistency across resources.
 */
export function checkNsConsistency(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];
  const ns = idx.namespace;

  // Detect dominant model prefix (could be namespace_ or a shortened version)
  const modelPrefixes = new Map<string, number>();
  for (const m of idx.raw.models) {
    const prefix = m.code.split('_').slice(0, 1).join('_');
    modelPrefixes.set(prefix, (modelPrefixes.get(prefix) || 0) + 1);
  }
  // Find the most common prefix
  let dominantPrefix = ns;
  let maxCount = 0;
  for (const [prefix, count] of modelPrefixes) {
    if (count > maxCount) { dominantPrefix = prefix; maxCount = count; }
  }

  // Accept both namespace and dominant prefix
  const validModelPrefixes = new Set([`${ns}_`, `${dominantPrefix}_`]);
  const validCmdPrefixes = new Set([`${ns}:`, `${dominantPrefix}:`]);

  // Models: check consistency among themselves
  for (const m of idx.raw.models) {
    const hasValidPrefix = Array.from(validModelPrefixes).some(p => m.code.startsWith(p));
    if (!hasValidPrefix) {
      messages.push({
        code: 'ns_consistency',
        severity: 'warning',
        message: `Model '${m.code}' doesn't follow prefix convention '${dominantPrefix}_'`,
        path: `models.json#${m.code}`,
      });
    }
  }

  // Commands: check consistency
  for (const c of idx.raw.commands) {
    const hasValidPrefix = Array.from(validCmdPrefixes).some(p => c.code.startsWith(p));
    if (!hasValidPrefix) {
      messages.push({
        code: 'ns_consistency',
        severity: 'warning',
        message: `Command '${c.code}' doesn't follow prefix convention '${dominantPrefix}:'`,
        path: `commands.json#${c.code}`,
      });
    }
  }

  // Fields: check consistency (use dominant model prefix)
  for (const f of idx.raw.fields) {
    const hasValidPrefix = Array.from(validModelPrefixes).some(p => f.code.startsWith(p));
    if (!hasValidPrefix) {
      messages.push({
        code: 'ns_consistency',
        severity: 'warning',
        message: `Field '${f.code}' doesn't follow prefix convention '${dominantPrefix}_'`,
        path: `fields.json#${f.code}`,
      });
    }
  }

  return messages;
}
