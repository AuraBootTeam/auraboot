import React, { useEffect, useState, Suspense } from 'react';
import { useLocation } from 'react-router';
import Header from '~/routes/Header';
import LeftSidebar from '~/routes/LeftSidebar';
import PageContent from '~/routes/PageContent';
import { useAuraBot } from '~/aurabot/AuraBotProvider';
import { recordVisit } from '~/dashboard-designer/widgets/workbench/useRecentVisits';

const AuraBotPanel = React.lazy(() =>
  import('~/aurabot/AuraBotPanel').then((m) => ({ default: m.AuraBotPanel })),
);

export default function DefaultLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { state } = useAuraBot();
  const aiPanelOpen = state.panelState === 'expanded';
  const location = useLocation();

  // Record page visits for the workbench "recent visits" widget
  useEffect(() => {
    const skipPaths = ['/home', '/home/settings', '/login', '/', '/register'];
    if (skipPaths.some((p) => location.pathname === p)) return;

    // Small delay to let document.title update
    const timer = setTimeout(() => {
      recordVisit({
        title: document.title || location.pathname,
        path: location.pathname,
      });
    }, 500);

    return () => clearTimeout(timer);
  }, [location.pathname]);

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      <Header sidebarOpen={sidebarOpen} setSidebarOpen={setSidebarOpen} />

      <div className="flex h-screen pt-16">
        <LeftSidebar sidebarOpen={sidebarOpen} setSidebarOpen={setSidebarOpen} />

        <div className="flex flex-1 flex-col overflow-hidden" data-print="content">
          <PageContent />
        </div>

        {aiPanelOpen && (
          <Suspense fallback={<div className="w-96 animate-pulse bg-gray-100 dark:bg-gray-800" />}>
            <AuraBotPanel />
          </Suspense>
        )}
      </div>

      {/* Feedback floating button — temporarily hidden */}
      {/* <FeedbackFab /> */}

      {sidebarOpen && (
        <div
          className="bg-opacity-75 print-hide fixed inset-0 z-40 bg-gray-600 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}
    </div>
  );
}
