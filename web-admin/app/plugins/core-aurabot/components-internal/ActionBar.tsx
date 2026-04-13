import { useEffect, useState } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import type { PageContext } from '~/plugins/core-aurabot/hooks/usePageContext';

interface Action {
  code: string;
  label: string;
  type: string;
}

interface ActionBarProps {
  pageContext: PageContext;
  onSendPrompt: (prompt: string) => void;
}

export function ActionBar({ pageContext, onSendPrompt }: ActionBarProps) {
  const [actions, setActions] = useState<Action[]>([]);

  useEffect(() => {
    if (
      !pageContext.modelCode ||
      pageContext.pageType === 'list' ||
      pageContext.pageType === 'custom'
    ) {
      setActions([]);
      return;
    }
    loadActions(pageContext).then(setActions);
  }, [pageContext.modelCode, pageContext.recordPid, pageContext.pageType]);

  if (actions.length === 0) return null;

  return (
    <div className="border-t border-gray-100 px-3 py-2 dark:border-gray-700">
      <div className="flex flex-wrap gap-2">
        {actions.slice(0, 4).map((action) => (
          <button
            key={action.code}
            onClick={() => onSendPrompt(`请执行操作: ${action.label}`)}
            className="rounded-lg bg-gray-100 px-3 py-1.5 text-xs font-medium text-gray-700 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            {action.label}
          </button>
        ))}
      </div>
    </div>
  );
}

async function loadActions(pageContext: PageContext): Promise<Action[]> {
  try {
    const result = await fetchResult(
      `/api/ai/aurabot/actions?modelCode=${pageContext.modelCode}` +
        (pageContext.recordPid ? `&recordPid=${pageContext.recordPid}` : ''),
      { method: 'get' },
    );
    if (result.code === '0' && Array.isArray(result.data)) {
      return result.data;
    }
  } catch {
    // Actions are optional
  }
  return [];
}
