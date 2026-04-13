/**
 * Loading fallback for React.lazy() route-level code splitting.
 * Displays a centered spinner while the route module is being loaded.
 */

import React from 'react';

export function RouteLoadingFallback() {
  return (
    <div className="flex h-[calc(100vh-64px)] items-center justify-center">
      <div className="flex flex-col items-center gap-3">
        <div className="h-10 w-10 animate-spin rounded-full border-4 border-blue-600 border-t-transparent" />
        <span className="text-sm text-gray-500 dark:text-gray-400">Loading...</span>
      </div>
    </div>
  );
}
