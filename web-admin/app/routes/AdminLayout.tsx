import { useApplicationCapabilities } from '~/contexts/ApplicationCapabilitiesContext';
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import Header from '~/routes/Header';
import { PagePresentationProvider, usePagePresentation } from '~/shared/layout/PagePresentationContext';
import LeftSidebar from '~/routes/LeftSidebar';
import PageContent from '~/routes/PageContent';
import { useAuraBot } from '~/plugins/core-aurabot/components-shell/AuraBotProvider';
import { recordVisit } from '~/plugins/core-dashboard/widgets/workbench/useRecentVisits';
// Eagerly import AuraBotPanel (non-lazy) — the panel is small, always needed
// for logged-in users, and lazy loading caused a race in dev mode where the
// chunk had not finished transpiling when the user clicked the toggle,
// leaving the panel invisible after the first click. See GAP-262.
import { AuraBotPanel } from '~/plugins/core-aurabot/components-shell/AuraBotPanel';
import { sanitizeRoute } from '@auraboot/track';
import { getTracker } from '~/shared/services/trackerInstance';
import { ImpersonationBanner } from '~/components/ImpersonationBanner';

function inferModelCodeFromPath(pathname: string): string | undefined {
  if (!pathname || pathname === '/') return undefined;

  if (pathname.startsWith('/p/c/')) {
    return undefined;
  }

  if (pathname.startsWith('/p/')) {
    const [, , pageKey] = pathname.split('/');
    return pageKey || undefined;
  }

  const [, firstSegment] = pathname.split('/');
  return firstSegment || undefined;
}

export default function AdminLayout() {
  return <PagePresentationProvider><AdminLayoutContent /></PagePresentationProvider>;
}

function AdminLayoutContent() {
  const workspace = usePagePresentation()?.mode === 'workspace';
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { state } = useAuraBot();
  const { agent, loading: agentLoading } = useApplicationCapabilities();
  const aiPanelOpen = agent === 'installed' && state.panelState === 'expanded';
  const location = useLocation();

  // One-time tracker init: register click-capture and flush-on-hide/pagehide listeners.
  useEffect(() => {
    getTracker().init();
  }, []);

  // Record page visits for the workbench "recent visits" widget
  // and emit a pageview telemetry event on each route change.
  useEffect(() => {
    const skipPaths = ['/home', '/home/settings', '/login', '/', '/register'];
    if (skipPaths.some((p) => location.pathname === p)) return;

    // Emit pageview telemetry alongside the existing engagement logic.
    getTracker().pageview(sanitizeRoute(location.pathname));

    // Small delay to let document.title update
    const timer = setTimeout(() => {
      recordVisit({
        title: document.title || location.pathname,
        path: location.pathname,
        modelCode: inferModelCodeFromPath(location.pathname),
      });
    }, 500);

    return () => clearTimeout(timer);
  }, [location.pathname]);

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      <Header sidebarOpen={sidebarOpen} setSidebarOpen={setSidebarOpen} />

      <div className={`flex h-screen ${workspace ? '' : 'pt-14'}`}>
        <LeftSidebar sidebarOpen={sidebarOpen} setSidebarOpen={setSidebarOpen} />

        <div className={`flex min-w-0 flex-1 flex-col overflow-hidden ${workspace ? 'pt-14' : ''}`} data-print="content">
          <ImpersonationBanner />
          <PageContent />
        </div>

        <div className={aiPanelOpen ? `flex min-h-0 shrink-0 ${workspace ? 'pt-14' : ''}` : 'hidden'} data-testid="ai-panel-dock" inert={agentLoading || undefined}>
          {agent === 'installed' && <AuraBotPanel />}
        </div>
      </div>

      {/* Feedback floating button — temporarily hidden */}
      {/* <FeedbackFab /> */}

      {sidebarOpen && (
        <div
          className="bg-opacity-75 print-hide fixed inset-0 z-40 bg-gray-600 lg:hidden"
          data-testid="sidebar-mobile-backdrop"
          onClick={() => setSidebarOpen(false)}
        />
      )}
    </div>
  );
}
