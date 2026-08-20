import React from 'react';
import { Copy } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { DslBlockV3, ModelFieldDefinition } from '../types';
import { SchemaInspector } from '../inspector/SchemaInspector';

interface InspectorHostProps {
  selectedBlock: DslBlockV3 | null;
  modelFields: ModelFieldDefinition[];
  editablePropertyPaths?: string[];
  canDuplicateBlock?: boolean;
  onDuplicateBlock?: () => void;
  onChange: (path: string, value: unknown) => void;
}

export function InspectorHost({
  selectedBlock,
  modelFields,
  editablePropertyPaths,
  canDuplicateBlock = false,
  onDuplicateBlock,
  onChange,
}: InspectorHostProps) {
  const { locale } = useI18n();
  return (
    <aside
      className="flex max-h-[360px] w-full shrink-0 flex-col border-t border-slate-200 bg-white xl:max-h-none xl:w-[340px] xl:border-l xl:border-t-0"
      data-testid="unified-inspector-host"
    >
      {selectedBlock ? (
        <div className="flex items-center justify-between gap-3 border-b border-slate-200 px-4 py-2.5">
          <div className="min-w-0">
            <p className="truncate text-xs font-medium text-slate-700">
              {resolveDesignerText(DESIGNER_I18N.unified.duplicateBlock, locale)}
            </p>
            <p className="truncate text-[11px] text-slate-500">
              {resolveDesignerText(DESIGNER_I18N.unified.duplicateBlockHint, locale)}
            </p>
          </div>
          <button
            type="button"
            data-testid="designer-duplicate-block"
            disabled={!canDuplicateBlock}
            onClick={onDuplicateBlock}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-medium text-slate-700 transition hover:border-blue-300 hover:text-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Copy className="h-3.5 w-3.5" aria-hidden="true" />
            {resolveDesignerText(DESIGNER_I18N.unified.duplicateBlock, locale)}
          </button>
        </div>
      ) : null}
      <SchemaInspector
        block={selectedBlock}
        modelFields={modelFields}
        editablePropertyPaths={editablePropertyPaths}
        onChange={onChange}
      />
    </aside>
  );
}
