/**
 * BpmDiagramNode - shared xyflow node used by {@link BpmDiagramSection}.
 *
 * Unlike the `core-designer` BPMN node family this component has no
 * dependency on the `useBPMNStore` singleton: the highlight tier is
 * pre-computed by the parent and threaded in via `data.highlight`. The
 * component stays intentionally minimal - a small box showing the node
 * label plus a tier-specific border - because the panel's purpose is "where
 * am I in the process" at-a-glance, not the full designer canvas.
 *
 * Keeping the renderer outside of `core-designer` also keeps Task 12's
 * contract that we do not mutate anything in the designer package.
 *
 * @since BPM closure spec 1 (Task 12)
 */
import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { BPMNNode } from '~/plugins/core-designer/components/bpmn-designer/types';

type HighlightTier = 'current' | 'completed' | 'idle';

const HIGHLIGHT_CLASSES: Record<HighlightTier, string> = {
  current: 'border-blue-500 bg-blue-50 text-blue-900',
  completed: 'border-green-500 bg-green-50 text-green-900',
  idle: 'border-gray-300 bg-white text-gray-600',
};

export const BpmDiagramNode = memo(({ id, data }: NodeProps<BPMNNode>) => {
  // `highlight` is injected by BpmDiagramSection when it walks the
  // definition. Anything unexpected falls back to `idle` so we never crash
  // the canvas for missing data.
  const rawHighlight = (data as { highlight?: string }).highlight;
  const highlight: HighlightTier =
    rawHighlight === 'current' || rawHighlight === 'completed' ? rawHighlight : 'idle';
  const classes = HIGHLIGHT_CLASSES[highlight];

  return (
    <div
      data-testid={`bpm-diagram-node-${id}`}
      data-highlight={highlight}
      className={`flex min-w-24 items-center justify-center rounded border-2 px-3 py-2 text-xs font-medium ${classes}`}
    >
      <Handle type="target" position={Position.Left} className="!h-2 !w-2 !bg-gray-400" />
      <span className="truncate">{data.label}</span>
      <Handle type="source" position={Position.Right} className="!h-2 !w-2 !bg-gray-400" />
    </div>
  );
});

BpmDiagramNode.displayName = 'BpmDiagramNode';
