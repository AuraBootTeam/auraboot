/**
 * Field Creation Page
 * Create a new field definition
 */

import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { fieldService } from '~/services/fieldService';
import { modelService } from '~/services/modelService';
import { useToastContext } from '~/contexts/ToastContext';
import { useDslRegistry } from '~/contexts/DslRegistryContext';
import type { MetaFieldCreateRequest, MetaModelDTO } from '~/types/model';

const DATA_TYPES_FALLBACK = [
  { value: 'string', label: 'String' },
  { value: 'integer', label: 'Integer' },
  { value: 'long', label: 'Long' },
  { value: 'decimal', label: 'Decimal' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'date', label: 'Date' },
  { value: 'datetime', label: 'DateTime' },
  { value: 'text', label: 'Text' },
  { value: 'json', label: 'json' },
  { value: 'reference', label: 'Reference' },
];

export default function NewFieldPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const modelPid = searchParams.get('modelPid');
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { ensureLoaded, getEnumOptions } = useDslRegistry();
  useEffect(() => { ensureLoaded(); }, [ensureLoaded]);
  const DATA_TYPES =
    getEnumOptions('DataType').length > 0 ? getEnumOptions('DataType') : DATA_TYPES_FALLBACK;

  const [loading, setLoading] = useState(false);
  const [modelInfo, setModelInfo] = useState<MetaModelDTO | null>(null);
  const [loadingModel, setLoadingModel] = useState(false);
  const [formData, setFormData] = useState<Partial<MetaFieldCreateRequest>>({
    code: '',
    dataType: 'string',
    extension: {
      displayName: '',
      description: '',
      required: false,
      unique: false,
      indexed: false,
    },
  });

  // Load model information if modelPid is provided
  useEffect(() => {
    if (modelPid) {
      setLoadingModel(true);
      modelService
        .findByPid(modelPid)
        .then((model) => {
          setModelInfo(model);
        })
        .catch((error) => {
          console.error('Failed to load model:', error);
          showErrorToast('加载模型信息失败');
        })
        .finally(() => {
          setLoadingModel(false);
        });
    }
  }, [modelPid]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!formData.code || !formData.dataType) {
      showErrorToast('请填写必填字段');
      return;
    }

    setLoading(true);

    try {
      const request: MetaFieldCreateRequest = {
        code: formData.code!,
        dataType: formData.dataType!,
        extension: formData.extension || {},
        modelPid: modelPid || undefined,
      };

      await fieldService.createField(request);

      showSuccessToast('字段创建成功');

      // Navigate back to model detail page if modelPid is provided
      if (modelPid) {
        navigate(`/meta/models/${modelPid}`);
      } else {
        navigate('/meta/fields');
      }
    } catch (error) {
      console.error('Failed to create field:', error);
      showErrorToast(error instanceof Error ? error.message : '字段创建失败');
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    if (modelPid) {
      navigate(`/meta/models/${modelPid}`);
    } else {
      navigate('/meta/fields');
    }
  };

  return (
    <div className="mx-auto w-full px-6 py-8">
      <div className="mx-auto max-w-2xl">
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-gray-900">创建字段</h1>
        </div>

        {/* Model Information Card */}
        {modelPid && (
          <div className="mb-6 rounded-lg border border-blue-200 bg-blue-50 p-4">
            {loadingModel ? (
              <div className="flex items-center">
                <div className="mr-3 h-5 w-5 animate-spin rounded-full border-b-2 border-blue-600"></div>
                <span className="text-sm text-gray-600">加载模型信息...</span>
              </div>
            ) : modelInfo ? (
              <div>
                <h3 className="mb-2 text-lg font-semibold text-blue-900">为模型创建字段</h3>
                <div className="grid grid-cols-2 gap-3 text-sm">
                  <div>
                    <span className="text-gray-600">模型名称:</span>
                    <span className="ml-2 font-medium text-gray-900">{modelInfo.displayName}</span>
                  </div>
                  <div>
                    <span className="text-gray-600">模型编码:</span>
                    <span className="ml-2 font-mono text-gray-900">{modelInfo.code}</span>
                  </div>
                  <div>
                    <span className="text-gray-600">模型类型:</span>
                    <span className="ml-2 text-gray-900">{modelInfo.modelType}</span>
                  </div>
                  <div>
                    <span className="text-gray-600">当前版本:</span>
                    <span className="ml-2 text-gray-900">v{modelInfo.version}</span>
                  </div>
                </div>
                {modelInfo.description && (
                  <div className="mt-3 border-t border-blue-200 pt-3">
                    <span className="text-sm text-gray-600">描述:</span>
                    <p className="mt-1 text-sm text-gray-700">{modelInfo.description}</p>
                  </div>
                )}
              </div>
            ) : (
              <div className="text-sm text-red-600">无法加载模型信息</div>
            )}
          </div>
        )}

        {!modelPid && (
          <div className="mb-6 rounded-lg border border-yellow-200 bg-yellow-50 p-4">
            <div className="flex items-start">
              <svg
                className="mt-0.5 mr-3 h-5 w-5 text-yellow-600"
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path
                  fillRule="evenodd"
                  d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                  clipRule="evenodd"
                />
              </svg>
              <div>
                <h3 className="text-sm font-medium text-yellow-800">提示</h3>
                <p className="mt-1 text-sm text-yellow-700">
                  建议从模型详情页创建字段,以便自动关联到模型。
                </p>
              </div>
            </div>
          </div>
        )}

        <form onSubmit={handleSubmit} className="rounded-lg bg-white p-6 shadow-md">
          {/* Field Code */}
          <div className="mb-4">
            <label className="mb-2 block text-sm font-medium text-gray-700">
              字段编码 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={formData.code || ''}
              onChange={(e) => setFormData({ ...formData, code: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              placeholder="例如: user_name"
              required
            />
            <p className="mt-1 text-xs text-gray-500">字段的唯一标识符，建议使用小写字母和下划线</p>
          </div>

          {/* Display Name */}
          <div className="mb-4">
            <label className="mb-2 block text-sm font-medium text-gray-700">显示名称</label>
            <input
              type="text"
              value={(formData.extension?.displayName as string) || ''}
              onChange={(e) =>
                setFormData({
                  ...formData,
                  extension: { ...formData.extension, displayName: e.target.value },
                })
              }
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              placeholder="例如: 用户名"
            />
          </div>

          {/* Data Type */}
          <div className="mb-4">
            <label className="mb-2 block text-sm font-medium text-gray-700">
              数据类型 <span className="text-red-500">*</span>
            </label>
            <select
              value={formData.dataType || 'string'}
              onChange={(e) => setFormData({ ...formData, dataType: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              required
            >
              {DATA_TYPES.map((type) => (
                <option key={type.value} value={type.value}>
                  {type.label}
                </option>
              ))}
            </select>
          </div>

          {/* Description */}
          <div className="mb-4">
            <label className="mb-2 block text-sm font-medium text-gray-700">描述</label>
            <textarea
              value={(formData.extension?.description as string) || ''}
              onChange={(e) =>
                setFormData({
                  ...formData,
                  extension: { ...formData.extension, description: e.target.value },
                })
              }
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              rows={3}
              placeholder="字段的详细说明"
            />
          </div>

          {/* Field Options */}
          <div className="mb-6 space-y-2">
            <label className="flex items-center">
              <input
                type="checkbox"
                checked={!!formData.extension?.required}
                onChange={(e) =>
                  setFormData({
                    ...formData,
                    extension: { ...formData.extension, required: e.target.checked },
                  })
                }
                className="mr-2"
              />
              <span className="text-sm text-gray-700">必填字段</span>
            </label>

            <label className="flex items-center">
              <input
                type="checkbox"
                checked={!!formData.extension?.unique}
                onChange={(e) =>
                  setFormData({
                    ...formData,
                    extension: { ...formData.extension, unique: e.target.checked },
                  })
                }
                className="mr-2"
              />
              <span className="text-sm text-gray-700">唯一约束</span>
            </label>

            <label className="flex items-center">
              <input
                type="checkbox"
                checked={!!formData.extension?.indexed}
                onChange={(e) =>
                  setFormData({
                    ...formData,
                    extension: { ...formData.extension, indexed: e.target.checked },
                  })
                }
                className="mr-2"
              />
              <span className="text-sm text-gray-700">创建索引</span>
            </label>
          </div>

          {/* Action Buttons */}
          <div className="flex justify-end space-x-3">
            <button
              type="button"
              onClick={handleCancel}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
              disabled={loading}
            >
              取消
            </button>
            <button
              type="submit"
              className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
              disabled={loading}
            >
              {loading ? '创建中...' : '创建字段'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
