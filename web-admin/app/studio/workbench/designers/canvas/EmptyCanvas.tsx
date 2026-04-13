/**
 * EmptyCanvas — Empty state with quick-add buttons
 *
 * Shown when the canvas has no blocks. Provides visual guidance
 * and quick shortcuts to add common block types.
 *
 * @since 4.0.0
 */

import React from 'react';

export interface EmptyCanvasProps {
  onAddBlock: (blockType: string) => void;
}

/**
 * Simple SVG icons for quick-add buttons
 */
const TableIcon = () => (
  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M3 10h18M3 14h18m-9-4v8m-7 0h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
  </svg>
);

const FormIcon = () => (
  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
  </svg>
);

const ChartIcon = () => (
  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
  </svg>
);

export const EmptyCanvas: React.FC<EmptyCanvasProps> = ({ onAddBlock }) => {
  return (
    <div
      className="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-gray-300 py-16"
      data-testid="canvas-empty-state"
    >
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-gray-100 text-2xl text-gray-400">
        +
      </div>
      <p className="mb-1 text-sm font-medium text-gray-600">
        Drag blocks from the left panel
      </p>
      <p className="mb-6 text-xs text-gray-400">
        or use quick shortcuts
      </p>
      <div className="flex gap-3">
        <button
          onClick={() => onAddBlock('table')}
          className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50"
          data-testid="canvas-quick-add-table"
        >
          <TableIcon />
          Table
        </button>
        <button
          onClick={() => onAddBlock('form-section')}
          className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50"
          data-testid="canvas-quick-add-form"
        >
          <FormIcon />
          Form
        </button>
        <button
          onClick={() => onAddBlock('chart')}
          className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50"
          data-testid="canvas-quick-add-chart"
        >
          <ChartIcon />
          Chart
        </button>
      </div>
    </div>
  );
};

export default EmptyCanvas;
