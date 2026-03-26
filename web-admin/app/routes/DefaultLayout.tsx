import { useState } from 'react';
import Header from '~/routes/Header';
import LeftSidebar from '~/routes/LeftSidebar';
import PageContent from '~/routes/PageContent';
import { AuraBotPanel } from '~/aurabot/AuraBotPanel';
import { useAuraBot } from '~/aurabot/AuraBotProvider';
import FeedbackFab from '~/components/FeedbackFab';

export default function DefaultLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { state } = useAuraBot();
  const aiPanelOpen = state.panelState === 'expanded';

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

      {/* Feedback floating button */}
      <FeedbackFab />

      {sidebarOpen && (
        <div
          className="bg-opacity-75 print-hide fixed inset-0 z-40 bg-gray-600 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}
    </div>
  );
}
