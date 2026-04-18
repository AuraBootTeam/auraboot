import React from 'react';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

export interface ListConfigPanelProps {
  schema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  onSave?: (schema: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
}

/**
 * Structured config panel for kind=list pages.
 * P2B stub — full implementation in P3 (Columns/Filters/Toolbar/Behavior tabs).
 */
export const ListConfigPanel: React.FC<ListConfigPanelProps> = ({ schema, modelCode }) => {
  return (
    <div
      className="flex h-full flex-1 items-center justify-center bg-gray-50"
      data-testid="list-config-panel-stub"
    >
      <div className="max-w-md text-center p-8">
        <div className="text-5xl mb-4">📋</div>
        <h2 className="text-lg font-medium text-gray-800 mb-2">List Config Panel</h2>
        <p className="text-sm text-gray-500 mb-4">
          此页面 kind=list 将由结构化配置面板（Columns / Filters / Toolbar / Behavior）驱动。
        </p>
        <p className="text-xs text-gray-400">P3 阶段将完整实现，目前为占位组件。</p>
        <div className="mt-6 rounded bg-white border border-gray-200 p-3 text-xs text-left font-mono">
          <div>
            schema.kind: <span className="text-blue-600">{schema.kind}</span>
          </div>
          <div>
            schema.modelCode:{' '}
            <span className="text-blue-600">{schema.modelCode ?? modelCode ?? '(未设置)'}</span>
          </div>
          <div>
            blocks.length:{' '}
            <span className="text-blue-600">{(schema.blocks ?? []).length}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ListConfigPanel;
