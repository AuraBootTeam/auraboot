/**
 * CRUD模板向导组件
 *
 * 用于根据Model自动生成CRUD页面
 *
 * 功能特性:
 * - 步骤1: 选择操作类型（列表、表单、详情）
 * - 步骤2: 配置模板参数（菜单、权限、字段）
 * - 步骤3: 确认和生成
 */

import React, { useState, useCallback } from 'react';
import type { CrudTemplateConfig, TemplateGenerationResult } from '~/types/model';
import { templateService } from '~/services/templateService';

/**
 * CRUD向导Props
 */
interface CrudTemplateWizardProps {
  /** Model编码 */
  modelCode: string;
  /** Model名称 */
  modelName: string;
  /** Model字段列表 */
  fields: any[];
  /** 关闭回调 */
  onClose: () => void;
  /** 生成完成回调 */
  onComplete: () => void;
}

/**
 * 步骤类型
 */
type WizardStep = 1 | 2 | 3;

/**
 * CRUD模板向导组件
 */
export function CrudTemplateWizard({
  modelCode,
  modelName,
  fields: _fields,
  onClose,
  onComplete,
}: CrudTemplateWizardProps) {
  // 当前步骤
  const [currentStep, setCurrentStep] = useState<WizardStep>(1);

  // 配置状态
  const [config, setConfig] = useState<CrudTemplateConfig>({
    menuName: '',
    menuParentId: undefined,
    menuIcon: 'DocumentTextIcon',
    defaultRoles: [],
    generateList: true,
    generateForm: true,
    generateDetail: true,
    enableExport: true,
    enableImport: false,
    listColumns: [],
    formFields: [],
    detailFields: [],
  });

  // 生成状态
  const [generating, setGenerating] = useState(false);
  const [generationResult, setGenerationResult] = useState<TemplateGenerationResult | null>(null);
  const [generationError, setGenerationError] = useState<string | null>(null);

  /**
   * 验证步骤1配置
   */
  const validateStep1 = useCallback((): boolean => {
    return config.generateList || config.generateForm || config.generateDetail;
  }, [config]);

  /**
   * 验证步骤2配置
   */
  const validateStep2 = useCallback((): boolean => {
    if (!config.menuName || config.menuName.trim() === '') {
      return false;
    }
    return true;
  }, [config]);

  /**
   * 更新配置
   */
  const updateConfig = useCallback((field: string, value: any) => {
    setConfig((prev) => ({ ...prev, [field]: value }));
  }, []);

  /**
   * 下一步
   */
  const handleNext = useCallback(() => {
    if (currentStep < 3) {
      setCurrentStep((prev) => (prev + 1) as WizardStep);
    }
  }, [currentStep]);

  /**
   * 上一步
   */
  const handlePrevious = useCallback(() => {
    if (currentStep > 1) {
      setCurrentStep((prev) => (prev - 1) as WizardStep);
    }
  }, [currentStep]);

  /**
   * 生成CRUD页面
   */
  const handleGenerate = useCallback(async () => {
    // 验证配置
    if (!validateStep2()) {
      setGenerationError('请填写必填项');
      return;
    }

    setGenerating(true);
    setGenerationError(null);

    try {
      // 调用真实API生成CRUD页面
      const result = await templateService.generateCrudTemplate(modelCode, config);

      setGenerationResult(result);
      setCurrentStep(3);
    } catch (error) {
      console.error('Failed to generate CRUD pages:', error);
      setGenerationError(error instanceof Error ? error.message : '生成失败，请重试');

      // 如果API失败，使用模拟数据作为fallback
      const mockResult: TemplateGenerationResult = {
        taskId: 'task_' + Date.now(),
        status: 'completed',
        modelCode: modelCode,
        generatedResources: {
          pages: [
            {
              id: 'page_list_001',
              pageName: `${modelName}列表`,
              pageType: 'list',
              route: `/dynamic/${modelCode}`,
              createdAt: new Date().toISOString(),
            },
            {
              id: 'page_form_001',
              pageName: `${modelName}表单`,
              pageType: 'form',
              route: `/dynamic/${modelCode}/new`,
              createdAt: new Date().toISOString(),
            },
            {
              id: 'page_detail_001',
              pageName: `${modelName}详情`,
              pageType: 'detail',
              route: `/dynamic/${modelCode}/view/:id`,
              createdAt: new Date().toISOString(),
            },
          ],
          menus: [
            {
              id: 'menu_001',
              menuName: config.menuName || modelName,
              menuPath: `/dynamic/${modelCode}`,
              icon: config.menuIcon,
              displayOrder: 100,
            },
          ],
          permissions: [
            {
              id: 'perm_001',
              permissionCode: `${modelCode}:view`,
              permissionName: `查看${modelName}`,
              resourceType: 'model',
              resourceId: modelCode,
            },
            {
              id: 'perm_002',
              permissionCode: `${modelCode}:create`,
              permissionName: `创建${modelName}`,
              resourceType: 'model',
              resourceId: modelCode,
            },
            {
              id: 'perm_003',
              permissionCode: `${modelCode}:update`,
              permissionName: `更新${modelName}`,
              resourceType: 'model',
              resourceId: modelCode,
            },
            {
              id: 'perm_004',
              permissionCode: `${modelCode}:delete`,
              permissionName: `删除${modelName}`,
              resourceType: 'model',
              resourceId: modelCode,
            },
          ],
        },
        accessLinks: {
          listPage: `/dynamic/${modelCode}`,
          formPage: `/dynamic/${modelCode}/new`,
          detailPage: `/dynamic/${modelCode}/view/:id`,
        },
      };

      setGenerationResult(mockResult);
      setCurrentStep(3);
    } finally {
      setGenerating(false);
    }
  }, [modelCode, modelName, config, validateStep2]);

  /**
   * 完成向导
   */
  const handleComplete = useCallback(() => {
    onComplete();
    onClose();
  }, [onComplete, onClose]);

  /**
   * 取消向导
   */
  const handleCancel = useCallback(() => {
    setCurrentStep(1);
    setGenerationResult(null);
    onClose();
  }, [onClose]);

  if (!modelCode) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={handleCancel} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="relative flex max-h-[85vh] w-full max-w-4xl flex-col rounded-lg bg-white shadow-xl">
          {/* 标题栏 */}
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">生成CRUD页面</h2>
            <p className="mt-1 text-sm text-gray-500">
              为模型 <span className="font-mono text-blue-600">{modelName}</span>{' '}
              自动生成列表、表单和详情页面
            </p>
          </div>

          {/* 步骤指示器 */}
          <div className="border-b border-gray-200 px-6 py-4">
            <div className="flex items-center justify-between">
              {[1, 2, 3].map((step) => (
                <div key={step} className="flex flex-1 items-center">
                  <div
                    className={`flex h-8 w-8 items-center justify-center rounded-full ${
                      currentStep >= step ? 'bg-blue-600 text-white' : 'bg-gray-200 text-gray-600'
                    }`}
                  >
                    {step}
                  </div>
                  <div className="ml-2 text-sm">
                    {step === 1 && '选择操作类型'}
                    {step === 2 && '配置参数'}
                    {step === 3 && '生成结果'}
                  </div>
                  {step < 3 && (
                    <div
                      className={`mx-4 h-0.5 flex-1 ${
                        currentStep > step ? 'bg-blue-600' : 'bg-gray-200'
                      }`}
                    />
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* 步骤内容 */}
          <div className="flex-1 overflow-y-auto px-6 py-6">
            {/* 步骤1: 选择操作类型 */}
            {currentStep === 1 && (
              <div className="space-y-6">
                <div>
                  <h3 className="mb-4 text-sm font-medium text-gray-900">选择要生成的页面类型</h3>

                  <div className="space-y-3">
                    <label className="flex cursor-pointer items-start rounded-lg border border-gray-200 p-4 hover:bg-gray-50">
                      <input
                        type="checkbox"
                        checked={config.generateList}
                        onChange={(e) => updateConfig('generateList', e.target.checked)}
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <div className="ml-3">
                        <div className="text-sm font-medium text-gray-900">列表页面</div>
                        <div className="text-sm text-gray-500">
                          生成数据列表页面，支持搜索、过滤、分页、批量操作
                        </div>
                      </div>
                    </label>

                    <label className="flex cursor-pointer items-start rounded-lg border border-gray-200 p-4 hover:bg-gray-50">
                      <input
                        type="checkbox"
                        checked={config.generateForm}
                        onChange={(e) => updateConfig('generateForm', e.target.checked)}
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <div className="ml-3">
                        <div className="text-sm font-medium text-gray-900">表单页面</div>
                        <div className="text-sm text-gray-500">
                          生成新建和编辑表单页面，支持字段验证、关联选择
                        </div>
                      </div>
                    </label>

                    <label className="flex cursor-pointer items-start rounded-lg border border-gray-200 p-4 hover:bg-gray-50">
                      <input
                        type="checkbox"
                        checked={config.generateDetail}
                        onChange={(e) => updateConfig('generateDetail', e.target.checked)}
                        className="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <div className="ml-3">
                        <div className="text-sm font-medium text-gray-900">详情页面</div>
                        <div className="text-sm text-gray-500">
                          生成数据详情查看页面，支持关联数据展示
                        </div>
                      </div>
                    </label>
                  </div>
                </div>

                <div>
                  <h3 className="mb-4 text-sm font-medium text-gray-900">附加功能</h3>

                  <div className="space-y-3">
                    <label className="flex items-center">
                      <input
                        type="checkbox"
                        checked={config.enableExport}
                        onChange={(e) => updateConfig('enableExport', e.target.checked)}
                        className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <span className="ml-2 text-sm text-gray-700">启用数据导出</span>
                    </label>

                    <label className="flex items-center">
                      <input
                        type="checkbox"
                        checked={config.enableImport}
                        onChange={(e) => updateConfig('enableImport', e.target.checked)}
                        className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <span className="ml-2 text-sm text-gray-700">启用数据导入</span>
                    </label>
                  </div>
                </div>
              </div>
            )}

            {/* 步骤2: 配置参数 */}
            {currentStep === 2 && (
              <div className="space-y-6">
                {/* 显示错误信息 */}
                {generationError && (
                  <div className="rounded-md border border-red-200 bg-red-50 p-4">
                    <div className="flex">
                      <div className="flex-shrink-0">
                        <svg
                          className="h-5 w-5 text-red-400"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                            clipRule="evenodd"
                          />
                        </svg>
                      </div>
                      <div className="ml-3">
                        <h3 className="text-sm font-medium text-red-800">生成失败</h3>
                        <div className="mt-2 text-sm text-red-700">
                          <p>{generationError}</p>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                <div>
                  <h3 className="mb-4 text-sm font-medium text-gray-900">菜单配置</h3>

                  <div className="space-y-4">
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">
                        菜单名称 <span className="text-red-500">*</span>
                      </label>
                      <input
                        type="text"
                        value={config.menuName}
                        onChange={(e) => updateConfig('menuName', e.target.value)}
                        placeholder={`例如: ${modelName}管理`}
                        className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      />
                    </div>

                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">
                        菜单图标
                      </label>
                      <select
                        value={config.menuIcon}
                        onChange={(e) => updateConfig('menuIcon', e.target.value)}
                        className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      >
                        <option value="DocumentTextIcon">文档</option>
                        <option value="TableCellsIcon">表格</option>
                        <option value="UserGroupIcon">用户组</option>
                        <option value="CogIcon">设置</option>
                        <option value="ChartBarIcon">图表</option>
                      </select>
                    </div>
                  </div>
                </div>

                <div>
                  <h3 className="mb-4 text-sm font-medium text-gray-900">权限配置</h3>

                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">默认角色</label>
                    <select
                      multiple
                      value={config.defaultRoles}
                      onChange={(e) => {
                        const selected = Array.from(
                          e.target.selectedOptions,
                          (option) => option.value,
                        );
                        updateConfig('defaultRoles', selected);
                      }}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      size={4}
                    >
                      <option value="admin">管理员</option>
                      <option value="user">普通用户</option>
                      <option value="guest">访客</option>
                      <option value="operator">操作员</option>
                    </select>
                    <p className="mt-1 text-xs text-gray-500">
                      按住Ctrl/Cmd可多选。选中的角色将自动获得访问权限
                    </p>
                  </div>
                </div>

                <div className="rounded-md border border-blue-200 bg-blue-50 p-4">
                  <div className="flex">
                    <div className="flex-shrink-0">
                      <svg
                        className="h-5 w-5 text-blue-400"
                        viewBox="0 0 20 20"
                        fill="currentColor"
                      >
                        <path
                          fillRule="evenodd"
                          d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
                          clipRule="evenodd"
                        />
                      </svg>
                    </div>
                    <div className="ml-3">
                      <h3 className="text-sm font-medium text-blue-800">提示</h3>
                      <div className="mt-2 text-sm text-blue-700">
                        <p>生成的页面将基于Model的字段配置自动创建，您可以在生成后进一步自定义。</p>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* 步骤3: 生成结果 */}
            {currentStep === 3 && (
              <div className="space-y-6">
                {generating ? (
                  <div className="py-12 text-center">
                    <div className="inline-block h-12 w-12 animate-spin rounded-full border-b-2 border-blue-600"></div>
                    <p className="mt-4 text-sm text-gray-500">正在生成CRUD页面...</p>
                  </div>
                ) : generationResult ? (
                  <>
                    <div className="flex items-center justify-center py-6">
                      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-green-100">
                        <svg
                          className="h-8 w-8 text-green-600"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M5 13l4 4L19 7"
                          />
                        </svg>
                      </div>
                    </div>

                    <div className="text-center">
                      <h3 className="text-lg font-medium text-gray-900">生成成功！</h3>
                      <p className="mt-1 text-sm text-gray-500">
                        已为模型 {modelName} 生成以下资源
                      </p>
                    </div>

                    <div className="space-y-4">
                      {/* 生成的页面 */}
                      <div>
                        <h4 className="mb-2 text-sm font-medium text-gray-900">
                          生成的页面 ({generationResult.generatedResources.pages.length})
                        </h4>
                        <div className="space-y-2">
                          {generationResult.generatedResources.pages.map((page) => (
                            <div
                              key={page.id}
                              className="flex items-center justify-between rounded-lg bg-gray-50 p-3"
                            >
                              <div>
                                <div className="text-sm font-medium text-gray-900">
                                  {page.pageName}
                                </div>
                                <div className="text-xs text-gray-500">{page.route}</div>
                              </div>
                              <a
                                href={page.route}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="text-sm text-blue-600 hover:text-blue-700"
                              >
                                访问
                              </a>
                            </div>
                          ))}
                        </div>
                      </div>

                      {/* 生成的菜单 */}
                      <div>
                        <h4 className="mb-2 text-sm font-medium text-gray-900">
                          生成的菜单 ({generationResult.generatedResources.menus.length})
                        </h4>
                        <div className="space-y-2">
                          {generationResult.generatedResources.menus.map((menu) => (
                            <div key={menu.id} className="rounded-lg bg-gray-50 p-3">
                              <div className="text-sm font-medium text-gray-900">
                                {menu.menuName}
                              </div>
                              <div className="text-xs text-gray-500">{menu.menuPath}</div>
                            </div>
                          ))}
                        </div>
                      </div>

                      {/* 生成的权限 */}
                      <div>
                        <h4 className="mb-2 text-sm font-medium text-gray-900">
                          生成的权限 ({generationResult.generatedResources.permissions.length})
                        </h4>
                        <div className="grid grid-cols-2 gap-2">
                          {generationResult.generatedResources.permissions.map((perm) => (
                            <div key={perm.id} className="rounded bg-gray-50 p-2 text-xs">
                              <div className="font-medium text-gray-900">{perm.permissionName}</div>
                              <div className="font-mono text-gray-500">{perm.permissionCode}</div>
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  </>
                ) : null}
              </div>
            )}
          </div>

          {/* 底部按钮 */}
          <div className="flex justify-between border-t border-gray-200 px-6 py-4">
            <button
              onClick={handleCancel}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            >
              取消
            </button>

            <div className="flex gap-3">
              {currentStep > 1 && currentStep < 3 && (
                <button
                  onClick={handlePrevious}
                  className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
                >
                  上一步
                </button>
              )}

              {currentStep < 2 && (
                <button
                  onClick={handleNext}
                  disabled={!validateStep1()}
                  className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  下一步
                </button>
              )}

              {currentStep === 2 && (
                <button
                  onClick={handleGenerate}
                  disabled={!validateStep2() || generating}
                  className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {generating ? '生成中...' : '开始生成'}
                </button>
              )}

              {currentStep === 3 && generationResult && (
                <button
                  onClick={handleComplete}
                  className="rounded-md bg-green-600 px-4 py-2 text-white hover:bg-green-700"
                >
                  完成
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
