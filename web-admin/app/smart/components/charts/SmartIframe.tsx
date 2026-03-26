/**
 * SmartIframe Component
 *
 * Embeds external content via iframe with sandboxing.
 * Provides a secure wrapper for displaying external web content.
 */

import React from 'react';
import { cn } from '~/utils/cn';

/**
 * Props for SmartIframe component
 */
export interface SmartIframeProps {
  /** Widget title */
  title?: string;
  /** Iframe source URL */
  src?: string;
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
}

export const SmartIframe: React.FC<SmartIframeProps> = ({ title, src, className, style }) => {
  // No source state
  if (!src) {
    return (
      <div
        className={cn(
          'flex h-full items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={style}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">🌐</div>
          <div className="font-medium text-gray-500">{title || '内嵌页面'}</div>
          <div className="mt-1 text-sm text-gray-400">请在右侧配置页面地址</div>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'flex h-full flex-col overflow-hidden rounded-lg border border-gray-200 bg-white',
        className,
      )}
      style={style}
    >
      {title && (
        <div className="flex-shrink-0 border-b border-gray-100 px-4 pt-3 pb-2 text-sm font-medium text-gray-500">
          {title}
        </div>
      )}
      <div className="min-h-0 flex-1">
        <iframe
          src={src}
          title={title || 'Embedded content'}
          sandbox="allow-scripts allow-forms"
          className="h-full w-full border-0"
          loading="lazy"
        />
      </div>
    </div>
  );
};

export default SmartIframe;
