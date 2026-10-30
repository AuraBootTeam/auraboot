import { AgentRouteBoundary } from '~/contexts/ApplicationCapabilitiesContext';
import { Outlet } from 'react-router';
import { usePagePresentation } from '~/shared/layout/PagePresentationContext';

export default function PageContent() {
  const themedShell = usePagePresentation()?.workspaceShell?.useThemeTokens === true;
  return (
    <main
      className={`flex flex-1 flex-col overflow-y-auto ${themedShell ? 'bg-bg' : 'bg-gray-50 dark:bg-gray-900'}`}
      data-aura-scroll-container="page-content"
    >
      <div className="flex min-h-0 flex-1 flex-col">
        <AgentRouteBoundary>
          <Outlet />
        </AgentRouteBoundary>
      </div>
    </main>
  );
}
