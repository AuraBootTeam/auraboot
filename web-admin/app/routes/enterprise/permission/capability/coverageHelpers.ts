import type { CapabilityGroup } from './types';

/**
 * "Source" of an atomic permission code in the advanced (escape-hatch) atomic-actions view.
 *
 * A code is *covered* when a DECLARED (business-language) capability includes it — editing it should
 * happen through that capability. A code reachable only through a convention-derived (fallback)
 * capability, or through none at all, is *uncovered* — an "exception" grant configured directly in
 * the advanced table (counts toward the role's exception tally for audit).
 */
export interface CodeSource {
  /** True when a declared capability includes this code. */
  covered: boolean;
  /** Label of the declared capability that covers this code (first match in render order). */
  capabilityLabel?: string;
  capabilityCode?: string;
}

/**
 * Build a `code -> CodeSource` map from a role's capability view. Only declared capabilities count
 * as coverage; convention-derived fallbacks are ignored so codes only they reach read as exceptions.
 */
export function deriveCodeSources(groups: CapabilityGroup[]): Record<string, CodeSource> {
  const map: Record<string, CodeSource> = {};
  for (const group of groups) {
    for (const cap of group.capabilities) {
      if (cap.conventionDerived) continue;
      for (const code of cap.includes ?? []) {
        // First declared capability in render order wins — keep it stable.
        if (!map[code]) {
          map[code] = { covered: true, capabilityLabel: cap.label, capabilityCode: cap.code };
        }
      }
    }
  }
  return map;
}

/** Look up a code's source, defaulting to an uncovered (exception) source when absent. */
export function sourceFor(map: Record<string, CodeSource>, code: string): CodeSource {
  return map[code] ?? { covered: false };
}

/** Count of currently-granted codes that are exceptions (uncovered by any declared capability). */
export function exceptionCount(map: Record<string, CodeSource>, grantedCodes: Iterable<string>): number {
  let n = 0;
  for (const code of grantedCodes) {
    if (!map[code]?.covered) n++;
  }
  return n;
}
