// web-admin/app/smart/automation/components/TemplatePreviewDialog.tsx
import React from 'react';
import { FlowDesigner } from '~/flow-designer-sdk';
import { automationNodes, automationCategoryOrder } from '../nodes';
import type { AutomationTemplate } from '../templates/automationTemplates';

export interface TemplatePreviewDialogProps {
  template: AutomationTemplate;
  onClose: () => void;
  onUseTemplate: () => void;
}

export function TemplatePreviewDialog({
  template,
  onClose,
  onUseTemplate,
}: TemplatePreviewDialogProps) {
  const config = {
    nodeDefinitions: automationNodes,
    categoryOrder: automationCategoryOrder,
    showMinimap: false,
    showControls: true,
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />

      <div className="relative z-10 mx-4 flex h-[80vh] w-full max-w-5xl flex-col rounded-xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex shrink-0 items-center justify-between border-b border-gray-200 px-6 py-4">
          <div>
            <h3 className="text-lg font-semibold text-gray-900">{template.name}</h3>
            <p className="mt-0.5 text-sm text-gray-500">{template.description}</p>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={onUseTemplate}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              data-testid="btn-preview-use-template"
            >
              Use This Template
            </button>
            <button
              onClick={onClose}
              className="rounded-lg p-2 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
              aria-label="Close preview"
            >
              <svg
                className="h-5 w-5"
                fill="none"
                viewBox="0 0 24 24"
                strokeWidth={2}
                stroke="currentColor"
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* Flow preview (read-only) */}
        <div className="flex-1">
          <FlowDesigner
            config={config}
            initialData={template.flowData}
            title={`Preview: ${template.name}`}
            readOnly
          />
        </div>
      </div>
    </div>
  );
}

export default TemplatePreviewDialog;
