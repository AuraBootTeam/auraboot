import React, { useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router';
import type { CrudTemplateConfig, TemplateGenerationResult } from '~/types/model';
import { templateService } from '~/shared/services/templateService';

interface CrudTemplateWizardProps {
  modelCode: string;
  modelName: string;
  fields: any[];
  onClose: () => void;
  onComplete: () => void;
}

export function CrudTemplateWizard({
  modelCode,
  modelName,
  onClose,
  onComplete,
}: CrudTemplateWizardProps) {
  const navigate = useNavigate();
  const [config, setConfig] = useState<CrudTemplateConfig>({
    generateList: true,
    generateForm: true,
    generateDetail: true,
    createMenu: false,
    createPermissions: false,
    assignRoles: false,
    openDesignerAfterGenerate: true,
    enableExport: false,
    enableImport: false,
    menuName: `${modelName}管理`,
    menuIcon: 'DocumentTextIcon',
    defaultRoles: [],
    listColumns: [],
    formFields: [],
    detailFields: [],
  });
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [generationError, setGenerationError] = useState<string | null>(null);
  const [generationResult, setGenerationResult] = useState<TemplateGenerationResult | null>(null);

  const generatedKinds = useMemo(
    () =>
      [
        config.generateList ? '列表页' : null,
        config.generateForm ? '表单页' : null,
        config.generateDetail ? '详情页' : null,
      ].filter(Boolean),
    [config.generateDetail, config.generateForm, config.generateList],
  );

  const updateConfig = useCallback((field: keyof CrudTemplateConfig, value: unknown) => {
    setConfig((prev) => ({ ...prev, [field]: value }));
  }, []);

  const canGenerate = config.generateList || config.generateForm || config.generateDetail;

  const getDesignerTarget = useCallback((result: TemplateGenerationResult | null) => {
    if (!result?.generatedResources?.pages?.length) return null;
    const pages = result.generatedResources.pages;
    return (
      pages.find((page) => page.kind === 'detail' || page.pageType === 'detail') ||
      pages.find((page) => page.kind === 'form' || page.pageType === 'form') ||
      pages.find((page) => page.kind === 'list' || page.pageType === 'list') ||
      pages[0]
    );
  }, []);

  const handleGenerate = useCallback(async () => {
    if (!canGenerate) return;

    setGenerating(true);
    setGenerationError(null);

    try {
      const result = await templateService.generateCrudTemplate(modelCode, config);
      setGenerationResult(result);

      if (config.openDesignerAfterGenerate) {
        const target = getDesignerTarget(result);
        const designerPageId = target?.pid || target?.id;
        if (designerPageId) {
          onComplete();
          onClose();
          navigate(`/page-designer/${designerPageId}`);
          return;
        }
      }
    } catch (error) {
      console.error('Failed to generate CRUD pages:', error);
      setGenerationError(error instanceof Error ? error.message : '生成失败，请重试');
    } finally {
      setGenerating(false);
    }
  }, [canGenerate, config, getDesignerTarget, modelCode, navigate, onClose, onComplete]);

  const handleComplete = useCallback(() => {
    onComplete();
    onClose();
  }, [onClose, onComplete]);

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto" data-testid="crud-template-dialog">
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={onClose} />

      <div className="flex min-h-screen items-center justify-center p-4">
        <div
          className="relative w-full max-w-3xl rounded-2xl bg-white shadow-xl"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="border-b border-gray-200 px-6 py-5">
            <h2 className="text-lg font-semibold text-gray-900">生成基础 CRUD 页面</h2>
            <p className="mt-1 text-sm text-gray-500">
              为模型 <span className="font-mono text-blue-600">{modelName}</span>{' '}
              生成页面骨架，后续可继续进入设计器调整。
            </p>
          </div>

          <div className="space-y-6 px-6 py-6">
            {generationError && (
              <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                {generationError}
              </div>
            )}

            {generationResult ? (
              <div className="space-y-5">
                <div className="rounded-xl border border-green-200 bg-green-50 px-4 py-4">
                  <div className="text-sm font-medium text-green-800">页面生成成功</div>
                  <div className="mt-1 text-sm text-green-700">
                    已生成 {generationResult.generatedResources.pages.length} 个页面资源。
                  </div>
                </div>

                <div className="space-y-2">
                  {generationResult.generatedResources.pages.map((page) => (
                    <div
                      key={page.id}
                      className="flex items-center justify-between rounded-xl border border-gray-200 bg-gray-50 px-4 py-3"
                    >
                      <div>
                        <div className="text-sm font-medium text-gray-900">{page.pageName}</div>
                        <div className="mt-1 text-xs text-gray-500">{page.route}</div>
                      </div>
                      <button
                        onClick={() => navigate(`/page-designer/${page.pid || page.id}`)}
                        className="rounded-md border border-blue-300 px-3 py-2 text-sm text-blue-700 hover:bg-blue-50"
                      >
                        编辑设计
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <>
                <div>
                  <h3 className="mb-3 text-sm font-medium text-gray-900">生成内容</h3>
                  <div className="space-y-3">
                    <label className="flex cursor-pointer items-start rounded-xl border border-gray-200 p-4 hover:bg-gray-50">
                      <input
                        data-testid="crud-generate-list"
                        type="checkbox"
                        checked={config.generateList}
                        onChange={(e) => updateConfig('generateList', e.target.checked)}
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <div className="ml-3">
                        <div className="text-sm font-medium text-gray-900">列表页</div>
                        <div className="text-sm text-gray-500">默认带筛选、表格、操作列</div>
                      </div>
                    </label>

                    <label className="flex cursor-pointer items-start rounded-xl border border-gray-200 p-4 hover:bg-gray-50">
                      <input
                        data-testid="crud-generate-form"
                        type="checkbox"
                        checked={config.generateForm}
                        onChange={(e) => updateConfig('generateForm', e.target.checked)}
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <div className="ml-3">
                        <div className="text-sm font-medium text-gray-900">表单页</div>
                        <div className="text-sm text-gray-500">默认带可编辑字段与保存动作</div>
                      </div>
                    </label>

                    <label className="flex cursor-pointer items-start rounded-xl border border-gray-200 p-4 hover:bg-gray-50">
                      <input
                        data-testid="crud-generate-detail"
                        type="checkbox"
                        checked={config.generateDetail}
                        onChange={(e) => updateConfig('generateDetail', e.target.checked)}
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <div className="ml-3">
                        <div className="text-sm font-medium text-gray-900">详情页</div>
                        <div className="text-sm text-gray-500">默认展示所有可见字段</div>
                      </div>
                    </label>
                  </div>
                </div>

                <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-600">
                  将生成：
                  <span className="ml-1 font-medium text-gray-900">
                    {generatedKinds.length > 0 ? generatedKinds.join('、') : '请至少选择一个页面类型'}
                  </span>
                  <span className="ml-1">，并自动发布。</span>
                </div>

                <div className="space-y-3">
                  <label className="flex items-center">
                    <input
                      data-testid="crud-open-designer"
                      type="checkbox"
                      checked={config.openDesignerAfterGenerate ?? true}
                      onChange={(e) => updateConfig('openDesignerAfterGenerate', e.target.checked)}
                      className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="ml-2 text-sm text-gray-700">生成后自动打开页面设计</span>
                  </label>

                  <button
                    type="button"
                    onClick={() => setShowAdvanced((prev) => !prev)}
                    className="text-sm text-blue-600 hover:text-blue-800"
                  >
                    {showAdvanced ? '收起更多选项' : '更多选项'}
                  </button>

                  {showAdvanced && (
                    <div className="space-y-4 rounded-xl border border-gray-200 bg-white p-4">
                      <label className="flex items-center">
                        <input
                          data-testid="crud-enable-export"
                          type="checkbox"
                          checked={config.enableExport}
                          onChange={(e) => updateConfig('enableExport', e.target.checked)}
                          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="ml-2 text-sm text-gray-700">列表页启用导出</span>
                      </label>

                      <label className="flex items-center">
                        <input
                          data-testid="crud-enable-import"
                          type="checkbox"
                          checked={config.enableImport}
                          onChange={(e) => updateConfig('enableImport', e.target.checked)}
                          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="ml-2 text-sm text-gray-700">列表页启用导入</span>
                      </label>

                      <label className="flex items-center">
                        <input
                          data-testid="crud-create-menu"
                          type="checkbox"
                          checked={config.createMenu}
                          onChange={(e) => updateConfig('createMenu', e.target.checked)}
                          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="ml-2 text-sm text-gray-700">同时创建菜单</span>
                      </label>

                      {config.createMenu && (
                        <input
                          type="text"
                          value={config.menuName || ''}
                          onChange={(e) => updateConfig('menuName', e.target.value)}
                          placeholder={`${modelName}管理`}
                          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                        />
                      )}

                      <label className="flex items-center">
                        <input
                          data-testid="crud-create-permissions"
                          type="checkbox"
                          checked={config.createPermissions}
                          onChange={(e) => updateConfig('createPermissions', e.target.checked)}
                          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="ml-2 text-sm text-gray-700">同时创建权限</span>
                      </label>

                      <label className="flex items-center">
                        <input
                          data-testid="crud-assign-roles"
                          type="checkbox"
                          checked={config.assignRoles}
                          onChange={(e) => updateConfig('assignRoles', e.target.checked)}
                          disabled={!config.createPermissions}
                          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
                        />
                        <span className="ml-2 text-sm text-gray-700">同时分配给当前角色</span>
                      </label>
                    </div>
                  )}
                </div>

                <div className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-700">
                  默认只生成页面资源，不再自动改菜单、权限和角色。
                </div>
              </>
            )}
          </div>

          <div className="flex justify-between border-t border-gray-200 px-6 py-4">
            <button
              onClick={onClose}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            >
              取消
            </button>

            {generationResult ? (
              <button
                data-testid="crud-complete"
                onClick={handleComplete}
                className="rounded-md bg-green-600 px-4 py-2 text-white hover:bg-green-700"
              >
                完成
              </button>
            ) : (
              <button
                data-testid="crud-generate-submit"
                onClick={handleGenerate}
                disabled={!canGenerate || generating}
                className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {generating ? '生成中...' : '生成页面'}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
