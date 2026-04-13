/**
 * GalleryView Component
 *
 * Adapter that bridges SavedView's ViewConfig to a CSS Grid gallery layout.
 * Reads gallery configuration from ViewConfig, fetches data via dynamicService,
 * and renders image cards in a responsive grid.
 */

import React, { useCallback, useState, useEffect, useRef } from 'react';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FilterConfig } from '~/framework/smart/types/chart';
import { dynamicService } from '~/services/dynamicService';
import { cn } from '~/utils/cn';
import { DataLimitBanner, ViewEmptyState } from './shared';

/**
 * Props for GalleryView component
 */
export interface GalleryViewProps {
  /** View configuration containing gallery settings */
  viewConfig?: ViewConfig;
  /** Model code for data fetching */
  modelCode: string;
  /** Callback when a gallery card (record) is clicked */
  onCardClick?: (recordId: string) => void;
  /** External filter conditions */
  linkageFilters?: FilterConfig[];
  /** Callback to open view configuration */
  onOpenViewConfig?: () => void;
  /** Callback to switch to table view */
  onSwitchToTableView?: () => void;
  /** Custom CSS class */
  className?: string;
}

interface GalleryItem {
  id: string;
  recordPid: string;
  imageUrl: string;
  title: string;
  description?: string;
  record: Record<string, unknown>;
}

const ASPECT_RATIO_MAP: Record<string, string> = {
  square: 'aspect-square',
  '4:3': 'aspect-[4/3]',
  '16:9': 'aspect-video',
  auto: '',
};

const GRID_COLS_MAP: Record<number, string> = {
  2: 'grid-cols-2',
  3: 'grid-cols-2 sm:grid-cols-3',
  4: 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-4',
  6: 'grid-cols-3 sm:grid-cols-4 lg:grid-cols-6',
};

/**
 * GalleryView - Renders records as image cards in a grid layout
 */
export const GalleryView: React.FC<GalleryViewProps> = ({
  viewConfig,
  modelCode,
  onCardClick,
  onOpenViewConfig,
  onSwitchToTableView,
  linkageFilters,
  className,
}) => {
  const [items, setItems] = useState<GalleryItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [totalCount, setTotalCount] = useState(0);
  const [lightboxItem, setLightboxItem] = useState<GalleryItem | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const imageField = viewConfig?.galleryImageField;
  const titleField = viewConfig?.galleryTitleField || 'name';
  const descriptionField = viewConfig?.galleryDescriptionField;
  const columns = viewConfig?.galleryColumns || 4;
  const aspectRatio = viewConfig?.galleryAspectRatio || '4:3';
  const showTitle = viewConfig?.galleryShowTitle ?? true;
  const showDescription = viewConfig?.galleryShowDescription ?? false;

  const aspectClass = ASPECT_RATIO_MAP[aspectRatio] || 'aspect-[4/3]';
  const gridClass = GRID_COLS_MAP[columns] || 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-4';

  // Fetch records and convert to gallery items
  const fetchItems = useCallback(async () => {
    if (!imageField) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);

    try {
      const result = await dynamicService.findByPage(modelCode, {
        page: 0,
        size: 200,
      });

      if (controller.signal.aborted) return;

      setTotalCount(result.total ?? result.records.length);

      const galleryItems: GalleryItem[] = result.records
        .filter((record) => record[imageField])
        .map((record) => ({
          id: String(record.id ?? record.pid ?? ''),
          recordPid: String(record.pid ?? record.id ?? ''),
          imageUrl: String(record[imageField]),
          title: String(record[titleField] ?? record['name'] ?? 'Untitled'),
          description: descriptionField ? String(record[descriptionField] ?? '') : undefined,
          record,
        }));

      setItems(galleryItems);
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(err instanceof Error ? err.message : 'Failed to fetch gallery data');
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
  }, [modelCode, imageField, titleField, descriptionField, linkageFilters]);

  useEffect(() => {
    fetchItems();
    return () => {
      abortRef.current?.abort();
    };
  }, [fetchItems]);

  // Handle card click
  const handleCardClick = useCallback(
    (item: GalleryItem) => {
      if (onCardClick) {
        onCardClick(item.recordPid);
      }
    },
    [onCardClick],
  );

  // Handle image click for lightbox
  const handleImageClick = useCallback((e: React.MouseEvent, item: GalleryItem) => {
    e.stopPropagation();
    setLightboxItem(item);
  }, []);

  // Close lightbox
  const closeLightbox = useCallback(() => {
    setLightboxItem(null);
  }, []);

  // Close lightbox on Escape
  useEffect(() => {
    if (!lightboxItem) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeLightbox();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [lightboxItem, closeLightbox]);

  // No image field configured
  if (!imageField) {
    return (
      <ViewEmptyState
        variant="not-configured"
        title="Gallery not configured"
        description="Please configure the Image Field to display the gallery view."
        onConfigure={onOpenViewConfig}
        onSwitchToTableView={onSwitchToTableView}
        className={className}
      />
    );
  }

  if (error) {
    return (
      <ViewEmptyState
        variant="error"
        title="Failed to load gallery data"
        error={error}
        onRetry={fetchItems}
        className={className}
      />
    );
  }

  return (
    <div className={cn('rounded-lg border border-gray-200 bg-white p-4', className)}>
      {loading && (
        <div className="mb-2 flex items-center justify-center py-2">
          <span className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
          <span className="text-sm text-gray-500">Loading...</span>
        </div>
      )}

      {!loading && items.length === 0 && (
        <ViewEmptyState
          variant="no-data"
          title="No images found"
          description="Records with image data will appear here."
        />
      )}

      <DataLimitBanner
        fetchedCount={items.length}
        totalCount={totalCount}
        onSwitchToTableView={onSwitchToTableView}
        className="mb-3"
      />

      {/* Gallery Grid */}
      <div className={cn('grid gap-4', gridClass)}>
        {items.map((item) => (
          <div
            key={item.id}
            onClick={() => handleCardClick(item)}
            className={cn(
              'group relative overflow-hidden rounded-lg border border-gray-200',
              'bg-white transition-shadow duration-200 hover:shadow-md',
              onCardClick && 'cursor-pointer',
            )}
          >
            {/* Image */}
            <div
              className={cn('relative overflow-hidden bg-gray-100', aspectClass)}
              onClick={(e) => handleImageClick(e, item)}
            >
              <img
                src={item.imageUrl}
                alt={item.title}
                loading="lazy"
                className={cn(
                  'h-full w-full object-cover',
                  'transition-transform duration-300 group-hover:scale-105',
                )}
                onError={(e) => {
                  const target = e.target as HTMLImageElement;
                  target.style.display = 'none';
                  const parent = target.parentElement;
                  if (parent && !parent.querySelector('.gallery-placeholder')) {
                    const placeholder = document.createElement('div');
                    placeholder.className =
                      'gallery-placeholder absolute inset-0 flex items-center justify-center text-gray-400';
                    placeholder.innerHTML =
                      '<svg class="w-12 h-12" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"/></svg>';
                    parent.appendChild(placeholder);
                  }
                }}
              />
              {/* Zoom icon on hover */}
              <div className="absolute inset-0 flex items-center justify-center bg-black/0 transition-colors duration-200 group-hover:bg-black/10">
                <svg
                  className="h-8 w-8 text-white opacity-0 drop-shadow-lg transition-opacity duration-200 group-hover:opacity-80"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0zM10 7v3m0 0v3m0-3h3m-3 0H7"
                  />
                </svg>
              </div>
            </div>

            {/* Card Content */}
            {(showTitle || showDescription) && (
              <div className="p-3">
                {showTitle && (
                  <h3 className="truncate text-sm font-medium text-gray-900">{item.title}</h3>
                )}
                {showDescription && item.description && (
                  <p className="mt-1 line-clamp-2 text-xs text-gray-500">{item.description}</p>
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Lightbox */}
      {lightboxItem && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80"
          onClick={closeLightbox}
        >
          <button
            onClick={closeLightbox}
            className="absolute top-4 right-4 z-10 text-white/80 hover:text-white"
          >
            <svg className="h-8 w-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
          <div
            className="flex max-h-[90vh] max-w-[90vw] flex-col items-center"
            onClick={(e) => e.stopPropagation()}
          >
            <img
              src={lightboxItem.imageUrl}
              alt={lightboxItem.title}
              className="max-h-[80vh] max-w-full rounded-lg object-contain"
            />
            {showTitle && (
              <div className="mt-3 text-center">
                <h3 className="text-lg font-medium text-white">{lightboxItem.title}</h3>
                {showDescription && lightboxItem.description && (
                  <p className="mt-1 text-sm text-gray-300">{lightboxItem.description}</p>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default GalleryView;
