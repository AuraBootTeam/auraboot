import React from 'react';
import type { BlockConfig } from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import { FieldCellRuntime } from './FieldCellRuntime';

interface BlockRegionRuntimeProps {
  block: BlockConfig;
  data?: Record<string, any>;
}

/**
 * Block Region Runtime - renders a block with its field grid in runtime mode.
 */
export const BlockRegionRuntime: React.FC<BlockRegionRuntimeProps> = ({ block, data }) => {
  const gridStyle: React.CSSProperties =
    block.layout.type === 'grid'
      ? {
          display: 'grid',
          gridTemplateColumns: `repeat(${block.layout.columns || 2}, 1fr)`,
          gap: `${block.layout.gap || 16}px`,
        }
      : {
          display: 'flex',
          flexDirection: block.layout.direction || 'row',
          gap: `${block.layout.gap || 16}px`,
          flexWrap: 'wrap',
        };

  if (block.fields.length === 0) return null;

  return (
    <div className="rounded-lg">
      {block.title && <h5 className="mb-2 text-sm font-medium text-gray-600">{block.title}</h5>}
      <div style={gridStyle}>
        {block.fields.map((field) => (
          <FieldCellRuntime key={field.id} field={field} data={data} />
        ))}
      </div>
    </div>
  );
};
