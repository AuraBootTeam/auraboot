/**
 * ReportPageSkeleton — loading skeleton for report pages
 */

import React from 'react';

export const ReportPageSkeleton: React.FC = () => {
  return (
    <div className="mx-auto max-w-4xl animate-pulse p-8">
      {/* Title */}
      <div className="mb-6 h-8 w-1/3 rounded bg-gray-200" />

      {/* Toolbar */}
      <div className="mb-6 flex gap-2">
        <div className="h-9 w-24 rounded bg-gray-200" />
        <div className="h-9 w-24 rounded bg-gray-200" />
      </div>

      {/* Table skeleton */}
      <div className="rounded border border-gray-200">
        <div className="h-10 border-b border-gray-200 bg-gray-100" />
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="flex h-10 items-center gap-4 border-b border-gray-100 px-4">
            <div className="h-4 flex-1 rounded bg-gray-200" />
            <div className="h-4 flex-1 rounded bg-gray-200" />
            <div className="h-4 flex-1 rounded bg-gray-200" />
          </div>
        ))}
      </div>
    </div>
  );
};
