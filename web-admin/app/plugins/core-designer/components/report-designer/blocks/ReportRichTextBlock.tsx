/**
 * ReportRichTextBlock — static text block for report descriptions/notes
 */

import { useSmartText } from '~/utils/i18n';
import React from 'react';
import type { RichTextBlock } from '../types';

interface ReportRichTextBlockProps {
  block: RichTextBlock;
  mode: 'design' | 'runtime';
}

export const ReportRichTextBlock: React.FC<ReportRichTextBlockProps> = ({ block, mode }) => {
  const text = useSmartText();
  const style: React.CSSProperties = {
    textAlign: block.align || 'left',
    fontSize: block.style?.fontSize ? `${block.style.fontSize}pt` : undefined,
    fontWeight: block.style?.fontWeight || undefined,
    color: block.style?.color || undefined,
  };

  if (!block.content && mode === 'design') {
    return (
      <div className="rounded border border-dashed border-gray-300 p-4 text-center text-sm text-gray-400">
        {text({ zh: '点击添加文本内容', en: 'Click to add text content' })}
      </div>
    );
  }

  // Simple paragraph rendering: split by newlines
  const paragraphs = (block.content || '').split('\n').filter(Boolean);

  return (
    <div style={style} className="text-sm text-gray-700">
      {paragraphs.map((p, i) => (
        <p key={i} className="mb-2 last:mb-0">
          {p}
        </p>
      ))}
    </div>
  );
};
