import React from 'react';

const shimmer = 'animate-pulse bg-gray-200 rounded';

export const DetailPageSkeleton: React.FC = () => {
  return (
    <div className="space-y-6 p-4" data-testid="detail-page-skeleton">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={`h-4 w-16 ${shimmer}`} />
          <div className={`h-4 w-4 ${shimmer}`} />
          <div className={`h-5 w-32 ${shimmer}`} />
        </div>
        <div className="flex items-center gap-2">
          <div className={`h-8 w-16 ${shimmer}`} />
          <div className={`h-8 w-16 ${shimmer}`} />
        </div>
      </div>

      {/* Description block */}
      <div className="rounded-lg border border-gray-200 p-6">
        <div className="grid grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="space-y-1">
              <div className={`h-3 w-16 ${shimmer}`} />
              <div className={`h-5 w-28 ${shimmer}`} />
            </div>
          ))}
        </div>
      </div>

      {/* Tabs */}
      <div className="space-y-4">
        <div className="flex items-center gap-4 border-b border-gray-200 pb-2">
          <div className={`h-4 w-16 ${shimmer}`} />
          <div className={`h-4 w-20 ${shimmer}`} />
          <div className={`h-4 w-16 ${shimmer}`} />
        </div>

        {/* Tab content — table */}
        <div className="overflow-hidden rounded-lg border border-gray-200">
          {Array.from({ length: 4 }).map((_, rowIdx) => (
            <div
              key={rowIdx}
              className="flex items-center gap-4 border-t border-gray-100 px-4 py-3 first:border-t-0"
            >
              {Array.from({ length: 5 }).map((_, colIdx) => (
                <div
                  key={colIdx}
                  className={`h-4 flex-1 ${shimmer}`}
                  style={{ opacity: 0.7 - rowIdx * 0.1 }}
                />
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
