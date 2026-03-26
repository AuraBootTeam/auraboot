/**
 * Plugin Uninstall Preview Component
 *
 * Displays a comprehensive preview of what will happen when uninstalling a plugin.
 * Shows resources categorized by: will delete, needs decision, will keep.
 */

import { useState, useCallback, useEffect } from 'react';
import {
  XMarkIcon,
  TrashIcon,
  ExclamationTriangleIcon,
  CheckCircleIcon,
  LinkSlashIcon,
  ChevronDownIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import { ResultHelper } from '~/utils/type';
import {
  getUninstallPreview,
  executeUninstall,
  type UninstallPreviewResult,
  type UninstallRequest,
  type UninstallResult,
  type ResourceUninstallInfo,
  type UninstallDecision,
  getResourceTypeLabel,
  getDecisionLabel,
} from '../api/pluginUninstallApi';
import { ResourceDiffViewer, DiffBadge } from './ResourceDiffViewer';

export interface PluginUninstallPreviewProps {
  pluginPid: string;
  pluginName?: string;
  isOpen: boolean;
  onClose: () => void;
  onUninstallComplete?: (result: UninstallResult) => void;
}

export function PluginUninstallPreview({
  pluginPid,
  pluginName,
  isOpen,
  onClose,
  onUninstallComplete,
}: PluginUninstallPreviewProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [loading, setLoading] = useState(true);
  const [executing, setExecuting] = useState(false);
  const [preview, setPreview] = useState<UninstallPreviewResult | null>(null);
  const [decisions, setDecisions] = useState<Record<string, UninstallDecision>>({});
  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    new Set(['needsDecision'])
  );
  const [expandedResources, setExpandedResources] = useState<Set<string>>(new Set());

  // Load preview on mount
  useEffect(() => {
    if (isOpen && pluginPid) {
      loadPreview();
    }
  }, [isOpen, pluginPid]);

  const loadPreview = async () => {
    setLoading(true);
    try {
      const result = await getUninstallPreview(pluginPid);
      if (ResultHelper.isSuccess(result) && result.data) {
        setPreview(result.data);
        // Initialize decisions with suggested values
        const initialDecisions: Record<string, UninstallDecision> = {};
        result.data.needsDecision.forEach((resource) => {
          if (resource.suggestedDecision) {
            initialDecisions[resource.code] = resource.suggestedDecision;
          }
        });
        setDecisions(initialDecisions);
      } else {
        showErrorToast(result.desc || '加载预览失败');
      }
    } catch (error) {
      showErrorToast('加载预览失败');
    } finally {
      setLoading(false);
    }
  };

  const handleDecisionChange = (resourceCode: string, decision: UninstallDecision) => {
    setDecisions((prev) => ({
      ...prev,
      [resourceCode]: decision,
    }));
  };

  const toggleSection = (section: string) => {
    setExpandedSections((prev) => {
      const next = new Set(prev);
      if (next.has(section)) {
        next.delete(section);
      } else {
        next.add(section);
      }
      return next;
    });
  };

  const toggleResource = (resourceCode: string) => {
    setExpandedResources((prev) => {
      const next = new Set(prev);
      if (next.has(resourceCode)) {
        next.delete(resourceCode);
      } else {
        next.add(resourceCode);
      }
      return next;
    });
  };

  const handleUninstall = async () => {
    if (!preview) return;

    // Check if all decisions are made
    const missingDecisions = preview.needsDecision.filter(
      (r) => !decisions[r.code] || decisions[r.code] === 'skip'
    );

    if (missingDecisions.length > 0) {
      showErrorToast(`请为 ${missingDecisions.length} 个资源做出选择`);
      return;
    }

    setExecuting(true);
    try {
      const request: UninstallRequest = {
        removeData: false,
        decisions,
        force: false,
      };

      const result = await executeUninstall(pluginPid, request);

      if (ResultHelper.isSuccess(result) && result.data) {
        if (result.data.success) {
          showSuccessToast(
            `插件卸载成功：删除 ${result.data.deletedCount} 个资源，脱离 ${result.data.detachedCount} 个资源`
          );
          onUninstallComplete?.(result.data);
          onClose();
        } else {
          showErrorToast(result.data.errorMessage || '卸载失败');
        }
      } else {
        showErrorToast(result.desc || '卸载失败');
      }
    } catch (error) {
      showErrorToast('卸载失败');
    } finally {
      setExecuting(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 overflow-y-auto h-full w-full z-50 flex items-center justify-center">
      <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-3xl mx-4 max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="bg-gradient-to-r from-red-500 to-red-600 rounded-t-2xl px-6 py-4 flex-shrink-0">
          <div className="flex items-center justify-between">
            <div className="flex items-center">
              <TrashIcon className="h-6 w-6 text-white mr-2" />
              <h3 className="text-lg font-semibold text-white">
                卸载插件 "{preview?.pluginName || pluginName || pluginPid}"
              </h3>
            </div>
            <button
              onClick={onClose}
              className="text-white/80 hover:text-white transition-colors"
              disabled={executing}
            >
              <XMarkIcon className="h-6 w-6" />
            </button>
          </div>
          {preview && (
            <p className="text-white/80 text-sm mt-1">
              版本 {preview.pluginVersion} | 共 {preview.totalResources} 个资源
            </p>
          )}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
              <span className="ml-3 text-gray-500">加载预览中...</span>
            </div>
          ) : preview ? (
            <div className="space-y-6">
              {/* Summary */}
              <SummarySection preview={preview} />

              {/* Will Delete Section */}
              {preview.willDelete.length > 0 && (
                <ResourceSection
                  title="将删除的资源"
                  description="这些资源将在卸载时删除"
                  icon={<TrashIcon className="h-5 w-5 text-red-500" />}
                  resources={preview.willDelete}
                  variant="delete"
                  isExpanded={expandedSections.has('willDelete')}
                  onToggle={() => toggleSection('willDelete')}
                  expandedResources={expandedResources}
                  onToggleResource={toggleResource}
                />
              )}

              {/* Needs Decision Section */}
              {preview.needsDecision.length > 0 && (
                <ResourceSection
                  title="需要您做出选择"
                  description="这些资源已被修改，请选择如何处理"
                  icon={<ExclamationTriangleIcon className="h-5 w-5 text-amber-500" />}
                  resources={preview.needsDecision}
                  variant="decision"
                  isExpanded={expandedSections.has('needsDecision')}
                  onToggle={() => toggleSection('needsDecision')}
                  expandedResources={expandedResources}
                  onToggleResource={toggleResource}
                  decisions={decisions}
                  onDecisionChange={handleDecisionChange}
                />
              )}

              {/* Will Keep Section */}
              {preview.willKeep.length > 0 && (
                <ResourceSection
                  title="将保留的资源"
                  description="这些资源已由您接管，不受卸载影响"
                  icon={<CheckCircleIcon className="h-5 w-5 text-green-500" />}
                  resources={preview.willKeep}
                  variant="keep"
                  isExpanded={expandedSections.has('willKeep')}
                  onToggle={() => toggleSection('willKeep')}
                  expandedResources={expandedResources}
                  onToggleResource={toggleResource}
                />
              )}
            </div>
          ) : (
            <div className="text-center py-12 text-gray-500">加载失败</div>
          )}
        </div>

        {/* Footer */}
        <div className="flex-shrink-0 bg-gray-50 rounded-b-2xl px-6 py-4 border-t border-gray-200">
          <div className="flex justify-between items-center">
            <div className="text-sm text-gray-500">
              {preview?.hasConflicts && (
                <span className="text-amber-600">
                  请为所有标记的资源做出选择后继续
                </span>
              )}
            </div>
            <div className="flex space-x-3">
              <button
                onClick={onClose}
                disabled={executing}
                className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={handleUninstall}
                disabled={executing || loading}
                className="px-4 py-2 text-sm font-medium text-white bg-red-600 border border-transparent rounded-lg hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 disabled:opacity-50"
              >
                {executing ? '卸载中...' : '确认卸载'}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ==================== Sub Components ====================

interface SummarySectionProps {
  preview: UninstallPreviewResult;
}

function SummarySection({ preview }: SummarySectionProps) {
  return (
    <div className="grid grid-cols-3 gap-4">
      <div className="bg-red-50 rounded-lg p-4 text-center">
        <div className="text-2xl font-bold text-red-600">{preview.willDelete.length}</div>
        <div className="text-sm text-red-700">将删除</div>
      </div>
      <div className="bg-amber-50 rounded-lg p-4 text-center">
        <div className="text-2xl font-bold text-amber-600">{preview.needsDecision.length}</div>
        <div className="text-sm text-amber-700">需决策</div>
      </div>
      <div className="bg-green-50 rounded-lg p-4 text-center">
        <div className="text-2xl font-bold text-green-600">{preview.willKeep.length}</div>
        <div className="text-sm text-green-700">将保留</div>
      </div>
    </div>
  );
}

interface ResourceSectionProps {
  title: string;
  description: string;
  icon: React.ReactNode;
  resources: ResourceUninstallInfo[];
  variant: 'delete' | 'decision' | 'keep';
  isExpanded: boolean;
  onToggle: () => void;
  expandedResources: Set<string>;
  onToggleResource: (code: string) => void;
  decisions?: Record<string, UninstallDecision>;
  onDecisionChange?: (code: string, decision: UninstallDecision) => void;
}

function ResourceSection({
  title,
  description,
  icon,
  resources,
  variant,
  isExpanded,
  onToggle,
  expandedResources,
  onToggleResource,
  decisions,
  onDecisionChange,
}: ResourceSectionProps) {
  const borderColor =
    variant === 'delete'
      ? 'border-red-200'
      : variant === 'decision'
        ? 'border-amber-200'
        : 'border-green-200';

  const headerBg =
    variant === 'delete'
      ? 'bg-red-50'
      : variant === 'decision'
        ? 'bg-amber-50'
        : 'bg-green-50';

  return (
    <div className={`border rounded-lg overflow-hidden ${borderColor}`}>
      {/* Section Header */}
      <button
        onClick={onToggle}
        className={`w-full flex items-center justify-between px-4 py-3 ${headerBg} hover:opacity-90 transition-opacity`}
      >
        <div className="flex items-center">
          {icon}
          <span className="ml-2 font-medium text-gray-900">{title}</span>
          <span className="ml-2 text-sm text-gray-500">({resources.length})</span>
        </div>
        {isExpanded ? (
          <ChevronDownIcon className="h-5 w-5 text-gray-400" />
        ) : (
          <ChevronRightIcon className="h-5 w-5 text-gray-400" />
        )}
      </button>

      {/* Section Content */}
      {isExpanded && (
        <div className="divide-y divide-gray-100">
          <div className="px-4 py-2 text-sm text-gray-500">{description}</div>
          {resources.map((resource) => (
            <ResourceItem
              key={resource.code}
              resource={resource}
              variant={variant}
              isExpanded={expandedResources.has(resource.code)}
              onToggle={() => onToggleResource(resource.code)}
              decision={decisions?.[resource.code]}
              onDecisionChange={
                onDecisionChange
                  ? (decision) => onDecisionChange(resource.code, decision)
                  : undefined
              }
            />
          ))}
        </div>
      )}
    </div>
  );
}

interface ResourceItemProps {
  resource: ResourceUninstallInfo;
  variant: 'delete' | 'decision' | 'keep';
  isExpanded: boolean;
  onToggle: () => void;
  decision?: UninstallDecision;
  onDecisionChange?: (decision: UninstallDecision) => void;
}

function ResourceItem({
  resource,
  variant,
  isExpanded,
  onToggle,
  decision,
  onDecisionChange,
}: ResourceItemProps) {
  const hasDiffs = resource.diffs && resource.diffs.length > 0;

  return (
    <div className="px-4 py-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center flex-1 min-w-0">
          {hasDiffs && (
            <button onClick={onToggle} className="mr-2 flex-shrink-0">
              {isExpanded ? (
                <ChevronDownIcon className="h-4 w-4 text-gray-400" />
              ) : (
                <ChevronRightIcon className="h-4 w-4 text-gray-400" />
              )}
            </button>
          )}
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-gray-100 text-gray-700 mr-2 flex-shrink-0">
            {getResourceTypeLabel(resource.type)}
          </span>
          <span className="text-sm text-gray-900 truncate">{resource.name}</span>
          <span className="text-xs text-gray-400 ml-2 truncate">({resource.code})</span>
          {resource.modified && <DiffBadge count={resource.diffs?.length || 0} className="ml-2" />}
        </div>

        {variant === 'decision' && onDecisionChange && (
          <div className="flex-shrink-0 ml-4">
            <select
              value={decision || ''}
              onChange={(e) => onDecisionChange(e.target.value as UninstallDecision)}
              className="text-sm border border-gray-300 rounded-md px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="">请选择...</option>
              <option value="delete">{getDecisionLabel('delete')}</option>
              <option value="keep_and_detach">{getDecisionLabel('keep_and_detach')}</option>
            </select>
          </div>
        )}

        {variant === 'keep' && (
          <span className="flex-shrink-0 ml-4 inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-700">
            <LinkSlashIcon className="h-3 w-3 mr-1" />
            已脱离
          </span>
        )}
      </div>

      {/* Diffs */}
      {isExpanded && hasDiffs && (
        <div className="mt-3 ml-6">
          <ResourceDiffViewer diffs={resource.diffs!} maxHeight="200px" />
        </div>
      )}
    </div>
  );
}

export default PluginUninstallPreview;
