/**
 * TraceGraphBlockRenderer — DSL blockType "trace-graph".
 *
 * Reads flat trace rows from a dataSource (namedQuery or api), maps them to
 * { nodes, edges } client-side via buildTraceGraph(), and renders a
 * TraceGraphCanvas.
 *
 * Block config shape:
 * {
 *   "id": "...",
 *   "blockType": "trace-graph",
 *   "dataSource": "<dataSourceId>",
 *   "mode": "consumption" | "genealogy"   // inferred from row fields if omitted
 * }
 *
 * Two supported modes
 * -------------------
 * consumption  (pe_consumption_trace_by_lot)
 *   rows: { work_order_id, work_order_code, work_order_name?,
 *            material_id?, lot_id, lot_code, qty_consumed?, consumed_at? }
 *   graph: WORK_ORDER node → LOT node, edge labeled "consumes <qty>"
 *
 * genealogy  (pe_genealogy_trace_by_finished_sn)
 *   rows: { finished_sn, component_sn,
 *            component_material_id?, component_material_name? }
 *   graph: SN node (finished_sn) → COMPONENT node (component_sn),
 *          edge labeled "contains"
 */

import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import {
  readDataSourceRows,
  readDataSourceState,
  useDataSourceSubscription,
} from './workbenchBlockUtils';
import { TraceGraphCanvas, type TraceNode, type TraceEdge } from '~/components/trace/TraceGraphCanvas';

// ---------------------------------------------------------------------------
// Row shapes
// ---------------------------------------------------------------------------

export interface ConsumptionTraceRow {
  work_order_id?: string | null;
  work_order_code?: string | null;
  work_order_name?: string | null;
  material_id?: string | null;
  lot_id?: string | null;
  lot_code?: string | null;
  qty_consumed?: number | string | null;
  consumed_at?: string | null;
  [key: string]: unknown;
}

export interface GenealogyTraceRow {
  finished_sn?: string | null;
  component_sn?: string | null;
  component_material_id?: string | null;
  component_material_name?: string | null;
  [key: string]: unknown;
}

export type TraceMode = 'consumption' | 'genealogy';

export interface TraceGraph {
  nodes: TraceNode[];
  edges: TraceEdge[];
}

// ---------------------------------------------------------------------------
// Pure mapping function (exported for unit testing)
// ---------------------------------------------------------------------------

function inferMode(rows: unknown[]): TraceMode {
  if (rows.length === 0) return 'consumption';
  const first = rows[0] as Record<string, unknown>;
  if ('finished_sn' in first || 'component_sn' in first) return 'genealogy';
  return 'consumption';
}

/**
 * buildTraceGraph — pure function that maps flat trace rows to a graph.
 *
 * @param rows   Array of flat row objects from the namedQuery result.
 * @param mode   'consumption' | 'genealogy'. If omitted the mode is inferred
 *               from the shape of the first row.
 */
export function buildTraceGraph(
  rows: unknown[],
  mode?: TraceMode,
): TraceGraph {
  const resolvedMode: TraceMode = mode ?? inferMode(rows);

  const nodeMap = new Map<string, TraceNode>();
  const edges: TraceEdge[] = [];

  if (resolvedMode === 'consumption') {
    for (const raw of rows) {
      const row = raw as ConsumptionTraceRow;
      const workOrderId = row.work_order_id;
      const lotId = row.lot_id;

      // Skip rows with null ids — cannot form graph connections
      if (!workOrderId || !lotId) continue;

      if (!nodeMap.has(workOrderId)) {
        nodeMap.set(workOrderId, {
          id: workOrderId,
          label: row.work_order_code ?? row.work_order_name ?? workOrderId,
          nodeType: 'WORK_ORDER',
        });
      }

      if (!nodeMap.has(lotId)) {
        nodeMap.set(lotId, {
          id: lotId,
          label: row.lot_code ?? lotId,
          nodeType: 'LOT',
        });
      }

      const edgeId = `e-${workOrderId}-${lotId}`;
      const qty =
        row.qty_consumed !== undefined && row.qty_consumed !== null
          ? `consumes ${row.qty_consumed}`
          : 'consumes';
      edges.push({
        id: edgeId,
        source: workOrderId,
        target: lotId,
        label: qty,
      });
    }
  } else {
    // genealogy mode
    for (const raw of rows) {
      const row = raw as GenealogyTraceRow;
      const finishedSn = row.finished_sn;
      const componentSn = row.component_sn;

      if (!finishedSn || !componentSn) continue;

      if (!nodeMap.has(finishedSn)) {
        nodeMap.set(finishedSn, {
          id: finishedSn,
          label: finishedSn,
          nodeType: 'SN',
        });
      }

      if (!nodeMap.has(componentSn)) {
        nodeMap.set(componentSn, {
          id: componentSn,
          label: row.component_material_name
            ? `${componentSn} (${row.component_material_name})`
            : componentSn,
          nodeType: 'COMPONENT',
        });
      }

      const edgeId = `e-${finishedSn}-${componentSn}`;
      edges.push({
        id: edgeId,
        source: finishedSn,
        target: componentSn,
        label: 'contains',
      });
    }
  }

  return { nodes: Array.from(nodeMap.values()), edges };
}

// ---------------------------------------------------------------------------
// Block renderer
// ---------------------------------------------------------------------------

export interface TraceGraphBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const TraceGraphBlockRenderer: React.FC<TraceGraphBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const t = context.t || ((key: string) => key);
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  const mode = (block as any).mode as TraceMode | undefined;

  useDataSourceSubscription(runtime, dataSourceId);

  const dataSourceState = readDataSourceState(runtime, dataSourceId);
  const rows = readDataSourceRows(runtime, dataSourceId);

  // --- Loading ---
  if (dataSourceState?.loading && !dataSourceState.data) {
    return (
      <div
        data-testid="trace-graph-loading"
        className="flex min-h-[420px] items-center justify-center rounded border border-gray-200 bg-white text-sm text-gray-500"
      >
        {t('common.loading') !== 'common.loading' ? t('common.loading') : 'Loading…'}
      </div>
    );
  }

  // --- Error ---
  if (dataSourceState?.error) {
    const message =
      dataSourceState.error instanceof Error
        ? dataSourceState.error.message
        : String(dataSourceState.error);
    return (
      <div
        role="alert"
        data-testid="trace-graph-error"
        className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700"
      >
        {message || 'Failed to load trace data'}
      </div>
    );
  }

  // --- Empty ---
  if (rows.length === 0) {
    return (
      <div
        data-testid="trace-graph-empty"
        className="flex min-h-[420px] items-center justify-center rounded border border-gray-200 bg-white text-sm text-gray-500"
      >
        {t('common.noData') !== 'common.noData' ? t('common.noData') : 'No trace data'}
      </div>
    );
  }

  const { nodes, edges } = buildTraceGraph(rows, mode);

  return (
    <section data-testid={`trace-graph-block-${block.id ?? 'block'}`}>
      <TraceGraphCanvas nodes={nodes} edges={edges} />
    </section>
  );
};

export default TraceGraphBlockRenderer;
