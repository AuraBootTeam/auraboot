import { useState } from 'react';
import {
  XMarkIcon,
  ShareIcon,
  CalendarDaysIcon,
  ClipboardDocumentIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

interface InviteCodeGenerateModalProps {
  isOpen: boolean;
  onClose: () => void;
  onGenerate: (days: number) => Promise<boolean>;
  inviteCode: string;
  loading: boolean;
}

const EXPIRY_OPTIONS = [
  { value: 1, label: '1天' },
  { value: 3, label: '3天' },
  { value: 7, label: '7天' },
  { value: 15, label: '15天' },
  { value: 30, label: '30天' },
];

export default function InviteCodeGenerateModal({
  isOpen,
  onClose,
  onGenerate,
  inviteCode,
  loading,
}: InviteCodeGenerateModalProps) {
  const [selectedExpiryDays, setSelectedExpiryDays] = useState(7);
  const [isGenerated, setIsGenerated] = useState(false);
  const { showSuccessToast } = useToastContext();

  const handleGenerate = async () => {
    const success = await onGenerate(selectedExpiryDays);
    if (success) {
      setIsGenerated(true);
    }
  };

  const copyInviteCode = () => {
    navigator.clipboard.writeText(inviteCode);
    showSuccessToast('邀请码已复制到剪贴板');
  };

  const getExpiryDate = (days: number) => {
    const date = new Date();
    date.setDate(date.getDate() + days);
    return date.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  };

  const handleClose = () => {
    setIsGenerated(false);
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="bg-opacity-50 fixed inset-0 z-50 flex h-full w-full items-center justify-center overflow-y-auto bg-black">
      <div className="bg-panel relative mx-4 w-full max-w-md transform rounded-2xl shadow-2xl transition-all">
        {/* 模态框头部 */}
        <div className="rounded-t-2xl bg-gradient-to-r from-blue-600 to-blue-700 px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center">
              <ShareIcon className="mr-2 h-6 w-6 text-white" />
              <h3 className="text-lg font-semibold text-white">生成邀请码</h3>
            </div>
            <button
              onClick={handleClose}
              className="text-white transition-colors hover:text-gray-200"
            >
              <XMarkIcon className="h-6 w-6" />
            </button>
          </div>
        </div>

        <div className="p-6">
          {!isGenerated ? (
            <>
              {/* 有效期选择 */}
              <div className="mb-6">
                <label className="text-text-2 mb-3 block text-sm font-medium">
                  选择邀请码有效期
                </label>
                <div className="grid grid-cols-3 gap-2">
                  {EXPIRY_OPTIONS.map((option) => (
                    <button
                      key={option.value}
                      onClick={() => setSelectedExpiryDays(option.value)}
                      className={`rounded-card border px-3 py-2 text-sm transition-colors ${
                        selectedExpiryDays === option.value
                          ? 'border-accent bg-accent text-white'
                          : 'border-border-strong bg-panel text-text-2 hover:bg-subtle'
                      }`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* 预览信息 */}
              <div className="rounded-card bg-accent-weak mb-6 p-4">
                <div className="flex items-center text-blue-800">
                  <CalendarDaysIcon className="mr-2 h-4 w-4" />
                  <span className="text-sm">
                    邀请码将于 {getExpiryDate(selectedExpiryDays)} 过期
                  </span>
                </div>
              </div>

              {/* 生成按钮 */}
              <button
                onClick={handleGenerate}
                disabled={loading}
                className="rounded-card w-full bg-gradient-to-r from-blue-600 to-blue-700 px-4 py-3 text-white transition-all duration-200 hover:from-blue-700 hover:to-blue-800 disabled:opacity-50"
              >
                {loading ? '生成中...' : '生成邀请码'}
              </button>
            </>
          ) : (
            <>
              {/* 生成成功显示 */}
              <div className="mb-6 text-center">
                <div className="rounded-pill mx-auto mb-4 flex h-16 w-16 items-center justify-center bg-green-100">
                  <ShareIcon className="text-status-green h-8 w-8" />
                </div>
                <h4 className="text-text mb-2 text-lg font-semibold">邀请码生成成功！</h4>
                <p className="text-text-2 text-sm">请将邀请码分享给新成员</p>
              </div>

              {/* 邀请码展示 */}
              <div className="rounded-card bg-subtle mb-4 p-4">
                <label className="text-text-2 mb-2 block text-sm font-medium">邀请码</label>
                <div className="flex items-center space-x-2">
                  <input
                    type="text"
                    value={inviteCode}
                    readOnly
                    className="rounded-card border-border-strong bg-panel flex-1 border px-3 py-2 text-center font-mono text-lg tracking-wider"
                  />
                  <button
                    onClick={copyInviteCode}
                    className="rounded-card bg-accent hover:bg-accent-hover px-3 py-2 text-white transition-colors"
                    title="复制邀请码"
                  >
                    <ClipboardDocumentIcon className="h-5 w-5" />
                  </button>
                </div>
              </div>

              {/* 邀请码信息 */}
              <div className="rounded-card bg-status-amber-bg mb-6 p-4">
                <div className="flex items-center text-amber-800">
                  <CalendarDaysIcon className="mr-2 h-4 w-4" />
                  <span className="text-sm font-medium">
                    有效期至：{getExpiryDate(selectedExpiryDays)}
                  </span>
                </div>
              </div>

              {/* 完成按钮 */}
              <button
                onClick={handleClose}
                className="rounded-card w-full bg-gradient-to-r from-blue-600 to-blue-700 px-4 py-3 text-white transition-all duration-200 hover:from-blue-700 hover:to-blue-800"
              >
                完成
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
