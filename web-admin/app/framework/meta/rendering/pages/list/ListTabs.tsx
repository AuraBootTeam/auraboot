/**
 * ListTabs — Extracted list tabs rendering from ListPageContent.
 *
 * Renders the tab navigation bar for list pages with status-based filtering.
 * Behavior-preserving extraction — no functional changes.
 */

import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface ListTabsProps {
  tabs: any[];
  activeTab: string;
  onTabChange: (tabKey: string) => void;
  locale: string;
  t: (key: string) => string;
}

export function ListTabs({ tabs, activeTab, onTabChange, locale, t }: ListTabsProps) {
  if (!tabs || tabs.length === 0) return null;

  return (
    <div className="border-border border-b px-6">
      <nav className="-mb-px flex space-x-6" aria-label="Tabs">
        {tabs.map((tab: any) => (
          <button
            key={tab.key}
            data-testid={`tab-${tab.key}`}
            onClick={() => onTabChange(tab.key)}
            className={`border-b-2 px-1 py-3 text-sm font-medium whitespace-nowrap ${
              activeTab === tab.key
                ? 'border-accent text-accent'
                : 'text-text-2 hover:border-border-strong hover:text-text-2 border-transparent'
            }`}
          >
            {typeof tab.label === 'string' ? tab.label : getLocalizedText(tab.label, locale, t)}
            {(tab.count != null || tab.badge != null) && (
              <span
                className={`ml-1.5 text-xs ${
                  activeTab === tab.key ? 'text-blue-400' : 'text-text-3'
                }`}
              >
                {tab.count ?? tab.badge}
              </span>
            )}
          </button>
        ))}
      </nav>
    </div>
  );
}
