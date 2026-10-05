/**
 * ReportBandBlock — renders header/footer band in design or runtime mode
 */

import { useSmartText } from '~/utils/i18n';
import React from 'react';
import type { ReportBand, BandElement } from '../types';

interface ReportBandBlockProps {
  band: ReportBand;
  mode: 'design' | 'runtime';
  position: 'header' | 'footer';
}

const BandElementRenderer: React.FC<{ element: BandElement }> = ({ element }) => {
  const text = useSmartText();
  const style: React.CSSProperties = {
    textAlign: element.align || 'left',
    fontSize: element.style?.fontSize ? `${element.style.fontSize}pt` : undefined,
    fontWeight: element.style?.fontWeight || undefined,
    color: element.style?.color || undefined,
    fontFamily: element.style?.fontFamily || undefined,
  };

  switch (element.type) {
    case 'text':
      return <div style={style}>{element.content || ''}</div>;
    case 'page-number':
      return <div style={style}>{text({ zh: '第 1 页', en: 'Page 1' })}</div>;
    case 'date':
      return <div style={style}>{new Date().toLocaleDateString()}</div>;
    case 'image':
      return (
        <div style={style}>
          {element.content ? (
            <img src={element.content} alt="" className="inline max-h-8" />
          ) : (
            <span className="text-xs text-gray-400">{text({ zh: '[图片]', en: '[Image]' })}</span>
          )}
        </div>
      );
    default:
      return null;
  }
};

export const ReportBandBlock: React.FC<ReportBandBlockProps> = ({ band, mode, position }) => {
  const text = useSmartText();
  const bgColor = position === 'header' ? 'bg-blue-50' : 'bg-gray-50';
  const label =
    position === 'header' ? text({ zh: '页眉', en: 'Header' }) : text({ zh: '页脚', en: 'Footer' });

  return (
    <div
      className={`${mode === 'design' ? bgColor : ''} rounded px-3 py-2`}
      style={{ minHeight: mode === 'design' ? `${Math.max(band.height * 2, 32)}px` : undefined }}
    >
      {mode === 'design' && (
        <div className="mb-1 text-[10px] tracking-wider text-gray-400 uppercase">{label}</div>
      )}
      <div className="flex items-center justify-between gap-4">
        {band.elements.map((el, idx) => (
          <div key={idx} className="flex-1">
            <BandElementRenderer element={el} />
          </div>
        ))}
      </div>
    </div>
  );
};
