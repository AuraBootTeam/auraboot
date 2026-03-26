/**
 * WatermarkBlockEditor — property editor for watermark blocks
 */

import React from 'react';
import type { WatermarkBlock } from '../types';

interface WatermarkBlockEditorProps {
  block: WatermarkBlock;
  onChange: (updates: Partial<WatermarkBlock>) => void;
}

export const WatermarkBlockEditor: React.FC<WatermarkBlockEditorProps> = ({ block, onChange }) => {
  return (
    <div className="space-y-4">
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Text</label>
        <input
          type="text"
          value={block.text || ''}
          onChange={(e) => onChange({ text: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="e.g. CONFIDENTIAL"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          Rotation ({block.rotation ?? -30}&deg;)
        </label>
        <input
          type="range"
          value={block.rotation ?? -30}
          onChange={(e) => onChange({ rotation: Number(e.target.value) })}
          className="w-full"
          min={-90}
          max={90}
          step={5}
        />
        <div className="flex justify-between text-xs text-gray-400">
          <span>-90&deg;</span>
          <span>0&deg;</span>
          <span>90&deg;</span>
        </div>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          Opacity ({Math.round((block.opacity ?? 0.1) * 100)}%)
        </label>
        <input
          type="range"
          value={block.opacity ?? 0.1}
          onChange={(e) => onChange({ opacity: Number(e.target.value) })}
          className="w-full"
          min={0.02}
          max={0.5}
          step={0.02}
        />
        <div className="flex justify-between text-xs text-gray-400">
          <span>2%</span>
          <span>25%</span>
          <span>50%</span>
        </div>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Font Size</label>
        <input
          type="number"
          value={block.fontSize ?? 16}
          onChange={(e) => onChange({ fontSize: Number(e.target.value) })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          min={8}
          max={72}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Color</label>
        <div className="flex items-center gap-2">
          <input
            type="color"
            value={block.color ?? '#000000'}
            onChange={(e) => onChange({ color: e.target.value })}
            className="h-8 w-10 cursor-pointer rounded border border-gray-300"
          />
          <input
            type="text"
            value={block.color ?? '#000000'}
            onChange={(e) => onChange({ color: e.target.value })}
            className="flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            placeholder="#000000"
          />
        </div>
      </div>

      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          id="watermarkRepeat"
          checked={block.repeat !== false}
          onChange={(e) => onChange({ repeat: e.target.checked })}
          className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <label htmlFor="watermarkRepeat" className="text-sm text-gray-700">
          Repeat pattern
        </label>
      </div>
    </div>
  );
};
