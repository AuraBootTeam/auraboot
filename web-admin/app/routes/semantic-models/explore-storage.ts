/**
 * Saved explorations for the semantic console (BI rectification R4).
 *
 * A saved exploration is the full pick state of one model's ad-hoc query —
 * metrics, dimensions, time-grain choices, and the TopN limit — persisted in
 * localStorage so an analyst can resume where they left off. Plain functions
 * (no React) so the storage contract is unit-testable in isolation.
 */

export interface SavedExploration {
  modelCode: string;
  metrics: string[];
  dimensions: string[];
  /** dimension code → grain suffix (day/week/month/quarter/year). */
  grains: Record<string, string>;
  limit: number;
}

const KEY_PREFIX = 'semantic_explore:';

export function saveExploration(saved: SavedExploration): void {
  try {
    localStorage.setItem(KEY_PREFIX + saved.modelCode, JSON.stringify(saved));
  } catch {
    // localStorage unavailable (private mode/quota) — saving is best-effort.
  }
}

export function loadExploration(modelCode: string): SavedExploration | null {
  try {
    const raw = localStorage.getItem(KEY_PREFIX + modelCode);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as SavedExploration;
    if (parsed.modelCode !== modelCode || !Array.isArray(parsed.metrics)) return null;
    return {
      modelCode,
      metrics: parsed.metrics,
      dimensions: Array.isArray(parsed.dimensions) ? parsed.dimensions : [],
      grains: parsed.grains && typeof parsed.grains === 'object' ? parsed.grains : {},
      limit: typeof parsed.limit === 'number' && parsed.limit > 0 ? parsed.limit : 100,
    };
  } catch {
    return null;
  }
}

export function clearExploration(modelCode: string): void {
  try {
    localStorage.removeItem(KEY_PREFIX + modelCode);
  } catch {
    // best-effort
  }
}

/**
 * Qualify a dimension for the compiler: `model.code` plus the `__grain`
 * suffix the semantic compiler understands for time dimensions
 * (e.g. `order.order_date__month`).
 */
export function qualifiedDimension(modelCode: string, dimCode: string, grain?: string): string {
  return `${modelCode}.${dimCode}${grain ? '__' + grain : ''}`;
}
