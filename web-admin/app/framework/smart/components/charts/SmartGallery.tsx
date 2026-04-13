/**
 * SmartGallery Component
 *
 * Mixed-mode gallery: supports static images and dynamic model data.
 * Renders cards in a configurable grid with lightbox preview.
 */

import React, { useMemo, useState, useCallback, useEffect } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type { ChartDataSource, LinkageConfig, FilterConfig } from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

interface GalleryItem {
  image: string;
  title?: string;
  description?: string;
  link?: string;
}

export interface SmartGalleryProps {
  title?: string;
  dataSource: ChartDataSource;
  columns?: number;
  imageField?: string;
  titleField?: string;
  descriptionField?: string;
  linkField?: string;
  staticItems?: GalleryItem[];
  imageHeight?: number;
  imageFit?: 'cover' | 'contain' | 'fill';
  showLightbox?: boolean;
  gap?: number;
  borderRadius?: number;
  linkage?: LinkageConfig;
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  linkageFilters?: FilterConfig[];
  refreshInterval?: number;
  className?: string;
  style?: React.CSSProperties;
}

function isDataSourceConfigured(ds: ChartDataSource): boolean {
  if (!ds) return false;
  if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
  if (ds.type === 'namedQuery') return !!ds.queryCode;
  return ds.type === 'static';
}

const FALLBACK_IMAGE = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="200" height="160" fill="%23e5e7eb"%3E%3Crect width="200" height="160" rx="4"/%3E%3Ctext x="100" y="85" text-anchor="middle" fill="%239ca3af" font-size="14"%3ENo Image%3C/text%3E%3C/svg%3E';

export const SmartGallery: React.FC<SmartGalleryProps> = ({
  title,
  dataSource,
  columns = 3,
  imageField,
  titleField,
  descriptionField,
  linkField,
  staticItems,
  imageHeight = 160,
  imageFit = 'cover',
  showLightbox = true,
  gap = 12,
  borderRadius = 8,
  linkage,
  onLinkageEmit,
  linkageFilters,
  refreshInterval,
  className,
  style,
}) => {
  const isStatic = dataSource?.type === 'static';
  const isConfigured = isStatic ? true : isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource,
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: isConfigured && !isStatic,
  });

  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);

  const items: GalleryItem[] = useMemo(() => {
    if (isStatic && staticItems?.length) return staticItems;
    if (!data?.rows?.length) return [];

    return data.rows.map((row) => ({
      image: imageField ? String(row[imageField] ?? '') : '',
      title: titleField ? String(row[titleField] ?? '') : undefined,
      description: descriptionField ? String(row[descriptionField] ?? '') : undefined,
      link: linkField ? String(row[linkField] ?? '') : undefined,
    }));
  }, [data, isStatic, staticItems, imageField, titleField, descriptionField, linkField]);

  const handleImageClick = useCallback(
    (item: GalleryItem) => {
      if (showLightbox && item.image) {
        setLightboxSrc(item.image);
      }
    },
    [showLightbox],
  );

  const handleCardClick = useCallback(
    (item: GalleryItem, index: number) => {
      if (item.link) {
        window.open(item.link, '_blank', 'noopener');
      }
      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit && data?.meta?.dimensions?.[0]) {
        const filter: FilterConfig = {
          field: data.meta.dimensions[0],
          operator: 'eq',
          value: data.rows[index]?.[data.meta.dimensions[0]],
        };
        onLinkageEmit([filter]);
      }
    },
    [linkage, onLinkageEmit, data],
  );

  useEffect(() => {
    if (!lightboxSrc) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setLightboxSrc(null);
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [lightboxSrc]);

  if (!isConfigured && !isStatic) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">🖼️</div>
          <div className="font-medium text-gray-500">{title || 'Gallery'}</div>
          <div className="mt-1 text-sm text-gray-400">Please configure data source</div>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="flex flex-col items-center gap-2">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <span className="text-sm text-gray-500">Loading...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-red-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
        role="alert"
      >
        <div className="text-center">
          <div className="mb-2 text-lg text-red-500">Failed to load gallery</div>
          <div className="text-sm text-gray-500">{error.message}</div>
        </div>
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">🖼️</div>
          <div className="font-medium text-gray-500">No items</div>
          <div className="mt-1 text-sm text-gray-400">No data available for gallery</div>
        </div>
      </div>
    );
  }

  return (
    <div className={cn('overflow-auto rounded-lg border border-gray-200 bg-white p-4', className)} style={style}>
      {title && (
        <h3 className="mb-3 text-sm font-medium text-gray-700">{title}</h3>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: `repeat(${columns}, 1fr)`,
          gap: `${gap}px`,
        }}
      >
        {items.map((item, idx) => (
          <div
            key={idx}
            className="overflow-hidden bg-white shadow-sm transition-shadow hover:shadow-md"
            style={{ borderRadius: `${borderRadius}px`, border: '1px solid #f0f0f0' }}
          >
            <div
              className="cursor-pointer overflow-hidden"
              style={{ height: `${imageHeight}px` }}
              onClick={() => handleImageClick(item)}
            >
              <img
                src={item.image || FALLBACK_IMAGE}
                alt={item.title || ''}
                className="h-full w-full"
                style={{ objectFit: imageFit }}
                onError={(e) => {
                  (e.target as HTMLImageElement).src = FALLBACK_IMAGE;
                }}
              />
            </div>
            {(item.title || item.description) && (
              <div
                className="cursor-pointer p-3"
                onClick={() => handleCardClick(item, idx)}
              >
                {item.title && (
                  <div className="truncate text-sm font-medium text-gray-800">{item.title}</div>
                )}
                {item.description && (
                  <div className="mt-1 line-clamp-2 text-xs text-gray-500">{item.description}</div>
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      {lightboxSrc && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
          onClick={() => setLightboxSrc(null)}
        >
          <img
            src={lightboxSrc}
            alt="Preview"
            className="max-h-[90vh] max-w-[90vw] rounded-lg object-contain shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          />
          <button
            className="absolute right-6 top-6 rounded-full bg-white/20 p-2 text-white transition-colors hover:bg-white/40"
            onClick={() => setLightboxSrc(null)}
          >
            ✕
          </button>
        </div>
      )}
    </div>
  );
};

export default SmartGallery;
