/**
 * NewPageWizard Component
 *
 * Step-by-step wizard for creating a new page.
 *
 * @since 3.2.0
 */

import React, { useState, useEffect, useCallback } from 'react';
import type { PageMode, PageTemplate, CreatePageRequest } from '../../../services/page-manager';
import { pageManagerService, PAGE_MODE_INFO } from '../../../services/page-manager';

/**
 * NewPageWizard props
 */
export interface NewPageWizardProps {
  /** Whether the wizard is open */
  isOpen: boolean;
  /** Close callback */
  onClose: () => void;
  /** Success callback with created page ID */
  onSuccess: (pageId: string) => void;
  /** Pre-selected mode */
  defaultMode?: PageMode;
}

type Step = 'mode' | 'template' | 'info';

/**
 * NewPageWizard component
 */
export const NewPageWizard: React.FC<NewPageWizardProps> = ({
  isOpen,
  onClose,
  onSuccess,
  defaultMode,
}) => {
  const [step, setStep] = useState<Step>('mode');
  const [selectedMode, setSelectedMode] = useState<PageMode | null>(defaultMode || null);
  const [selectedTemplate, setSelectedTemplate] = useState<PageTemplate | null>(null);
  const [templates, setTemplates] = useState<PageTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);

  // Form data
  const [title, setTitle] = useState('');
  const [pageKey, setPageKey] = useState('');
  const [description, setDescription] = useState('');
  const [viewModelCode, setViewModelCode] = useState('');
  const [layoutPreset, setLayoutPreset] = useState<'cols-2' | 'cols-3' | 'cols-4'>('cols-2');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  // Reset state when opening
  useEffect(() => {
    if (isOpen) {
      setStep(defaultMode ? 'template' : 'mode');
      setSelectedMode(defaultMode || null);
      setSelectedTemplate(null);
      setTitle('');
      setPageKey('');
      setDescription('');
      setViewModelCode('');
      setLayoutPreset('cols-2');
      setError(null);
      setFieldErrors({});
    }
  }, [isOpen, defaultMode]);

  // Load templates when mode is selected
  useEffect(() => {
    if (selectedMode) {
      setLoading(true);
      pageManagerService
        .getTemplates(selectedMode)
        .then(setTemplates)
        .finally(() => setLoading(false));
    }
  }, [selectedMode]);

  const handleModeSelect = (mode: PageMode) => {
    setSelectedMode(mode);
    setSelectedTemplate(null);
    setStep('template');
  };

  const handleTemplateSelect = (template: PageTemplate) => {
    setSelectedTemplate(template);
    setStep('info');
  };

  const handleSkipTemplate = () => {
    setSelectedTemplate(null);
    setStep('info');
  };

  const handleBack = () => {
    if (step === 'info') {
      setStep('template');
    } else if (step === 'template') {
      setStep('mode');
    }
  };

  const handleCreate = useCallback(async () => {
    if (!selectedMode || !title.trim()) return;

    setCreating(true);
    setError(null);
    setFieldErrors({});
    try {
      const request: CreatePageRequest = {
        title: title.trim(),
        pageKey: pageKey.trim() || undefined,
        description: description.trim() || undefined,
        mode: selectedMode,
        viewModelCode: viewModelCode.trim() || undefined,
        templateId: selectedTemplate?.id,
        layoutPreset: selectedMode === 'form' ? layoutPreset : undefined,
      };

      const page = await pageManagerService.createPage(request);
      onSuccess(page.id);
    } catch (err) {
      console.error('Failed to create page:', err);
      // Parse error message for field-specific errors
      const message = err instanceof Error ? err.message : '创建页面失败';

      // Try to extract field errors from the message (format: "field1: error1, field2: error2")
      const fieldErrorMatch = message.match(/\{([^}]+)\}/);
      if (fieldErrorMatch) {
        try {
          const parsed = JSON.parse(`{${fieldErrorMatch[1].replace(/(\w+):/g, '"$1":')}}`);
          setFieldErrors(parsed);
        } catch {
          // Ignore parse errors
        }
      }

      setError(message);
    } finally {
      setCreating(false);
    }
  }, [
    selectedMode,
    title,
    pageKey,
    description,
    viewModelCode,
    selectedTemplate,
    layoutPreset,
    onSuccess,
  ]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />

      {/* Dialog */}
      <div className="relative max-h-[90vh] w-full max-w-2xl overflow-hidden rounded-xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold text-gray-900">新建页面</h2>
            {/* Step indicator */}
            <div className="ml-4 flex items-center gap-2">
              {(['mode', 'template', 'info'] as const).map((s, i) => (
                <React.Fragment key={s}>
                  {i > 0 && <div className="h-px w-6 bg-gray-300" />}
                  <div
                    className={`flex h-6 w-6 items-center justify-center rounded-full text-xs font-medium ${
                      step === s
                        ? 'bg-blue-600 text-white'
                        : i < ['mode', 'template', 'info'].indexOf(step)
                          ? 'bg-green-500 text-white'
                          : 'bg-gray-200 text-gray-500'
                    }`}
                  >
                    {i + 1}
                  </div>
                </React.Fragment>
              ))}
            </div>
          </div>
          <button onClick={onClose} className="rounded-lg p-1.5 text-gray-500 hover:bg-gray-100">
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="overflow-y-auto p-6" style={{ maxHeight: 'calc(90vh - 140px)' }}>
          {step === 'mode' && (
            <div>
              <h3 className="mb-2 text-base font-medium text-gray-900">选择页面类型</h3>
              <p className="mb-6 text-sm text-gray-500">
                不同的页面类型适用于不同的场景，请根据需求选择
              </p>
              <div className="grid grid-cols-3 gap-4">
                {(
                  Object.entries(PAGE_MODE_INFO) as [PageMode, (typeof PAGE_MODE_INFO)['grid']][]
                ).map(([mode, info]) => (
                  <button
                    key={mode}
                    onClick={() => handleModeSelect(mode)}
                    className={`rounded-lg border-2 p-4 text-left transition-all hover:border-blue-300 hover:bg-blue-50 ${
                      selectedMode === mode ? 'border-blue-500 bg-blue-50' : 'border-gray-200'
                    }`}
                  >
                    <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-gray-100">
                      <svg
                        className="h-6 w-6 text-gray-600"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={1.5}
                          d={info.icon}
                        />
                      </svg>
                    </div>
                    <h4 className="font-medium text-gray-900">{info.label}</h4>
                    <p className="mt-1 text-xs text-gray-500">{info.description}</p>
                  </button>
                ))}
              </div>
            </div>
          )}

          {step === 'template' && (
            <div>
              <h3 className="mb-2 text-base font-medium text-gray-900">选择模板</h3>
              <p className="mb-6 text-sm text-gray-500">选择一个模板快速开始，或从空白页面开始</p>

              {loading ? (
                <div className="grid grid-cols-3 gap-4">
                  {Array.from({ length: 3 }).map((_, i) => (
                    <div key={i} className="animate-pulse rounded-lg border border-gray-200 p-4">
                      <div className="mb-3 h-20 rounded bg-gray-200" />
                      <div className="mb-2 h-4 w-3/4 rounded bg-gray-200" />
                      <div className="h-3 w-1/2 rounded bg-gray-200" />
                    </div>
                  ))}
                </div>
              ) : (
                <div className="grid grid-cols-3 gap-4">
                  {/* Blank option */}
                  <button
                    onClick={handleSkipTemplate}
                    className="rounded-lg border-2 border-dashed border-gray-300 p-4 text-left transition-all hover:border-blue-300 hover:bg-blue-50"
                  >
                    <div className="mb-3 flex h-20 items-center justify-center">
                      <svg
                        className="h-10 w-10 text-gray-400"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={1.5}
                          d="M12 4v16m8-8H4"
                        />
                      </svg>
                    </div>
                    <h4 className="font-medium text-gray-900">空白页面</h4>
                    <p className="mt-1 text-xs text-gray-500">从零开始设计</p>
                  </button>

                  {/* Templates */}
                  {templates.map((template) => (
                    <button
                      key={template.id}
                      onClick={() => handleTemplateSelect(template)}
                      className={`rounded-lg border-2 p-4 text-left transition-all hover:border-blue-300 hover:bg-blue-50 ${
                        selectedTemplate?.id === template.id
                          ? 'border-blue-500 bg-blue-50'
                          : 'border-gray-200'
                      }`}
                    >
                      <div className="mb-3 flex h-20 items-center justify-center rounded bg-gray-100">
                        {template.thumbnail ? (
                          <img
                            src={template.thumbnail}
                            alt={template.name}
                            className="h-full w-full rounded object-cover"
                          />
                        ) : (
                          <svg
                            className="h-10 w-10 text-gray-400"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={1.5}
                              d={PAGE_MODE_INFO[template.mode].icon}
                            />
                          </svg>
                        )}
                      </div>
                      <h4 className="font-medium text-gray-900">{template.name}</h4>
                      <p className="mt-1 line-clamp-2 text-xs text-gray-500">
                        {template.description}
                      </p>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}

          {step === 'info' && (
            <div>
              <h3 className="mb-2 text-base font-medium text-gray-900">页面信息</h3>
              <p className="mb-6 text-sm text-gray-500">填写页面的基本信息</p>

              <div className="space-y-4">
                {/* Title */}
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    页面标题 <span className="text-red-500">*</span>
                  </label>
                  <input
                    type="text"
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    placeholder="输入页面标题"
                    className={`w-full rounded-lg border px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                      fieldErrors.title ? 'border-red-300 bg-red-50' : 'border-gray-300'
                    }`}
                    autoFocus
                  />
                  {fieldErrors.title && (
                    <p className="mt-1 text-xs text-red-500">{fieldErrors.title}</p>
                  )}
                </div>

                {/* Page Key */}
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    页面标识 <span className="font-normal text-gray-400">(可选)</span>
                  </label>
                  <input
                    type="text"
                    value={pageKey}
                    onChange={(e) => setPageKey(e.target.value)}
                    placeholder="例如: customer_list (留空自动生成)"
                    className={`w-full rounded-lg border px-3 py-2 font-mono text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                      fieldErrors.pageKey ? 'border-red-300 bg-red-50' : 'border-gray-300'
                    }`}
                  />
                  <p
                    className={`mt-1 text-xs ${fieldErrors.pageKey ? 'text-red-500' : 'text-gray-400'}`}
                  >
                    {fieldErrors.pageKey || '以字母开头，只能包含字母、数字、下划线和连字符'}
                  </p>
                </div>

                {/* Description */}
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">页面描述</label>
                  <textarea
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="输入页面描述（可选）"
                    rows={3}
                    className="w-full resize-none rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                </div>

                {/* ViewModel Code */}
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    关联视图模型
                  </label>
                  <input
                    type="text"
                    value={viewModelCode}
                    onChange={(e) => setViewModelCode(e.target.value)}
                    placeholder="输入视图模型代码（可选）"
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                  <p className="mt-1 text-xs text-gray-400">关联后可从视图模型获取字段列表</p>
                </div>

                {/* Layout preset for form mode */}
                {selectedMode === 'form' && (
                  <div>
                    <label className="mb-2 block text-sm font-medium text-gray-700">表单列数</label>
                    <div className="flex items-center gap-3">
                      {(['cols-2', 'cols-3', 'cols-4'] as const).map((preset) => (
                        <button
                          key={preset}
                          onClick={() => setLayoutPreset(preset)}
                          className={`rounded-lg border px-4 py-2 text-sm ${
                            layoutPreset === preset
                              ? 'border-blue-500 bg-blue-50 text-blue-700'
                              : 'border-gray-300 text-gray-700 hover:bg-gray-50'
                          }`}
                        >
                          {preset === 'cols-2' && '2列'}
                          {preset === 'cols-3' && '3列'}
                          {preset === 'cols-4' && '4列'}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {/* Summary */}
                <div className="mt-6 rounded-lg bg-gray-50 p-4">
                  <h4 className="mb-2 text-sm font-medium text-gray-700">创建摘要</h4>
                  <div className="space-y-1 text-sm text-gray-600">
                    <div className="flex items-center gap-2">
                      <span className="text-gray-400">页面类型:</span>
                      <span>{selectedMode && PAGE_MODE_INFO[selectedMode].label}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-gray-400">模板:</span>
                      <span>{selectedTemplate?.name || '空白页面'}</span>
                    </div>
                    {viewModelCode && (
                      <div className="flex items-center gap-2">
                        <span className="text-gray-400">视图模型:</span>
                        <span className="font-mono text-xs">{viewModelCode}</span>
                      </div>
                    )}
                  </div>
                </div>

                {/* Error message */}
                {error && (
                  <div className="mt-4 rounded-lg border border-red-200 bg-red-50 p-4">
                    <div className="flex items-start gap-3">
                      <svg
                        className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                        />
                      </svg>
                      <div>
                        <h4 className="text-sm font-medium text-red-800">创建失败</h4>
                        <p className="mt-1 text-sm text-red-600">{error}</p>
                        <p className="mt-2 text-xs text-red-500">请检查后端服务是否正常运行</p>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-gray-200 bg-gray-50 px-6 py-4">
          <div>
            {step !== 'mode' && (
              <button
                onClick={handleBack}
                className="rounded-lg px-4 py-2 text-sm text-gray-700 hover:bg-gray-200"
              >
                上一步
              </button>
            )}
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={onClose}
              className="rounded-lg px-4 py-2 text-sm text-gray-700 hover:bg-gray-200"
            >
              取消
            </button>
            {step === 'info' && (
              <button
                onClick={handleCreate}
                disabled={!title.trim() || creating}
                className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {creating ? (
                  <>
                    <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                      <circle
                        className="opacity-25"
                        cx="12"
                        cy="12"
                        r="10"
                        stroke="currentColor"
                        strokeWidth="4"
                      />
                      <path
                        className="opacity-75"
                        fill="currentColor"
                        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                      />
                    </svg>
                    创建中...
                  </>
                ) : (
                  '创建页面'
                )}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default NewPageWizard;
