/**
 * Minimal BPMN-style conditional edge for the PoC. Renders an inline label
 * showing the user-visible name and (when set) the condition expression.
 *
 * Mirrors the shape of bpmn-designer/components/edges/ConditionalEdge.tsx but
 * is sized to fit through the SDK's EdgeRegistry (G1) without any private
 * BPMN-only types.
 */

import { memo } from 'react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  type EdgeProps,
} from '@xyflow/react';

interface BpmEdgeData {
  label?: string;
  condition?: { type: 'expression' | 'script'; content: string };
  isDefault?: boolean;
}

export const BpmConditionalEdge = memo(
  ({
    id,
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    data,
    selected,
    markerEnd,
  }: EdgeProps) => {
    const [edgePath, labelX, labelY] = getSmoothStepPath({
      sourceX,
      sourceY,
      sourcePosition,
      targetX,
      targetY,
      targetPosition,
      borderRadius: 16,
    });
    const d = (data as BpmEdgeData | undefined) ?? {};
    const condition = d.condition?.content?.trim();
    const hasLabel = !!(d.label || condition || d.isDefault);

    return (
      <>
        <BaseEdge
          id={id}
          path={edgePath}
          markerEnd={markerEnd}
          style={{
            stroke: selected ? '#3b82f6' : d.isDefault ? '#6b7280' : '#94a3b8',
            strokeWidth: selected ? 2 : 1.5,
            strokeDasharray: d.isDefault ? '5 3' : undefined,
          }}
        />
        {hasLabel && (
          <EdgeLabelRenderer>
            <div
              data-testid={`bpm-smoke-edge-label-${id}`}
              className="nodrag nopan pointer-events-auto"
              style={{
                position: 'absolute',
                transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              }}
            >
              <div className="max-w-[160px] rounded border border-gray-200 bg-white/90 px-1.5 py-0.5 text-center">
                {d.label && (
                  <div className="truncate text-[10px] font-medium text-gray-700">
                    {d.label}
                  </div>
                )}
                {condition && (
                  <div className="truncate font-mono text-[9px] text-gray-400" title={condition}>
                    {condition.length > 30 ? condition.slice(0, 30) + '…' : condition}
                  </div>
                )}
                {d.isDefault && !d.label && !condition && (
                  <div className="text-[9px] italic text-gray-400">default</div>
                )}
              </div>
            </div>
          </EdgeLabelRenderer>
        )}
      </>
    );
  },
);
BpmConditionalEdge.displayName = 'BpmConditionalEdge';
