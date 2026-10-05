/**
 * BandEditor — edit header/footer band elements
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import type { ReportBand, BandElement } from '../types';

interface BandEditorProps {
  band: ReportBand;
  onChange: (band: ReportBand) => void;
}

export const BandEditor: React.FC<BandEditorProps> = ({ band, onChange }) => {
  const text = useSmartText();
  const heightId = React.useId();
  const updateElement = (idx: number, updates: Partial<BandElement>) => {
    const elements = [...band.elements];
    elements[idx] = { ...elements[idx], ...updates };
    onChange({ ...band, elements });
  };

  const removeElement = (idx: number) => {
    const elements = [...band.elements];
    elements.splice(idx, 1);
    onChange({ ...band, elements });
  };

  const addElement = (type: BandElement['type']) => {
    const newEl: BandElement = { type };
    if (type === 'text') {
      newEl.content = text({ zh: '文本', en: 'Text' });
      newEl.align = 'left';
    } else if (type === 'page-number') {
      newEl.align = 'right';
    } else if (type === 'date') {
      newEl.align = 'left';
    }
    onChange({ ...band, elements: [...band.elements, newEl] });
  };

  return (
    <div className="space-y-4">
      {/* Band height */}
      <div>
        <label htmlFor={heightId} className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '高度（毫米）', en: 'Height (mm)' })}
        </label>
        <input
          id={heightId}
          type="number"
          value={band.height}
          onChange={(e) => onChange({ ...band, height: Number(e.target.value) })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          min={5}
          max={100}
        />
      </div>

      {/* Elements */}
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">
          {text({ zh: '元素', en: 'Elements' })}
        </label>
        <div className="space-y-2">
          {band.elements.map((el, idx) => (
            <div key={idx} className="space-y-2 rounded-md bg-gray-50 p-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-gray-600 uppercase">
                  {text(
                    {
                      text: { zh: '文本', en: 'Text' },
                      'page-number': { zh: '页码', en: 'Page Number' },
                      date: { zh: '日期', en: 'Date' },
                      image: { zh: '图片', en: 'Image' },
                    }[el.type],
                  )}
                </span>
                <button
                  aria-label={text({ zh: '删除元素', en: 'Remove element' })}
                  onClick={() => removeElement(idx)}
                  className="p-1 text-gray-400 hover:text-red-500"
                >
                  <svg
                    className="h-3.5 w-3.5"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              </div>

              {(el.type === 'text' || el.type === 'image') && (
                <input
                  type="text"
                  value={el.content || ''}
                  onChange={(e) => updateElement(idx, { content: e.target.value })}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
                  placeholder={
                    el.type === 'text'
                      ? text({ zh: '文本内容', en: 'Text content' })
                      : text({ zh: '图片地址', en: 'Image URL' })
                  }
                />
              )}

              <div className="flex gap-2">
                <select
                  value={el.align || 'left'}
                  onChange={(e) =>
                    updateElement(idx, { align: e.target.value as 'left' | 'center' | 'right' })
                  }
                  className="flex-1 rounded border border-gray-300 px-2 py-1 text-xs"
                >
                  <option value="left">{text({ zh: '左对齐', en: 'Left' })}</option>
                  <option value="center">{text({ zh: '居中', en: 'Center' })}</option>
                  <option value="right">{text({ zh: '右对齐', en: 'Right' })}</option>
                </select>
                <input
                  type="number"
                  value={el.style?.fontSize || 10}
                  onChange={(e) =>
                    updateElement(idx, {
                      style: { ...el.style, fontSize: Number(e.target.value) },
                    })
                  }
                  className="w-16 rounded border border-gray-300 px-2 py-1 text-xs"
                  title={text({ zh: '字号（磅）', en: 'Font size (pt)' })}
                  min={6}
                  max={48}
                />
              </div>
            </div>
          ))}
        </div>

        <div className="mt-2 flex gap-2">
          <button
            onClick={() => addElement('text')}
            className="rounded bg-blue-50 px-2 py-1 text-xs text-blue-600 hover:bg-blue-100"
          >
            {text({ zh: '+ 文本', en: '+ Text' })}
          </button>
          <button
            onClick={() => addElement('page-number')}
            className="rounded bg-blue-50 px-2 py-1 text-xs text-blue-600 hover:bg-blue-100"
          >
            {text({ zh: '+ 页码', en: '+ Page #' })}
          </button>
          <button
            onClick={() => addElement('date')}
            className="rounded bg-blue-50 px-2 py-1 text-xs text-blue-600 hover:bg-blue-100"
          >
            {text({ zh: '+ 日期', en: '+ Date' })}
          </button>
        </div>
      </div>
    </div>
  );
};
