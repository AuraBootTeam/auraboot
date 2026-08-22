/**
 * TabsBlockRenderer - 标签页块渲染器
 */

import React, { useState } from 'react';
import type { BlockConfig, DetailTabConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { BlockRenderer } from '@auraboot/runtime-kernel';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface TabsBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const TabsBlockRenderer: React.FC<TabsBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const tabs = (block.tabs || []) as DetailTabConfig[];
  const [activeTab, setActiveTab] = useState(0);

  return (
    <div className="tabs-block">
      {/* Tab headers */}
      <div className="border-border min-w-0 border-b">
        <nav
          className="-mb-px flex min-h-11 max-w-full gap-6 overflow-x-auto overscroll-x-contain px-1 [scrollbar-width:thin]"
          role="tablist"
          aria-label={getLocalizedText({ 'zh-CN': '页签', en: 'Tabs' }, locale, t)}
        >
          {tabs.map((tab, index) => {
            const label = getLocalizedText(tab.label, locale, t);
            return (
              <button
                key={tab.key || index}
                role="tab"
                aria-selected={activeTab === index}
                onClick={() => setActiveTab(index)}
                className={`min-h-11 shrink-0 touch-manipulation whitespace-nowrap border-b-2 px-1 py-2 text-sm font-medium ${
                  activeTab === index
                    ? 'border-accent text-accent'
                    : 'text-text-2 hover:border-border-strong hover:text-text-2 border-transparent'
                }`}
              >
                {label}
              </button>
            );
          })}
        </nav>
      </div>

      {/* Tab content */}
      <div className="mt-4">
        {tabs[activeTab]?.blocks?.map((nestedBlock: BlockConfig, index: number) => (
          <BlockRenderer
            key={index}
            block={nestedBlock}
            runtime={runtime}
            areaId={`tab-${activeTab}`}
          />
        ))}
      </div>
    </div>
  );
};

export default TabsBlockRenderer;
