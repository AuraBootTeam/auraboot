/**
 * RichTextBlockEditor — property editor for rich-text blocks
 */

import React from 'react';
import type { RichTextBlock } from '../types';

interface RichTextBlockEditorProps {
  block: RichTextBlock;
  onChange: (updates: Partial<RichTextBlock>) => void;
}

export const RichTextBlockEditor: React.FC<RichTextBlockEditorProps> = ({ block, onChange }) => {
  return (
    <div className="space-y-4">
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Content</label>
        <textarea
          value={block.content || ''}
          onChange={(e) => onChange({ content: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          rows={6}
          placeholder="Enter text content..."
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Alignment</label>
        <div className="flex gap-1">
          {(['left', 'center', 'right'] as const).map((align) => (
            <button
              key={align}
              onClick={() => onChange({ align })}
              className={`rounded border px-3 py-1.5 text-sm ${
                (block.align || 'left') === align
                  ? 'border-blue-300 bg-blue-50 text-blue-700'
                  : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50'
              }`}
            >
              {align.charAt(0).toUpperCase() + align.slice(1)}
            </button>
          ))}
        </div>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Font Size (pt)</label>
        <input
          type="number"
          value={block.style?.fontSize || 10}
          onChange={(e) =>
            onChange({ style: { ...block.style, fontSize: Number(e.target.value) } })
          }
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          min={6}
          max={48}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Font Weight</label>
        <select
          value={block.style?.fontWeight || 'normal'}
          onChange={(e) =>
            onChange({ style: { ...block.style, fontWeight: e.target.value as 'normal' | 'bold' } })
          }
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="normal">Normal</option>
          <option value="bold">Bold</option>
        </select>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Color</label>
        <input
          type="color"
          value={block.style?.color || '#333333'}
          onChange={(e) => onChange({ style: { ...block.style, color: e.target.value } })}
          className="h-8 w-10 cursor-pointer rounded border border-gray-300"
        />
      </div>
    </div>
  );
};
