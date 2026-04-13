/**
 * SmartRichText Component
 *
 * Renders HTML or markdown content in a dashboard widget.
 * Uses DOMPurify for XSS-safe HTML sanitization.
 */

import React, { useMemo } from 'react';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { cn } from '~/utils/cn';

export interface SmartRichTextProps {
  title?: string;
  content?: string;
  format?: 'html' | 'markdown';
  className?: string;
  style?: React.CSSProperties;
}

/**
 * Very basic markdown to HTML conversion
 */
function markdownToHtml(md: string): string {
  return (
    md
      // Headers
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h2>$1</h2>')
      .replace(/^# (.+)$/gm, '<h1>$1</h1>')
      // Bold and italic
      .replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      // Links
      .replace(
        /\[([^\]]+)\]\(([^)]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>',
      )
      // Line breaks
      .replace(/\n\n/g, '</p><p>')
      .replace(/\n/g, '<br/>')
      // Wrap in paragraph
      .replace(/^(.+)$/, '<p>$1</p>')
  );
}

export const SmartRichText: React.FC<SmartRichTextProps> = ({
  title,
  content = '',
  format = 'html',
  className,
  style,
}) => {
  const renderedHtml = useMemo(() => {
    if (!content) return '';
    const html = format === 'markdown' ? markdownToHtml(content) : content;
    return sanitizeHtml(html);
  }, [content, format]);

  // No content state
  if (!content) {
    return (
      <div
        className={cn(
          'flex h-full items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={style}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">📝</div>
          <div className="font-medium text-gray-500">{title || '富文本'}</div>
          <div className="mt-1 text-sm text-gray-400">请在右侧配置内容</div>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'h-full overflow-auto rounded-lg border border-gray-200 bg-white p-4',
        className,
      )}
      style={style}
    >
      {title && (
        <div className="mb-3 border-b border-gray-100 pb-2 text-sm font-medium text-gray-500">
          {title}
        </div>
      )}
      <div
        className="prose prose-sm max-w-none text-gray-700"
        dangerouslySetInnerHTML={{ __html: renderedHtml }}
      />
    </div>
  );
};

export default SmartRichText;
