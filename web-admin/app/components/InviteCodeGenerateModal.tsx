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
      <div className="relative mx-4 w-full max-w-md transform rounded-2xl bg-white shadow-2xl transition-all">
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
                <label className="mb-3 block text-sm font-medium text-gray-700">
                  选择邀请码有效期
                </label>
                <div className="grid grid-cols-3 gap-2">
                  {EXPIRY_OPTIONS.map((option) => (
                    <button
                      key={option.value}
                      onClick={() => setSelectedExpiryDays(option.value)}
                      className={`rounded-lg border px-3 py-2 text-sm transition-colors ${
                        selectedExpiryDays === option.value
                          ? 'border-blue-600 bg-blue-600 text-white'
                          : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
                      }`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* 预览信息 */}
              <div className="mb-6 rounded-lg bg-blue-50 p-4">
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
                className="w-full rounded-lg bg-gradient-to-r from-blue-600 to-blue-700 px-4 py-3 text-white transition-all duration-200 hover:from-blue-700 hover:to-blue-800 disabled:opacity-50"
              >
                {loading ? '生成中...' : '生成邀请码'}
              </button>
            </>
          ) : (
            <>
              {/* 生成成功显示 */}
              <div className="mb-6 text-center">
                <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-green-100">
                  <ShareIcon className="h-8 w-8 text-green-600" />
                </div>
                <h4 className="mb-2 text-lg font-semibold text-gray-900">邀请码生成成功！</h4>
                <p className="text-sm text-gray-600">请将邀请码分享给新成员</p>
              </div>

              {/* 邀请码展示 */}
              <div className="mb-4 rounded-lg bg-gray-50 p-4">
                <label className="mb-2 block text-sm font-medium text-gray-700">邀请码</label>
                <div className="flex items-center space-x-2">
                  <input
                    type="text"
                    value={inviteCode}
                    readOnly
                    className="flex-1 rounded-lg border border-gray-300 bg-white px-3 py-2 text-center font-mono text-lg tracking-wider"
                  />
                  <button
                    onClick={copyInviteCode}
                    className="rounded-lg bg-blue-600 px-3 py-2 text-white transition-colors hover:bg-blue-700"
                    title="复制邀请码"
                  >
                    <ClipboardDocumentIcon className="h-5 w-5" />
                  </button>
                </div>
              </div>

              {/* 邀请码信息 */}
              <div className="mb-6 rounded-lg bg-amber-50 p-4">
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
                className="w-full rounded-lg bg-gradient-to-r from-blue-600 to-blue-700 px-4 py-3 text-white transition-all duration-200 hover:from-blue-700 hover:to-blue-800"
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
