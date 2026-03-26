/**
 * SmartImage Component
 *
 * A simple image display widget for dashboards.
 * Supports cover, contain, and fill object-fit modes.
 */

import React, { useState } from 'react';
import { cn } from '~/utils/cn';

/**
 * Props for SmartImage component
 */
export interface SmartImageProps {
  /** Widget title */
  title?: string;
  /** Image source URL */
  src?: string;
  /** Image alt text */
  alt?: string;
  /** Object-fit mode */
  objectFit?: 'cover' | 'contain' | 'fill';
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
}

export const SmartImage: React.FC<SmartImageProps> = ({
  title,
  src,
  alt = '',
  objectFit = 'cover',
  className,
  style,
}) => {
  const [hasError, setHasError] = useState(false);

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
          <div className="mb-3 text-4xl text-gray-400">🖼️</div>
          <div className="font-medium text-gray-500">{title || '图片'}</div>
          <div className="mt-1 text-sm text-gray-400">请在右侧配置图片地址</div>
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
        <div className="flex-shrink-0 px-4 pt-3 pb-2 text-sm font-medium text-gray-500">
          {title}
        </div>
      )}
      <div className="min-h-0 flex-1">
        {hasError ? (
          <div className="flex h-full items-center justify-center text-gray-400">
            <div className="text-center">
              <div className="mb-2 text-3xl">⚠️</div>
              <div className="text-sm">图片加载失败</div>
            </div>
          </div>
        ) : (
          <img
            src={src}
            alt={alt || title || ''}
            className="h-full w-full"
            style={{ objectFit }}
            loading="lazy"
            onError={() => setHasError(true)}
          />
        )}
      </div>
    </div>
  );
};

export default SmartImage;
