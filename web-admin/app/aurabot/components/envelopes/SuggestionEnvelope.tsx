import type { ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type {
  SuggestionEnvelope as SuggestionEnvelopeData,
} from '../../types/envelope';
import type { SkillSuggestion } from '../../types/skill';

export interface SuggestionEnvelopeProps {
  envelope: SuggestionEnvelopeData;
  onPick?: (suggestion: SkillSuggestion) => void;
}

export function SuggestionEnvelope({
  envelope,
  onPick,
}: SuggestionEnvelopeProps): ReactElement {
  const { t } = useI18n();
  const title = t('aurabot.shell.suggestion.title', undefined, 'Suggestions');

  return (
    <div data-aurabot-envelope="suggestion" className="text-sm">
      <div className="mb-1 text-xs font-medium uppercase text-gray-500 dark:text-gray-400">
        {title}
      </div>
      <div className="flex flex-wrap gap-2">
        {envelope.suggestions.map((suggestion, idx) => (
          <button
            key={`${suggestion.skillName}-${idx}`}
            type="button"
            onClick={() => onPick?.(suggestion)}
            data-aurabot-suggestion={suggestion.skillName}
            className="rounded-full border border-blue-200 bg-blue-50 px-3 py-1 text-xs font-medium text-blue-700 hover:bg-blue-100 dark:border-blue-800 dark:bg-blue-950/40 dark:text-blue-200 dark:hover:bg-blue-900/40"
          >
            {suggestion.label}
          </button>
        ))}
      </div>
    </div>
  );
}
