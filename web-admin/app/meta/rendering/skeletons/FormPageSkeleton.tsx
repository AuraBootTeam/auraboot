import React from 'react';

const shimmer = 'animate-pulse bg-gray-200 rounded';

export const FormPageSkeleton: React.FC = () => {
  return (
    <div className="max-w-4xl space-y-6 p-4" data-testid="form-page-skeleton">
      {/* Breadcrumb */}
      <div className="flex items-center gap-2">
        <div className={`h-4 w-16 ${shimmer}`} />
        <div className={`h-4 w-4 ${shimmer}`} />
        <div className={`h-4 w-24 ${shimmer}`} />
      </div>

      {/* Form section 1 */}
      <div className="space-y-4 rounded-lg border border-gray-200 p-6">
        <div className={`h-5 w-32 ${shimmer}`} />
        <div className="grid grid-cols-2 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="space-y-2">
              <div className={`h-4 w-20 ${shimmer}`} />
              <div className={`h-9 w-full ${shimmer}`} />
            </div>
          ))}
        </div>
      </div>

      {/* Form section 2 */}
      <div className="space-y-4 rounded-lg border border-gray-200 p-6">
        <div className={`h-5 w-40 ${shimmer}`} />
        <div className="grid grid-cols-2 gap-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="space-y-2">
              <div className={`h-4 w-24 ${shimmer}`} />
              <div className={`h-9 w-full ${shimmer}`} />
            </div>
          ))}
        </div>
      </div>

      {/* Buttons */}
      <div className="flex items-center justify-end gap-3">
        <div className={`h-9 w-20 ${shimmer}`} />
        <div className={`h-9 w-20 ${shimmer}`} />
      </div>
    </div>
  );
};
