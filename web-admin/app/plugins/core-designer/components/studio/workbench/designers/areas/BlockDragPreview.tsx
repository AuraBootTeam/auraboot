/**
 * Block Drag Preview
 *
 * Visual preview shown while dragging a block from the library.
 */

import React from 'react';
import type { BlockType } from '~/plugins/core-designer/components/studio/domain/dsl/types';

export interface BlockDragPreviewProps {
  blockType: BlockType;
}

/**
 * Block type display info
 */
const BLOCK_INFO: Record<BlockType, { name: string; icon: string }> = {
  'filters': { name: 'Filter Form', icon: '🔍' },
  'form-section': { name: 'Form Section', icon: '📝' },
  'detail-section': { name: 'Detail Section', icon: '📄' },
  'form-buttons': { name: 'Form Buttons', icon: '✅' },
  'toolbar': { name: 'Toolbar Buttons', icon: '🔘' },
  'selection-info': { name: 'Selection Info', icon: '☑️' },
  'tabs': { name: 'Tabs', icon: 'TAB' },
  'table': { name: 'Data Table', icon: '📊' },
  'stat-card': { name: 'Stat Card', icon: '📈' },
  'metric-strip': { name: 'Metric Strip', icon: 'KPI' },
  'record-inspector': { name: 'Record Inspector', icon: 'ROW' },
  'candidate-list': { name: 'Candidate List', icon: 'ALT' },
  'workbench-action-bar': { name: 'Workbench Action Bar', icon: 'ACT' },
  'evidence-panel': { name: 'Evidence Panel', icon: 'EV' },
  'artifact-timeline': { name: 'Artifact Timeline', icon: 'TL' },
  'review-drawer': { name: 'Review Drawer', icon: 'REV' },
  'chart-card': { name: 'Chart Card', icon: '📉' },
  custom: { name: 'Custom Block', icon: 'EXT' },
  text: { name: 'Text Content', icon: '📃' },
};

export const BlockDragPreview: React.FC<BlockDragPreviewProps> = ({ blockType }) => {
  const info = BLOCK_INFO[blockType] || { name: blockType, icon: '📦' };

  return (
    <div
      className="flex items-center gap-3 rounded-lg border-2 border-blue-400 bg-white px-4 py-3 shadow-lg"
      data-testid="block-drag-preview"
    >
      <span className="text-xl">{info.icon}</span>
      <div>
        <div className="text-sm font-medium text-gray-900">{info.name}</div>
        <div className="text-xs text-blue-600">Drop into target area</div>
      </div>
    </div>
  );
};

export default BlockDragPreview;
