/**
 * TabsBlockRenderer - 标签页块渲染器
 */

import React, { useState } from 'react';
import type { BlockConfig, DetailTabConfig } from '~/meta/schemas/types';
import type { SchemaRuntime } from '~/meta/runtime/schema-runtime';
import { BlockRenderer } from '~/meta/rendering/BlockRenderer';
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
      <div className="border-b border-gray-200">
        <nav className="-mb-px flex space-x-8">
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
                    ? 'border-blue-500 text-blue-600'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
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
