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
      <div className="border-border border-b">
        <nav className="-mb-px flex space-x-8" role="tablist" aria-label="Tabs">
          {tabs.map((tab, index) => {
            const label = getLocalizedText(tab.label, locale, t);
            return (
              <button
                key={tab.key || index}
                role="tab"
                aria-selected={activeTab === index}
                onClick={() => setActiveTab(index)}
                className={`border-b-2 px-1 py-2 text-sm font-medium ${
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
