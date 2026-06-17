import {
  XMarkIcon,
  ShareIcon,
  ClipboardDocumentIcon,
  ArrowPathIcon,
  NoSymbolIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

interface InviteCodeManageModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentInviteCode: any;
  onRefresh: (days: number) => Promise<boolean>;
  onRevoke: (code: string) => Promise<boolean>;
  loading: boolean;
}

export default function InviteCodeManageModal({
  isOpen,
  onClose,
  currentInviteCode,
  onRefresh,
  onRevoke,
  loading,
}: InviteCodeManageModalProps) {
  const { showSuccessToast } = useToastContext();

  const copyInviteCode = () => {
    navigator.clipboard.writeText(currentInviteCode.code);
    showSuccessToast('邀请码已复制到剪贴板');
  };

  const handleRefresh = async () => {
    const success = await onRefresh(7); // 默认7天
    if (success) {
      showSuccessToast('邀请码已刷新');
    }
  };

  const handleRevoke = async () => {
    if (confirm('确定要作废当前邀请码吗？')) {
      const success = await onRevoke(currentInviteCode.code);
      if (success) {
        onClose();
      }
    }
  };

  if (!isOpen || !currentInviteCode) return null;

  return (
    <div className="bg-opacity-50 fixed inset-0 z-50 flex h-full w-full items-center justify-center overflow-y-auto bg-black">
      <div className="bg-panel relative mx-4 w-full max-w-md transform rounded-2xl shadow-2xl transition-all">
        {/* 模态框头部 */}
        <div className="rounded-t-2xl bg-gradient-to-r from-blue-600 to-blue-700 px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center">
              <ShareIcon className="mr-2 h-6 w-6 text-white" />
              <h3 className="text-lg font-semibold text-white">邀请码管理</h3>
            </div>
            <button onClick={onClose} className="text-white transition-colors hover:text-gray-200">
              <XMarkIcon className="h-6 w-6" />
            </button>
          </div>
        </div>

        <div className="p-6">
          {/* 当前邀请码信息 */}
          <div className="rounded-card bg-status-green-bg mb-4 p-4">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium text-green-800">当前邀请码</span>
              <span className="text-status-green text-xs">
                {new Date(currentInviteCode.expiredAt).toLocaleDateString()}
              </span>
            </div>
            <div className="flex items-center space-x-2">
              <input
                type="text"
                value={currentInviteCode.code}
                readOnly
                className="rounded-card bg-panel flex-1 border border-green-300 px-3 py-2 text-center font-mono text-lg tracking-wider"
              />
              <button
                onClick={copyInviteCode}
                className="rounded-card bg-green-600 px-3 py-2 text-white transition-colors hover:bg-green-700"
                title="复制邀请码"
              >
                <ClipboardDocumentIcon className="h-5 w-5" />
              </button>
            </div>
          </div>

          {/* 管理按钮 */}
          <div className="space-y-3">
            <button
              onClick={handleRefresh}
              disabled={loading}
              className="rounded-card bg-accent hover:bg-accent-hover flex w-full items-center justify-center px-4 py-3 text-white transition-colors disabled:opacity-50"
            >
              <ArrowPathIcon className="mr-2 h-5 w-5" />
              刷新邀请码
            </button>

            <button
              onClick={handleRevoke}
              className="rounded-card flex w-full items-center justify-center bg-red-600 px-4 py-3 text-white transition-colors hover:bg-red-700"
            >
              <NoSymbolIcon className="mr-2 h-5 w-5" />
              作废邀请码
            </button>

            <button
              onClick={onClose}
              className="rounded-card border-border-strong text-text-2 hover:bg-subtle w-full border px-4 py-3 transition-colors"
            >
              关闭
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
