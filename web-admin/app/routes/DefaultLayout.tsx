import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import Header from '~/routes/Header';
import LeftSidebar from '~/routes/LeftSidebar';
import PageContent from '~/routes/PageContent';
import { useAuraBot } from '~/plugins/core-aurabot/components-shell/AuraBotProvider';
import { recordVisit } from '~/plugins/core-dashboard/widgets/workbench/useRecentVisits';
// Eagerly import AuraBotPanel (non-lazy) — the panel is small, always needed
// for logged-in users, and lazy loading caused a race in dev mode where the
// chunk had not finished transpiling when the user clicked the toggle,
// leaving the panel invisible after the first click. See GAP-262.
import { AuraBotPanel } from '~/plugins/core-aurabot/components-shell/AuraBotPanel';

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

        {aiPanelOpen && <AuraBotPanel />}
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
