import React from 'react';
import type { DslBlockV3, ModelFieldDefinition } from '../types';
import { SchemaInspector } from '../inspector/SchemaInspector';

interface InspectorHostProps {
  selectedBlock: DslBlockV3 | null;
  modelFields: ModelFieldDefinition[];
  onChange: (path: string, value: unknown) => void;
}

export function InspectorHost({ selectedBlock, modelFields, onChange }: InspectorHostProps) {
  return (
    <aside
      className="flex max-h-[360px] w-full shrink-0 flex-col border-t border-slate-200 bg-white xl:max-h-none xl:w-[340px] xl:border-l xl:border-t-0"
      data-testid="unified-inspector-host"
    >
      <SchemaInspector block={selectedBlock} modelFields={modelFields} onChange={onChange} />
    </aside>
  );
}
