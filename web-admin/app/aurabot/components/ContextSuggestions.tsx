import { getSuggestions, type Suggestion } from '~/aurabot/config/suggestions';
import type { PageContext } from '~/aurabot/hooks/usePageContext';

interface ContextSuggestionsProps {
  pageContext: PageContext;
  onSuggestionClick: (prompt: string) => void;
}

export function ContextSuggestions({ pageContext, onSuggestionClick }: ContextSuggestionsProps) {
  const suggestions = getSuggestions(pageContext.pageType, pageContext.modelCode);

  if (suggestions.length === 0) return null;

  return (
    <div className="border-b border-gray-100 px-3 py-2 dark:border-gray-700">
      <div className="scrollbar-hide flex items-center gap-2 overflow-x-auto">
        {suggestions.map((s, i) => (
          <button
            key={i}
            onClick={() => onSuggestionClick(s.prompt)}
            className="flex items-center gap-1.5 rounded-full bg-blue-50 px-3 py-1.5 text-xs font-medium whitespace-nowrap text-blue-700 transition-colors hover:bg-blue-100 dark:bg-blue-900/20 dark:text-blue-300 dark:hover:bg-blue-900/40"
            title={s.prompt}
          >
            <span>{s.icon}</span>
            <span>{s.labelZh}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
