/**
 * Block Property Panel — edit properties of selected block, header, footer, or report
 */

import React from 'react';
import { useReportStore } from '../store/useReportStore';
import { DataTableBlockEditor } from './DataTableBlockEditor';
import { GroupedTableBlockEditor } from './GroupedTableBlockEditor';
import { StatCardBlockEditor } from './StatCardBlockEditor';
import { RichTextBlockEditor } from './RichTextBlockEditor';
import { CrossTabBlockEditor } from './CrossTabBlockEditor';
import { ChartBlockEditor } from './ChartBlockEditor';
import { BarcodeBlockEditor } from './BarcodeBlockEditor';
import { WatermarkBlockEditor } from './WatermarkBlockEditor';
import { ParameterEditor } from './ParameterEditor';
import { BandEditor } from './BandEditor';
import type { PageConfig } from '../types';

/** Trash icon SVG path */
const TrashIcon: React.FC = () => (
  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={2}
      d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
    />
  </svg>
);

/** Reusable delete button for header/footer */
const DeleteButton: React.FC<{ onClick: () => void; title: string }> = ({ onClick, title }) => (
  <button
    onClick={onClick}
    className="rounded p-1.5 text-gray-500 hover:bg-red-50 hover:text-red-600"
    title={title}
  >
    <TrashIcon />
  </button>
);

/** Reusable move up / move down / delete button group for block property panel header */
const BlockActionBar: React.FC<{
  blockId: string;
  blockIndex: number;
  totalBlocks: number;
  onMove: (blockId: string, direction: 'up' | 'down') => void;
  onRemove: (blockId: string) => void;
}> = ({ blockId, blockIndex, totalBlocks, onMove, onRemove }) => (
  <div className="flex items-center gap-1">
    <button
      onClick={() => onMove(blockId, 'up')}
      disabled={blockIndex === 0}
      className="rounded p-1.5 text-gray-500 hover:bg-blue-50 hover:text-blue-600 disabled:opacity-30"
      title="Move up"
    >
      <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" />
      </svg>
    </button>
    <button
      onClick={() => onMove(blockId, 'down')}
      disabled={blockIndex === totalBlocks - 1}
      className="rounded p-1.5 text-gray-500 hover:bg-blue-50 hover:text-blue-600 disabled:opacity-30"
      title="Move down"
    >
      <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
      </svg>
    </button>
    <button
      onClick={() => onRemove(blockId)}
      className="rounded p-1.5 text-gray-500 hover:bg-red-50 hover:text-red-600"
      title="Delete"
    >
      <TrashIcon />
    </button>
  </div>
);

/** Panel wrapper with consistent layout */
const PanelShell: React.FC<{
  title: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
}> = ({ title, actions, children }) => (
  <div
    data-testid="block-property-panel"
    className="flex w-72 flex-col overflow-hidden border-l border-gray-200 bg-white"
  >
    <div className="border-b border-gray-200 p-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-gray-700">{title}</h2>
        {actions}
      </div>
    </div>
    <div className="flex-1 overflow-y-auto p-4">{children}</div>
  </div>
);

export const BlockPropertyPanel: React.FC = () => {
  const {
    report,
    selectedBlockId,
    getBlockById,
    updateBlock,
    removeBlock,
    moveBlock,
    updateHeader,
    updateFooter,
    updateDescription,
  } = useReportStore();

  if (!report) return null;

  // Header selected
  if (selectedBlockId === '__header' && report.header) {
    return (
      <PanelShell
        title="Page Header"
        actions={<DeleteButton onClick={() => updateHeader(undefined)} title="Remove header" />}
      >
        <BandEditor band={report.header} onChange={updateHeader} />
      </PanelShell>
    );
  }

  // Footer selected
  if (selectedBlockId === '__footer' && report.footer) {
    return (
      <PanelShell
        title="Page Footer"
        actions={<DeleteButton onClick={() => updateFooter(undefined)} title="Remove footer" />}
      >
        <BandEditor band={report.footer} onChange={updateFooter} />
      </PanelShell>
    );
  }

  // Block selected
  const block = selectedBlockId ? getBlockById(selectedBlockId) : undefined;

  if (block) {
    const blockIndex = report.body.findIndex((b) => b.id === block.id);
    const actionBar = (
      <BlockActionBar
        blockId={block.id}
        blockIndex={blockIndex}
        totalBlocks={report.body.length}
        onMove={moveBlock}
        onRemove={removeBlock}
      />
    );

    if (block.blockType === 'data-table') {
      return (
        <PanelShell title="Data Table" actions={actionBar}>
          <DataTableBlockEditor
            block={block}
            dataSources={report.dataSources}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }

    if (block.blockType === 'grouped-table') {
      return (
        <PanelShell title="Grouped Table" actions={actionBar}>
          <GroupedTableBlockEditor
            block={block}
            dataSources={report.dataSources}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }

    if (block.blockType === 'stat-card') {
      return (
        <PanelShell title="Stat Card" actions={actionBar}>
          <StatCardBlockEditor
            block={block}
            dataSources={report.dataSources}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }

    if (block.blockType === 'rich-text') {
      return (
        <PanelShell title="Rich Text" actions={actionBar}>
          <RichTextBlockEditor
            block={block}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }

    if (block.blockType === 'cross-tab') {
      return (
        <PanelShell title="Cross Tab" actions={actionBar}>
          <CrossTabBlockEditor
            block={block}
            dataSources={report.dataSources}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }

    if (block.blockType === 'chart') {
      return (
        <PanelShell title="Chart" actions={actionBar}>
          <ChartBlockEditor
            block={block}
            dataSources={report.dataSources}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }

    if (block.blockType === 'barcode') {
      return (
        <PanelShell title="Barcode" actions={actionBar}>
          <BarcodeBlockEditor
            block={block}
            dataSources={report.dataSources}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }

    if (block.blockType === 'watermark') {
      return (
        <PanelShell title="Watermark" actions={actionBar}>
          <WatermarkBlockEditor
            block={block}
            onChange={(updates) => updateBlock(block.id, updates)}
          />
        </PanelShell>
      );
    }
  }

  // No selection — show report-level properties + parameter editor
  return (
    <PanelShell title="Report Properties">
      <div className="space-y-4">
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">Description</label>
          <textarea
            value={report.description || ''}
            onChange={(e) => updateDescription(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            rows={3}
            placeholder="Report description..."
          />
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">Page Size</label>
          <p className="text-sm text-gray-600">
            {report.page.size} — {report.page.orientation}
          </p>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">Data Sources</label>
          <p className="text-sm text-gray-600">
            {Object.keys(report.dataSources).length} configured
          </p>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">Blocks</label>
          <p className="text-sm text-gray-600">{report.body.length} block(s)</p>
        </div>

        {/* Parameter Editor */}
        <div className="border-t border-gray-200 pt-4">
          <ParameterEditor
            parameters={report.parameters || []}
            dataSources={report.dataSources}
            onChange={(params) => {
              if (report) {
                useReportStore.getState().updateBlock('__noop', {}); // trigger dirty
                // Direct mutation via store - parameters are on the report object
                const store = useReportStore.getState();
                if (store.report) {
                  store.report.parameters = params;
                  useReportStore.setState({ isDirty: true });
                }
              }
            }}
          />
        </div>

        <div className="pt-4 text-xs text-gray-400">
          Select a block on the canvas to edit its properties.
        </div>
      </div>
    </PanelShell>
  );
};
