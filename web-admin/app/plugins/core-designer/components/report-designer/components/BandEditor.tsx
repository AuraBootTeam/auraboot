/**
 * BandEditor — edit header/footer band elements
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import type { ReportBand, BandElement } from '../types';
import { SchemaBlockConfigPanel, type ExtendedPropertySchema } from '~/shared/designer/SchemaBlockConfigPanel';

interface BandEditorProps {
  band: ReportBand;
  onChange: (band: ReportBand) => void;
}

export const BandEditor: React.FC<BandEditorProps> = ({ band, onChange }) => {
  const text = useSmartText();

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
      <SchemaBlockConfigPanel density="compact" schemas={[{key:'height',label:text({zh:'高度（毫米）',en:'Height (mm)'}),type:'number',min:5,max:100}]}
        value={band as unknown as Record<string,unknown>} onChange={next=>onChange(next as unknown as ReportBand)} />

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

              <SchemaBlockConfigPanel density="compact" pathMode="nested"
                schemas={[
                  ...(['text','image'].includes(el.type) ? [{key:'content',label:text({zh:el.type==='text'?'文本内容':'图片地址',en:el.type==='text'?'Text content':'Image URL'}),placeholder:text({zh:el.type==='text'?'文本内容':'图片地址',en:el.type==='text'?'Text content':'Image URL'}),type:'text' as const}] : []),
                  {key:'align',label:text({zh:'对齐方式',en:'Alignment'}),type:'select',defaultValue:'left',options:[{value:'left',label:text({zh:'左对齐',en:'Left'})},{value:'center',label:text({zh:'居中',en:'Center'})},{value:'right',label:text({zh:'右对齐',en:'Right'})}]},
                  {key:'style.fontSize',label:text({zh:'字号（磅）',en:'Font size (pt)'}),type:'number',min:6,max:48,defaultValue:10},
                ] satisfies ExtendedPropertySchema<string>[]}
                value={el as unknown as Record<string,unknown>}
                onChange={next=>updateElement(idx,next as unknown as BandElement)} />
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
