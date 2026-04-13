/**
 * NbaSuggestionBar - AI Next Best Action suggestion bar for detail pages.
 * Shows 1-3 contextual action suggestions above the tab bar.
 *
 * @since 6.3.0
 */

import { useState, useEffect } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface NbaSuggestion {
  title: string;
  description: string;
  priority: string; // HIGH, MEDIUM, LOW
  category: string; // FOLLOW_UP, DATA_QUALITY, STAGE_ADVANCE, RISK_ALERT, OPPORTUNITY
}

interface NbaSuggestionBarProps {
  modelCode: string;
  recordPid: string;
  token?: string;
}

const CATEGORY_ICONS: Record<string, string> = {
  FOLLOW_UP: '\u{1F4DE}', // phone
  DATA_QUALITY: '\u{1F4DD}', // memo
  STAGE_ADVANCE: '\u{1F680}', // rocket
  RISK_ALERT: '\u{26A0}', // warning
  OPPORTUNITY: '\u{2B50}', // star
};

const PRIORITY_STYLES: Record<string, string> = {
  HIGH: 'border-l-red-500 bg-red-50',
  MEDIUM: 'border-l-amber-500 bg-amber-50',
  LOW: 'border-l-blue-500 bg-blue-50',
};

export function NbaSuggestionBar({ modelCode, recordPid, token }: NbaSuggestionBarProps) {
  const [suggestions, setSuggestions] = useState<NbaSuggestion[]>([]);
  const [dismissed, setDismissed] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!modelCode || !recordPid) {
      setLoading(false);
      return;
    }

    const load = async () => {
      try {
        const result = await fetchResult<NbaSuggestion[]>('/api/ai/nba', {
          method: 'get',
          params: { modelCode, recordPid },
          token,
        });
        if (
          ResultHelper.isSuccess(result) &&
          Array.isArray(result.data) &&
          result.data.length > 0
        ) {
          setSuggestions(result.data);
        }
      } catch {
        // Silently ignore — NBA is optional enhancement
      } finally {
        setLoading(false);
      }
    };

    load();
  }, [modelCode, recordPid, token]);

  if (loading || dismissed || suggestions.length === 0) return null;

  return (
    <div className="border-b border-blue-100 bg-gradient-to-r from-blue-50 to-indigo-50 px-6 py-3">
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-1.5 text-xs font-medium text-blue-700">
          <svg
            className="h-3.5 w-3.5"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            viewBox="0 0 24 24"
          >
            <path d="M9.663 17h4.674M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
          </svg>
          AI Suggested Actions
        </div>
        <button
          onClick={() => setDismissed(true)}
          className="p-0.5 text-gray-400 hover:text-gray-600"
          title="Dismiss"
        >
          <svg
            className="h-3.5 w-3.5"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            viewBox="0 0 24 24"
          >
            <path d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
      <div className="flex gap-3 overflow-x-auto">
        {suggestions.map((s, i) => (
          <div
            key={i}
            className={`max-w-[340px] min-w-[200px] flex-1 rounded-md border-l-3 px-3 py-2 ${
              PRIORITY_STYLES[s.priority] || PRIORITY_STYLES.MEDIUM
            }`}
          >
            <div className="flex items-center gap-1.5 text-sm font-medium text-gray-800">
              <span>{CATEGORY_ICONS[s.category] || '\u{1F4A1}'}</span>
              {s.title}
            </div>
            <p className="mt-0.5 line-clamp-2 text-xs text-gray-600">{s.description}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
