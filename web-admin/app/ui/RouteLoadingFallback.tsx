/**
 * Loading fallback for React.lazy() route-level code splitting.
 * Displays a centered spinner while the route module is being loaded.
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';

export function RouteLoadingFallback() {
  const st = useSmartText();
  return (
    <div role="status" aria-live="polite" data-testid="route-loading-fallback" className="flex h-[calc(100vh-64px)] items-center justify-center">
      <div className="flex flex-col items-center gap-3">
        <div className="rounded-pill border-accent h-10 w-10 animate-spin border-4 border-t-transparent" />
        <span className="text-text-2 text-sm dark:text-gray-400">{st({ zh: '加载中…', en: 'Loading…' })}</span>
      </div>
    </div>
  );
}
