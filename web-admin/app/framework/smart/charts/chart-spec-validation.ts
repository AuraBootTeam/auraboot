/**
 * ChartSpec → render-target compatibility validation (B2a).
 *
 * Backlog B2c "Print compatibility validation": when a ChartSpec cannot be safely
 * degraded onto a target (interactive-only feature required / unsupported type /
 * unbounded dataset with no aggregation), surface a design-time ERROR or required
 * FALLBACK instead of silently exporting a wrong chart.
 *
 * This is pure + renderer-agnostic: it reasons only about ChartSpec + the target's
 * declared CAPABILITY_MATRIX, never about echarts/svg internals.
 */

import type { ChartSpec } from './chart-spec';
import { type ChartRenderTarget, type ChartRenderTargetId, getRenderTarget } from './chart-spec';

export type ChartFallback = 'table' | 'image-snapshot' | 'aggregation';

export interface ChartSpecValidationError {
  code:
    | 'UNSUPPORTED_TYPE'
    | 'DRILLDOWN_REQUIRED_UNSUPPORTED'
    | 'UNBOUNDED_DATASET';
  message: string;
  /** Suggested fallback the author can choose to make it valid. */
  fallback?: ChartFallback;
}

export interface ChartSpecDegradation {
  capability: 'tooltip' | 'drilldown' | 'animation' | 'theme' | 'linkage' | 'largeDataset';
  message: string;
}

export interface ChartSpecValidationResult {
  ok: boolean;
  target: ChartRenderTargetId;
  errors: ChartSpecValidationError[];
  degradations: ChartSpecDegradation[];
}

/** Heuristic: a spec plots a potentially unbounded set when no measure aggregates
 * and the data source sets no limit. On a print target this needs sampling/table. */
function isUnbounded(spec: ChartSpec): boolean {
  const anyAggregation = spec.measures.some((m) => !!m.aggregation);
  const ds = spec.dataSource as { limit?: number; type?: string };
  const hasLimit = typeof ds.limit === 'number' && ds.limit > 0;
  // static data is inherently bounded
  if (ds.type === 'static') return false;
  return !anyAggregation && !hasLimit;
}

function typeSupported(target: ChartRenderTarget, type: ChartSpec['type']): boolean {
  return target.supportedTypes === '*' || target.supportedTypes.includes(type);
}

/**
 * Validate a ChartSpec against a render target. `errors` block export (the author
 * must pick a fallback or change the spec); `degradations` are non-blocking losses
 * the author should be aware of.
 */
export function validateChartSpecForTarget(
  spec: ChartSpec,
  targetId: ChartRenderTargetId,
): ChartSpecValidationResult {
  const target = getRenderTarget(targetId);
  const errors: ChartSpecValidationError[] = [];
  const degradations: ChartSpecDegradation[] = [];

  // 1. Type support
  if (!typeSupported(target, spec.type)) {
    errors.push({
      code: 'UNSUPPORTED_TYPE',
      message: `Chart type "${spec.type}" is not supported by the ${target.label} target.`,
      fallback: 'image-snapshot',
    });
  }

  const cap = target.capabilities;

  // 2. Drilldown
  if (spec.drilldown?.enabled) {
    if (cap.drilldown === 'unsupported') {
      errors.push({
        code: 'DRILLDOWN_REQUIRED_UNSUPPORTED',
        message: `Drill-down is enabled but the ${target.label} target does not support it.`,
        fallback: 'table',
      });
    } else if (cap.drilldown === 'degrade') {
      degradations.push({
        capability: 'drilldown',
        message: 'Drill-down is rendered as a static footnote/link (not interactive) on this target.',
      });
    }
  }

  // 3. Tooltip
  if (spec.interaction?.tooltip && cap.tooltip !== 'full') {
    degradations.push({
      capability: 'tooltip',
      message: 'Tooltips are not available on this target and will be omitted.',
    });
  }

  // 4. Auto-refresh has no meaning on a non-interactive (print/snapshot) target.
  if (spec.interaction?.refreshIntervalMs && !target.interactive) {
    degradations.push({
      capability: 'animation',
      message: 'Auto-refresh has no effect on a static target; a point-in-time snapshot is rendered.',
    });
  }
  if (spec.interaction?.linkage?.enabled && cap.linkage === 'unsupported') {
    degradations.push({
      capability: 'linkage',
      message: 'Cross-filter linkage is unavailable on this target.',
    });
  }

  // 5. Theme degradation
  if (cap.theme === 'degrade' && spec.visual?.colorTokens?.length) {
    degradations.push({
      capability: 'theme',
      message: 'Theme tokens are flattened to static styles on this target.',
    });
  }

  // 6. Unbounded dataset (no aggregation + no row limit). A non-interactive target
  //    cannot auto-sample, so it must block until the author picks a bound/fallback;
  //    an interactive target degrades by sampling at render time.
  if (isUnbounded(spec) && cap.largeDataset !== 'full') {
    if (!target.interactive) {
      errors.push({
        code: 'UNBOUNDED_DATASET',
        message:
          'Chart has no aggregation and no row limit; a static target cannot render an unbounded dataset — add an aggregation, a row limit, or a table fallback.',
        fallback: 'aggregation',
      });
    } else {
      degradations.push({
        capability: 'largeDataset',
        message:
          'Unbounded dataset will be sampled / paginated; consider an aggregation or row limit for fidelity.',
      });
    }
  }

  return { ok: errors.length === 0, target: targetId, errors, degradations };
}
