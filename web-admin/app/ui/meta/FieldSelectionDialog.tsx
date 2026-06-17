/**
 * Field Selection Dialog Component
 * Main dialog for selecting existing fields or creating new fields
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import type {
  MetaFieldDTO,
  FieldBindingRequest,
  FieldSearchRequest,
  FieldRecommendation,
} from '~/types/fieldLibrary';
import { fieldLibraryService } from '~/shared/services/fieldLibraryService';
import { modelService } from '~/shared/services/modelService';
import { FieldPreviewPanel } from './FieldPreviewPanel';
import { FieldBindingConfigForm } from './FieldBindingConfigForm';
import { FieldCreationForm, type FieldCreationFormData } from './FieldCreationForm';
import { useToastContext } from '~/contexts/ToastContext';

interface FieldSelectionDialogProps {
  isOpen: boolean;
  modelPid: string;
  modelCode: string;
  onClose: () => void;
  onFieldBound: () => void;
}

type TabMode = 'select' | 'create';

export function FieldSelectionDialog({
  isOpen,
  modelPid,
  modelCode,
  onClose,
  onFieldBound,
}: FieldSelectionDialogProps) {
  const { showErrorToast } = useToastContext();

  // Tab state
  const [activeTab, setActiveTab] = useState<TabMode>('select');

  // Search and filter state
  const [searchKeyword, setSearchKeyword] = useState('');
  const [baseTypeFilter, setBaseTypeFilter] = useState<string>('');
  const [semanticTypeFilter, setSemanticTypeFilter] = useState<string>('');

  // Data state
  const [fields, setFields] = useState<MetaFieldDTO[]>([]);
  const [recommendations, setRecommendations] = useState<FieldRecommendation[]>([]);
  const [selectedField, setSelectedField] = useState<MetaFieldDTO | null>(null);
  const [selectedFields, setSelectedFields] = useState<Set<string>>(new Set());

  // UI state
  const [loading, setLoading] = useState(false);
  const [binding, setBinding] = useState(false);
  const [showConfig, setShowConfig] = useState(false);
  const [bindingConfig, setBindingConfig] = useState<FieldBindingRequest | null>(null);
  const [isConfigValid, setIsConfigValid] = useState(true);

  // Batch mode
  const [batchMode, setBatchMode] = useState(false);

  // Field creation state
  const [creationFormData, setCreationFormData] = useState<FieldCreationFormData | null>(null);
  const [isCreationFormValid, setIsCreationFormValid] = useState(false);
  const [creating, setCreating] = useState(false);

  // Load recommendations on mount
  useEffect(() => {
    if (isOpen && activeTab === 'select') {
      loadRecommendations();
      searchFields();
    }
  }, [isOpen, activeTab]);

  // Debounced search
  useEffect(() => {
    if (activeTab === 'select') {
      const timer = setTimeout(() => {
        searchFields();
      }, 300);
      return () => clearTimeout(timer);
    }
  }, [searchKeyword, baseTypeFilter, semanticTypeFilter, activeTab]);

  const loadRecommendations = async () => {
    try {
      const recs = await fieldLibraryService.getFieldRecommendations(modelPid);
      setRecommendations(recs.slice(0, 10));
    } catch (error) {
      console.error('Failed to load recommendations:', error);
    }
  };

  const searchFields = async () => {
    setLoading(true);
    try {
      const request: FieldSearchRequest = {
        keyword: searchKeyword || undefined,
        baseType: baseTypeFilter || undefined,
        semanticType: semanticTypeFilter || undefined,
        page: 0,
        size: 50,
      };
      const result = await fieldLibraryService.searchFields(request);
      setFields(result.records || []);
    } catch (error) {
      console.error('Failed to search fields:', error);
      setFields([]);
    } finally {
      setLoading(false);
    }
  };

  const handleFieldClick = useCallback(
    (field: MetaFieldDTO) => {
      setSelectedField(field);
      if (!batchMode) {
        setShowConfig(true);
      }
    },
    [batchMode],
  );

  const handleFieldSelect = useCallback(
    (field: MetaFieldDTO) => {
      if (batchMode) {
        setSelectedFields((prev) => {
          const next = new Set(prev);
          if (next.has(field.pid)) {
            next.delete(field.pid);
          } else {
            next.add(field.pid);
          }
          return next;
        });
      } else {
        setSelectedField(field);
        setShowConfig(true);
      }
    },
    [batchMode],
  );

  const handleSelectAll = useCallback(() => {
    if (!fields || fields.length === 0) return;
    if (selectedFields.size === fields.length) {
      setSelectedFields(new Set());
    } else {
      setSelectedFields(new Set(fields.map((f) => f.pid)));
    }
  }, [fields, selectedFields]);

  const handleBindingConfigChange = useCallback((config: FieldBindingRequest) => {
    setBindingConfig(config);
  }, []);

  const handleSingleBind = async () => {
    if (!selectedField || !bindingConfig || !isConfigValid) return;

    setBinding(true);
    try {
      await modelService.bindFieldToModel(modelPid, bindingConfig);
      onFieldBound();
      onClose();
    } catch (error: any) {
      console.error('Failed to bind field:', error);
      showErrorToast(error.message || '绑定字段失败');
    } finally {
      setBinding(false);
    }
  };

  const handleBatchBind = async () => {
    if (selectedFields.size === 0) return;

    setBinding(true);
    try {
      await modelService.batchBindFieldsToModel(modelPid, {
        fieldPids: Array.from(selectedFields),
        commonConfig: {
          required: false,
          visible: true,
          editable: true,
        },
      });
      onFieldBound();
      onClose();
    } catch (error: any) {
      console.error('Failed to batch bind fields:', error);
      showErrorToast(error.message || '批量绑定字段失败');
    } finally {
      setBinding(false);
    }
  };

  const handleCreateField = async () => {
    if (!creationFormData || !isCreationFormValid) return;

    setCreating(true);
    try {
      const { modelPid: _modelPid, ...fieldDefinition } = creationFormData;
      // Create the field
      const newField = await fieldLibraryService.createField(fieldDefinition);

      // Automatically bind the field to the model
      await modelService.bindFieldToModel(modelPid, {
        fieldPid: newField.pid,
        required: creationFormData.feature?.required || false,
        nullable: !creationFormData.feature?.required,
        readonly: false,
        visible: true,
        editable: true,
      });

      onFieldBound();
      onClose();
    } catch (error: any) {
      console.error('Failed to create and bind field:', error);
      showErrorToast(error.message || '创建字段失败');
    } finally {
      setCreating(false);
    }
  };

  const handleClose = () => {
    if (!binding && !creating) {
      setSelectedField(null);
      setSelectedFields(new Set());
      setShowConfig(false);
      setBindingConfig(null);
      setBatchMode(false);
      setSearchKeyword('');
      setBaseTypeFilter('');
      setSemanticTypeFilter('');
      setCreationFormData(null);
      setIsCreationFormValid(false);
      setActiveTab('select');
      onClose();
    }
  };

  const baseTypes = useMemo(() => {
    if (!fields || fields.length === 0) return [];
    const types = new Set(fields.map((f) => f.dataType));
    return Array.from(types).sort();
  }, [fields]);

  if (!isOpen) return null;

  return (
    <div data-testid="field-selection-dialog" className="fixed inset-0 z-50 overflow-y-auto">
      {/* Overlay */}
      <div
        className="bg-opacity-50 fixed inset-0 bg-black transition-opacity"
        onClick={handleClose}
      />

      {/* Dialog */}
      <div className="flex min-h-full items-center justify-center p-4">
        <div
          className="rounded-card bg-panel relative flex max-h-[90vh] w-full max-w-6xl flex-col shadow-xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="border-border border-b px-6 py-4">
            <div className="flex items-center justify-between">
              <h2 className="text-text text-xl font-semibold">添加字段到模型: {modelCode}</h2>
              <button
                onClick={handleClose}
                disabled={binding}
                className="text-text-3 hover:text-text-2 disabled:opacity-50"
              >
                <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>

            {/* Tabs */}
            <div className="border-border mt-4 flex border-b">
              <button
                data-testid="field-selection-tab-select"
                onClick={() => setActiveTab('select')}
                className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                  activeTab === 'select'
                    ? 'text-accent border-accent'
                    : 'text-text-2 hover:text-text-2 border-transparent'
                }`}
              >
                选择已有字段
              </button>
              <button
                data-testid="field-selection-tab-create"
                onClick={() => setActiveTab('create')}
                className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                  activeTab === 'create'
                    ? 'text-accent border-accent'
                    : 'text-text-2 hover:text-text-2 border-transparent'
                }`}
              >
                创建新字段
              </button>
            </div>
          </div>

          {/* Content */}
          <div className="flex-1 overflow-hidden">
            {activeTab === 'select' ? (
              <div className="flex h-full">
                {/* Left Panel - Search and List */}
                <div className="border-border flex flex-1 flex-col border-r">
                  {/* Search and Filters */}
                  <div className="border-border space-y-3 border-b p-4">
                    {/* Search */}
                    <div>
                      <input
                        data-testid="field-selection-search"
                        type="text"
                        value={searchKeyword}
                        onChange={(e) => setSearchKeyword(e.target.value)}
                        placeholder="搜索字段..."
                        className="rounded-control border-border-strong focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
                      />
                    </div>

                    {/* Filters */}
                    <div className="flex gap-2">
                      <select
                        value={baseTypeFilter}
                        onChange={(e) => setBaseTypeFilter(e.target.value)}
                        className="rounded-control border-border-strong focus-visible:shadow-focus flex-1 border px-3 py-2 focus:outline-none"
                      >
                        <option value="">所有类型</option>
                        {baseTypes.map((type) => (
                          <option key={type} value={type}>
                            {type}
                          </option>
                        ))}
                      </select>

                      <button
                        onClick={() => setBatchMode(!batchMode)}
                        className={`rounded-control px-4 py-2 text-sm font-medium ${
                          batchMode
                            ? 'bg-accent text-white'
                            : 'bg-subtle text-text-2 hover:bg-hover'
                        }`}
                      >
                        {batchMode ? '批量模式' : '单选模式'}
                      </button>
                    </div>

                    {batchMode && selectedFields.size > 0 && (
                      <div className="rounded-control bg-accent-weak flex items-center justify-between px-3 py-2">
                        <span className="text-sm text-blue-800">
                          已选择 {selectedFields.size} 个字段
                        </span>
                        <button
                          onClick={() => setSelectedFields(new Set())}
                          className="text-accent text-sm hover:text-blue-800"
                        >
                          清空
                        </button>
                      </div>
                    )}
                  </div>

                  {/* Recommendations */}
                  {recommendations.length > 0 && !searchKeyword && (
                    <div className="border-border border-b p-4">
                      <h3 className="text-text-2 mb-2 text-sm font-medium">推荐字段</h3>
                      <div className="space-y-1">
                        {recommendations.slice(0, 5).map((rec) => (
                          <button
                            key={rec.field.pid}
                            onClick={() => handleFieldClick(rec.field)}
                            className="rounded-control hover:bg-subtle flex w-full items-center justify-between px-3 py-2 text-left"
                          >
                            <div className="min-w-0 flex-1">
                              <div className="text-text truncate text-sm font-medium">
                                {rec.field.code}
                              </div>
                              <div className="text-text-2 text-xs">使用 {rec.usageCount} 次</div>
                            </div>
                            <span className="ml-2 inline-flex rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
                              {rec.field.dataType}
                            </span>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Field List */}
                  <div className="flex-1 overflow-y-auto p-4">
                    {loading ? (
                      <div className="flex items-center justify-center py-12">
                        <div className="rounded-pill border-accent h-8 w-8 animate-spin border-b-2"></div>
                      </div>
                    ) : !fields || fields.length === 0 ? (
                      <div className="text-text-2 py-12 text-center">
                        <p>未找到字段</p>
                      </div>
                    ) : (
                      <div className="space-y-2">
                        {batchMode && (
                          <label className="rounded-control hover:bg-subtle flex cursor-pointer items-center px-3 py-2">
                            <input
                              type="checkbox"
                              checked={fields && selectedFields.size === fields.length}
                              onChange={handleSelectAll}
                              className="border-border-strong text-accent focus-visible:shadow-focus rounded focus:outline-none"
                            />
                            <span className="text-text-2 ml-2 text-sm font-medium">全选</span>
                          </label>
                        )}
                        {fields.map((field) => (
                          <div
                            key={field.pid}
                            onClick={() => handleFieldClick(field)}
                            className={`rounded-control cursor-pointer px-3 py-3 transition-colors ${
                              selectedField?.pid === field.pid
                                ? 'bg-accent-weak border border-blue-200'
                                : 'hover:bg-subtle border border-transparent'
                            }`}
                          >
                            <div className="flex items-start">
                              {batchMode && (
                                <input
                                  type="checkbox"
                                  checked={selectedFields.has(field.pid)}
                                  onChange={(e) => {
                                    e.stopPropagation();
                                    handleFieldSelect(field);
                                  }}
                                  className="border-border-strong text-accent focus-visible:shadow-focus mt-1 mr-3 rounded focus:outline-none"
                                />
                              )}
                              <div className="min-w-0 flex-1">
                                <div className="flex items-center gap-2">
                                  <span className="text-text font-mono text-sm font-medium">
                                    {field.code}
                                  </span>
                                  <span className="bg-subtle text-text inline-flex rounded px-2 py-0.5 text-xs font-medium">
                                    {field.dataType}
                                  </span>
                                  {field.status === 'published' && (
                                    <span className="inline-flex rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
                                      已发布
                                    </span>
                                  )}
                                </div>
                                {field.remark && (
                                  <p className="text-text-2 mt-1 truncate text-xs">
                                    {field.remark}
                                  </p>
                                )}
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>

                {/* Right Panel - Preview and Config */}
                <div className="flex w-96 flex-col">
                  {showConfig && selectedField ? (
                    <div className="flex-1 overflow-y-auto p-4">
                      <h3 className="text-text mb-4 text-lg font-medium">配置字段绑定</h3>
                      <div className="rounded-control bg-subtle mb-4 p-3">
                        <div className="text-text font-mono text-sm font-medium">
                          {selectedField.code}
                        </div>
                        <div className="text-text-2 mt-1 text-xs">{selectedField.dataType}</div>
                      </div>
                      <FieldBindingConfigForm
                        field={selectedField}
                        onChange={handleBindingConfigChange}
                        onValidationChange={setIsConfigValid}
                      />
                    </div>
                  ) : (
                    <FieldPreviewPanel
                      field={selectedField}
                      onSelect={handleFieldSelect}
                      showSelectButton={!batchMode}
                    />
                  )}
                </div>
              </div>
            ) : (
              <div className="p-6">
                <FieldCreationForm
                  modelPid={modelPid}
                  modelCode={modelCode}
                  onValidationChange={setIsCreationFormValid}
                  onChange={setCreationFormData}
                />
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="border-border flex justify-end gap-3 border-t px-6 py-4">
            <button
              onClick={handleClose}
              disabled={binding || creating}
              className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2 disabled:opacity-50"
            >
              取消
            </button>
            {activeTab === 'select' ? (
              <>
                {batchMode ? (
                  <button
                    onClick={handleBatchBind}
                    disabled={binding || selectedFields.size === 0}
                    className="rounded-control bg-accent hover:bg-accent-hover flex items-center px-4 py-2 text-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {binding && (
                      <div className="rounded-pill mr-2 h-4 w-4 animate-spin border-b-2 border-white"></div>
                    )}
                    批量绑定 ({selectedFields.size})
                  </button>
                ) : (
                  <button
                    onClick={handleSingleBind}
                    disabled={binding || !selectedField || !showConfig || !isConfigValid}
                    className="rounded-control bg-accent hover:bg-accent-hover flex items-center px-4 py-2 text-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {binding && (
                      <div className="rounded-pill mr-2 h-4 w-4 animate-spin border-b-2 border-white"></div>
                    )}
                    确认绑定
                  </button>
                )}
              </>
            ) : (
              <button
                data-testid="field-selection-create-bind"
                onClick={handleCreateField}
                disabled={creating || !isCreationFormValid}
                className="rounded-control flex items-center bg-green-600 px-4 py-2 text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {creating && (
                  <div className="rounded-pill mr-2 h-4 w-4 animate-spin border-b-2 border-white"></div>
                )}
                创建并绑定字段
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
