import React from 'react';

/** Skeleton shimmer animation class */
const shimmer = 'animate-pulse bg-gray-200 rounded';

export const ListPageSkeleton: React.FC = () => {
  return (
    <div className="space-y-4 p-4" data-testid="list-page-skeleton">
      {/* Toolbar skeleton */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className={`h-8 w-20 ${shimmer}`} />
          <div className={`h-8 w-20 ${shimmer}`} />
        </div>
        <div className={`h-8 w-24 ${shimmer}`} />
      </div>

      {/* Filter skeleton */}
      <div className="flex items-center gap-3">
        <div className={`h-8 w-40 ${shimmer}`} />
        <div className={`h-8 w-40 ${shimmer}`} />
        <div className={`h-8 w-24 ${shimmer}`} />
      </div>

      {/* Table header */}
      <div className="overflow-hidden rounded-lg border border-gray-200">
        <div className="flex items-center gap-4 bg-gray-50 px-4 py-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className={`h-4 flex-1 ${shimmer}`} />
          ))}
        </div>

        {/* Table rows */}
        {Array.from({ length: 5 }).map((_, rowIdx) => (
          <div key={rowIdx} className="flex items-center gap-4 border-t border-gray-100 px-4 py-3">
            {Array.from({ length: 6 }).map((_, colIdx) => (
              <div
                key={colIdx}
                className={`h-4 flex-1 ${shimmer}`}
                style={{ opacity: 0.7 - rowIdx * 0.1 }}
              />
            ))}
          </div>
        ))}
      </div>

      {/* Pagination skeleton */}
      <div className="flex items-center justify-end gap-2">
        <div className={`h-8 w-8 ${shimmer}`} />
        <div className={`h-8 w-8 ${shimmer}`} />
        <div className={`h-8 w-8 ${shimmer}`} />
      </div>
    </div>
  );
};
