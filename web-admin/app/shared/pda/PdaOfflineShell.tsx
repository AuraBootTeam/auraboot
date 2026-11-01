import { useEffect } from 'react';
import { useLocation } from 'react-router';
import {
  isPdaShellPath,
  replayNow,
  startPdaOfflineShell,
  suspendPdaOfflineShell,
} from './offlineReplay';

/** Schedule inventory replay only while an authenticated PDA route is active. */
export function PdaOfflineShell({ enabled }: { enabled: boolean }) {
  const { pathname } = useLocation();
  useEffect(() => {
    if (!enabled || !isPdaShellPath(pathname)) return;
    startPdaOfflineShell();
    if (navigator.onLine) void replayNow('route-entry');
    return suspendPdaOfflineShell;
  }, [enabled, pathname]);
  return null;
}
