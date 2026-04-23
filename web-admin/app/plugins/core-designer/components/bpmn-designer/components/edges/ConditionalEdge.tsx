/**
 * Custom edge that renders condition expression and label on the flow line.
 */

import { memo } from 'react';
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, type EdgeProps } from '@xyflow/react';
import type { BPMNEdgeData } from '~/plugins/core-designer/components/bpmn-designer/types';

export function formatConditionForDisplay(condition: string | undefined): string | null {
  if (!condition) return null;

  const normalized = condition.replace(/\s+/g, '');
  if (normalized === '${true}' || normalized === 'true' || normalized === '${$true}') {
    return null;
  }

  return condition;
}

export const ConditionalEdge = memo(
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

    const edgeData = data as BPMNEdgeData | undefined;
    const label = edgeData?.label;
    const condition = formatConditionForDisplay(edgeData?.condition?.content);
    const isDefault = edgeData?.isDefault;

    // Build display text: label first, then condition in smaller font
    const hasContent = label || condition || isDefault;

    return (
      <>
        <BaseEdge
          id={id}
          path={edgePath}
          markerEnd={markerEnd}
          style={{
            stroke: selected ? '#3b82f6' : isDefault ? '#6b7280' : '#94a3b8',
            strokeWidth: selected ? 2 : 1.5,
            strokeDasharray: isDefault ? '5 3' : undefined,
          }}
        />
        {hasContent && (
          <EdgeLabelRenderer>
            <div
              className="nodrag nopan pointer-events-auto"
              style={{
                position: 'absolute',
                transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              }}
            >
              <div
                className={`max-w-[140px] rounded px-1.5 py-0.5 text-center ${
                  selected
                    ? 'border border-blue-200 bg-blue-50'
                    : 'border border-gray-200 bg-white/90'
                }`}
              >
                {label && (
                  <div className="truncate text-[10px] font-medium text-gray-700">{label}</div>
                )}
                {condition && (
                  <div className="truncate font-mono text-[9px] text-gray-400" title={condition}>
                    {condition.length > 25 ? condition.slice(0, 25) + '...' : condition}
                  </div>
                )}
                {isDefault && !label && !condition && (
                  <div className="text-[9px] text-gray-400 italic">default</div>
                )}
              </div>
            </div>
          </EdgeLabelRenderer>
        )}
      </>
    );
  },
);

ConditionalEdge.displayName = 'ConditionalEdge';
