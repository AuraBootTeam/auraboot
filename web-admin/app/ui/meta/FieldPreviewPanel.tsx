/**
 * Field Preview Panel Component
 * Displays detailed information about a selected field
 */

import React, { useEffect, useState } from 'react';
import type { MetaFieldDTO, FieldUsageInfo } from '~/types/fieldLibrary';
import { fieldLibraryService } from '~/shared/services/fieldLibraryService';

interface FieldPreviewPanelProps {
  field: MetaFieldDTO | null;
  onSelect?: (field: MetaFieldDTO) => void;
  showSelectButton?: boolean;
}

export function FieldPreviewPanel({
  field,
  onSelect,
  showSelectButton = true,
}: FieldPreviewPanelProps) {
  const [usageInfo, setUsageInfo] = useState<FieldUsageInfo | null>(null);
  const [loadingUsage, setLoadingUsage] = useState(false);

  useEffect(() => {
    if (field) {
      loadUsageInfo(field.pid);
    } else {
      setUsageInfo(null);
    }
  }, [field]);

  const loadUsageInfo = async (fieldPid: string) => {
    setLoadingUsage(true);
    try {
      const info = await fieldLibraryService.getFieldUsage(fieldPid);
      setUsageInfo(info);
    } catch (error) {
      console.error('Failed to load field usage:', error);
    } finally {
      setLoadingUsage(false);
    }
  };

  if (!field) {
    return (
      <div className="text-text-3 flex h-full items-center justify-center">
        <div className="text-center">
          <svg
            className="text-text-3 mx-auto h-12 w-12"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
            />
          </svg>
          <p className="mt-2 text-sm">选择一个字段查看详情</p>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto">
      {/* Header */}
      <div className="border-border border-b p-4">
        <h3 className="text-text text-lg font-medium">{field.code}</h3>
        <p className="text-text-2 mt-1 text-sm">字段详情</p>
      </div>

      {/* Basic Information */}
      <div className="space-y-4 p-4">
        <div>
          <label className="text-text-2 mb-1 block text-xs font-medium tracking-wider uppercase">
            字段编码
          </label>
          <div className="text-text font-mono text-sm">{field.code}</div>
        </div>

        <div>
          <label className="text-text-2 mb-1 block text-xs font-medium tracking-wider uppercase">
            数据类型
          </label>
          <div className="text-text text-sm">
            <span className="inline-flex rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
              {field.dataType}
            </span>
          </div>
        </div>

        <div>
          <label className="text-text-2 mb-1 block text-xs font-medium tracking-wider uppercase">
            状态
          </label>
          <div className="text-text text-sm">
            <span
              className={`inline-flex rounded px-2 py-1 text-xs font-medium ${
                field.status === 'published'
                  ? 'bg-green-100 text-green-800'
                  : field.status === 'draft'
                    ? 'text-status-amber bg-yellow-100'
                    : 'bg-subtle text-text'
              }`}
            >
              {field.status === 'published' && '已发布'}
              {field.status === 'draft' && '草稿'}
              {field.status === 'archived' && '已归档'}
            </span>
          </div>
        </div>

        {field.remark && (
          <div>
            <label className="text-text-2 mb-1 block text-xs font-medium tracking-wider uppercase">
              备注
            </label>
            <div className="text-text text-sm">{field.remark}</div>
          </div>
        )}

        {/* Features */}
        {field.feature && Object.keys(field.feature).length > 0 && (
          <div>
            <label className="text-text-2 mb-2 block text-xs font-medium tracking-wider uppercase">
              字段特性
            </label>
            <div className="flex flex-wrap gap-2">
              {field.feature.required && (
                <span className="inline-flex rounded bg-red-100 px-2 py-1 text-xs font-medium text-red-800">
                  必填
                </span>
              )}
              {field.feature.unique && (
                <span className="inline-flex rounded bg-purple-100 px-2 py-1 text-xs font-medium text-purple-800">
                  唯一
                </span>
              )}
              {field.feature.indexed && (
                <span className="inline-flex rounded bg-indigo-100 px-2 py-1 text-xs font-medium text-indigo-800">
                  索引
                </span>
              )}
            </div>
          </div>
        )}

        {/* Usage Statistics */}
        <div className="border-border border-t pt-4">
          <label className="text-text-2 mb-2 block text-xs font-medium tracking-wider uppercase">
            使用情况
          </label>
          {loadingUsage ? (
            <div className="text-text-2 flex items-center text-sm">
              <div className="rounded-pill border-border-strong mr-2 h-4 w-4 animate-spin border-b-2"></div>
              加载中...
            </div>
          ) : usageInfo ? (
            <div className="space-y-2">
              <div className="text-text text-sm">
                使用次数: <span className="font-medium">{usageInfo.totalUsageCount}</span>
              </div>
              {usageInfo.modelUsages.length > 0 && (
                <div>
                  <div className="text-text-2 mb-1 text-xs">使用该字段的模型:</div>
                  <div className="space-y-1">
                    {usageInfo.modelUsages.slice(0, 5).map((usage) => (
                      <div key={usage.modelPid} className="text-text-2 text-xs">
                        • {usage.modelName || usage.modelCode}
                      </div>
                    ))}
                    {usageInfo.modelUsages.length > 5 && (
                      <div className="text-text-2 text-xs">
                        还有 {usageInfo.modelUsages.length - 5} 个模型...
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="text-text-2 text-sm">暂无使用记录</div>
          )}
        </div>

        {/* Validation Rules */}
        {field.ruleSchema && Object.keys(field.ruleSchema).length > 0 && (
          <div className="border-border border-t pt-4">
            <label className="text-text-2 mb-2 block text-xs font-medium tracking-wider uppercase">
              验证规则
            </label>
            <div className="bg-subtle text-text-2 rounded p-2 font-mono text-xs">
              {JSON.stringify(field.ruleSchema, null, 2)}
            </div>
          </div>
        )}

        {/* Metadata */}
        <div className="border-border space-y-2 border-t pt-4">
          <div className="text-text-2 text-xs">
            创建时间: {new Date(field.createdAt).toLocaleString()}
          </div>
          <div className="text-text-2 text-xs">
            更新时间: {new Date(field.updatedAt).toLocaleString()}
          </div>
          {field.createdBy && <div className="text-text-2 text-xs">创建人: {field.createdBy}</div>}
        </div>
      </div>

      {/* Select Button */}
      {showSelectButton && onSelect && (
        <div className="border-border border-t p-4">
          <button
            onClick={() => onSelect(field)}
            className="rounded-control bg-accent hover:bg-accent-hover focus-visible:shadow-focus w-full px-4 py-2 text-white focus:outline-none"
          >
            选择此字段
          </button>
        </div>
      )}
    </div>
  );
}
