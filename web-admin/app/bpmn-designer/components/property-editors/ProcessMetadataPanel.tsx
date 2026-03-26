/**
 * Process-level metadata editing panel (shown when no node/edge is selected).
 */

import type { ProcessMetadataProps } from '../BPMNPropertyPanel';

const CATEGORY_OPTIONS = ['finance', 'HR', 'procurement', 'sales', 'general', 'e2e-test'];

export function ProcessMetadataPanel({ metadata }: { metadata: ProcessMetadataProps }) {
  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">流程名称</label>
        <input
          type="text"
          value={metadata.name}
          onChange={(e) => metadata.onNameChange(e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          placeholder="Process Name"
          data-testid="prop-panel-name"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">流程标识</label>
        <input
          type="text"
          value={metadata.processKey}
          readOnly={metadata.isExisting}
          className={`w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm ${
            metadata.isExisting ? 'cursor-not-allowed bg-gray-50 text-gray-500' : ''
          }`}
          data-testid="prop-panel-key"
        />
        {metadata.isExisting && (
          <p className="mt-1 text-xs text-gray-400">流程标识创建后不可修改</p>
        )}
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
        <textarea
          value={metadata.description}
          onChange={(e) => metadata.onDescriptionChange(e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          rows={3}
          placeholder="Process description"
          data-testid="prop-panel-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">分类</label>
        <div className="relative">
          <input
            type="text"
            value={metadata.category}
            onChange={(e) => metadata.onCategoryChange(e.target.value)}
            list="category-options"
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            placeholder="Select or type a category"
            data-testid="prop-panel-category"
          />
          <datalist id="category-options">
            {CATEGORY_OPTIONS.map((cat) => (
              <option key={cat} value={cat} />
            ))}
          </datalist>
        </div>
      </div>
    </>
  );
}
