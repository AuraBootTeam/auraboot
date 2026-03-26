/**
 * Plugin Resource Warning Modal
 *
 * Displays a warning when user first modifies a shared resource managed by a plugin.
 * Provides options to continue editing, cancel, or detach from plugin management.
 */

import { XMarkIcon, ExclamationTriangleIcon, LinkSlashIcon } from '@heroicons/react/24/outline';
import type { ResourceOwnershipInfo, ResourceType } from '../api/pluginUninstallApi';
import { getResourceTypeLabel, getOwnershipTypeLabel } from '../api/pluginUninstallApi';

export interface PluginResourceWarningModalProps {
  isOpen: boolean;
  resourceType: ResourceType;
  resourceCode: string;
  resourceName?: string;
  pluginName?: string;
  ownershipInfo?: ResourceOwnershipInfo | null;
  loading?: boolean;
  onContinue: () => void;
  onCancel: () => void;
  onDetach: () => void;
}

export function PluginResourceWarningModal({
  isOpen,
  resourceType,
  resourceCode,
  resourceName,
  pluginName,
  ownershipInfo,
  loading = false,
  onContinue,
  onCancel,
  onDetach,
}: PluginResourceWarningModalProps) {
  if (!isOpen) return null;

  const displayName = resourceName || resourceCode;
  const typeLabel = getResourceTypeLabel(resourceType);
  const pluginDisplayName = pluginName || ownershipInfo?.pluginPid || '未知插件';

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 overflow-y-auto h-full w-full z-50 flex items-center justify-center">
      <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 transform transition-all">
        {/* Header */}
        <div className="bg-gradient-to-r from-amber-500 to-orange-500 rounded-t-2xl px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center">
              <ExclamationTriangleIcon className="h-6 w-6 text-white mr-2" />
              <h3 className="text-lg font-semibold text-white">修改插件资源</h3>
            </div>
            <button
              onClick={onCancel}
              className="text-white/80 hover:text-white transition-colors"
              disabled={loading}
            >
              <XMarkIcon className="h-6 w-6" />
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="p-6">
          <div className="mb-6">
            <p className="text-gray-700 mb-4">
              您正在修改由插件 <strong className="text-gray-900">"{pluginDisplayName}"</strong> 提供的{typeLabel}：
            </p>

            <div className="bg-gray-50 rounded-lg p-4 mb-4">
              <div className="flex items-center">
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800 mr-2">
                  {typeLabel}
                </span>
                <span className="text-gray-900 font-medium">{displayName}</span>
              </div>
              {ownershipInfo?.ownershipType && (
                <div className="mt-2 text-sm text-gray-500">
                  当前状态：{getOwnershipTypeLabel(ownershipInfo.ownershipType)}
                </div>
              )}
            </div>

            <div className="space-y-3 text-sm text-gray-600">
              <p>
                修改后，该资源将标记为 <strong>"用户已修改"</strong>。
              </p>
              <p>
                当插件卸载或升级时，系统会提示您选择是否保留您的修改。
              </p>
            </div>
          </div>

          {/* Actions */}
          <div className="flex flex-col space-y-3">
            <button
              onClick={onContinue}
              disabled={loading}
              className="w-full inline-flex justify-center items-center px-4 py-2.5 border border-transparent text-sm font-medium rounded-lg shadow-sm text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {loading ? '处理中...' : '继续修改'}
            </button>

            <button
              onClick={onDetach}
              disabled={loading}
              className="w-full inline-flex justify-center items-center px-4 py-2.5 border border-gray-300 text-sm font-medium rounded-lg shadow-sm text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <LinkSlashIcon className="h-4 w-4 mr-2" />
              脱离插件管理
              <span className="ml-2 text-xs text-gray-500">(资源将完全归您所有)</span>
            </button>

            <button
              onClick={onCancel}
              disabled={loading}
              className="w-full inline-flex justify-center items-center px-4 py-2.5 text-sm font-medium rounded-lg text-gray-500 hover:text-gray-700 focus:outline-none transition-colors"
            >
              取消
            </button>
          </div>
        </div>

        {/* Info Footer */}
        <div className="bg-gray-50 rounded-b-2xl px-6 py-3 border-t border-gray-100">
          <p className="text-xs text-gray-500 text-center">
            选择"脱离插件管理"后，插件的卸载或升级将不再影响此资源。
          </p>
        </div>
      </div>
    </div>
  );
}

/**
 * Blocked Modification Modal
 *
 * Displays when user tries to modify a plugin_owned resource.
 */
export interface PluginResourceBlockedModalProps {
  isOpen: boolean;
  resourceType: ResourceType;
  resourceCode: string;
  resourceName?: string;
  pluginName?: string;
  onClose: () => void;
}

export function PluginResourceBlockedModal({
  isOpen,
  resourceType,
  resourceCode,
  resourceName,
  pluginName,
  onClose,
}: PluginResourceBlockedModalProps) {
  if (!isOpen) return null;

  const displayName = resourceName || resourceCode;
  const typeLabel = getResourceTypeLabel(resourceType);
  const pluginDisplayName = pluginName || '未知插件';

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 overflow-y-auto h-full w-full z-50 flex items-center justify-center">
      <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-md mx-4 transform transition-all">
        {/* Header */}
        <div className="bg-gradient-to-r from-red-500 to-red-600 rounded-t-2xl px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center">
              <XMarkIcon className="h-6 w-6 text-white mr-2" />
              <h3 className="text-lg font-semibold text-white">无法修改</h3>
            </div>
            <button
              onClick={onClose}
              className="text-white/80 hover:text-white transition-colors"
            >
              <XMarkIcon className="h-6 w-6" />
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="p-6">
          <div className="mb-6">
            <p className="text-gray-700 mb-4">
              此{typeLabel} <strong className="text-gray-900">"{displayName}"</strong> 由插件 <strong>"{pluginDisplayName}"</strong> 完全控制。
            </p>

            <div className="bg-red-50 rounded-lg p-4 border border-red-100">
              <p className="text-sm text-red-700">
                插件控制的资源不允许用户修改。如需更改，请联系插件开发者或卸载该插件。
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="w-full inline-flex justify-center items-center px-4 py-2.5 border border-transparent text-sm font-medium rounded-lg shadow-sm text-white bg-gray-600 hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-500 transition-colors"
          >
            我知道了
          </button>
        </div>
      </div>
    </div>
  );
}

export default PluginResourceWarningModal;
