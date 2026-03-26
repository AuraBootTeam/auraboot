/**
 * 文件上传按钮组件
 * 支持点击上传和拖拽上传
 */

import { useRef, useState, type ChangeEvent, type DragEvent } from 'react';
import { uploadTemporaryFile, type UploadProgress } from '~/chat/services/fileService';

interface FileUploadButtonProps {
  sessionId: string;
  onUploadSuccess?: (attachment: any) => void;
  onUploadError?: (error: string) => void;
  disabled?: boolean;
}

// 文件类型配置
const FILE_TYPES = {
  pdf: { accept: '.pdf', maxSize: 50 * 1024 * 1024, label: 'pdf' },
  excel: { accept: '.xlsx,.xls', maxSize: 20 * 1024 * 1024, label: 'Excel' },
  word: { accept: '.docx,.doc', maxSize: 20 * 1024 * 1024, label: 'Word' },
  image: { accept: '.png,.jpg,.jpeg', maxSize: 10 * 1024 * 1024, label: '图片' },
  text: { accept: '.txt', maxSize: 5 * 1024 * 1024, label: '文本' },
};

const ALLOWED_EXTENSIONS = [
  '.pdf',
  '.xlsx',
  '.xls',
  '.docx',
  '.doc',
  '.png',
  '.jpg',
  '.jpeg',
  '.txt',
];

const MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

export function FileUploadButton({
  sessionId,
  onUploadSuccess,
  onUploadError,
  disabled,
}: FileUploadButtonProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null);
  const [isDragging, setIsDragging] = useState(false);

  const validateFile = (file: File): string | null => {
    // 检查文件大小
    if (file.size > MAX_FILE_SIZE) {
      return `文件大小超过限制（最大 ${MAX_FILE_SIZE / 1024 / 1024}MB）`;
    }

    // 检查文件类型
    const extension = '.' + file.name.split('.').pop()?.toLowerCase();
    if (!ALLOWED_EXTENSIONS.includes(extension)) {
      return `不支持的文件类型：${extension}`;
    }

    // 检查具体类型的大小限制
    if (extension === '.pdf' && file.size > FILE_TYPES.pdf.maxSize) {
      return `PDF 文件大小超过限制（最大 ${FILE_TYPES.pdf.maxSize / 1024 / 1024}MB）`;
    }
    if (
      ['.xlsx', '.xls', '.docx', '.doc'].includes(extension) &&
      file.size > FILE_TYPES.excel.maxSize
    ) {
      return `文档文件大小超过限制（最大 ${FILE_TYPES.excel.maxSize / 1024 / 1024}MB）`;
    }
    if (['.png', '.jpg', '.jpeg'].includes(extension) && file.size > FILE_TYPES.image.maxSize) {
      return `图片文件大小超过限制（最大 ${FILE_TYPES.image.maxSize / 1024 / 1024}MB）`;
    }
    if (extension === '.txt' && file.size > FILE_TYPES.text.maxSize) {
      return `文本文件大小超过限制（最大 ${FILE_TYPES.text.maxSize / 1024 / 1024}MB）`;
    }

    return null;
  };

  const handleFileUpload = async (file: File) => {
    // 验证文件
    const error = validateFile(file);
    if (error) {
      onUploadError?.(error);
      return;
    }

    setIsUploading(true);
    setUploadProgress({ loaded: 0, total: file.size, percentage: 0 });

    try {
      const result = await uploadTemporaryFile(file, sessionId, (progress) => {
        setUploadProgress(progress);
      });

      onUploadSuccess?.(result);
    } catch (err: any) {
      console.error('Upload error:', err);
      onUploadError?.(err.message || '上传失败');
    } finally {
      setIsUploading(false);
      setUploadProgress(null);
    }
  };

  const handleFileSelect = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      handleFileUpload(file);
    }
    // 清空 input，允许重复上传同一文件
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handleClick = () => {
    if (!disabled && !isUploading) {
      fileInputRef.current?.click();
    }
  };

  const handleDragEnter = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!disabled && !isUploading) {
      setIsDragging(true);
    }
  };

  const handleDragLeave = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  };

  const handleDragOver = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
  };

  const handleDrop = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);

    if (disabled || isUploading) {
      return;
    }

    const file = e.dataTransfer.files?.[0];
    if (file) {
      handleFileUpload(file);
    }
  };

  return (
    <div
      className="relative"
      onDragEnter={handleDragEnter}
      onDragLeave={handleDragLeave}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
    >
      <input
        ref={fileInputRef}
        type="file"
        accept={ALLOWED_EXTENSIONS.join(',')}
        onChange={handleFileSelect}
        className="hidden"
      />

      <button
        onClick={handleClick}
        disabled={disabled || isUploading}
        className={`rounded-lg p-2 transition-colors ${
          isDragging ? 'bg-blue-100 text-blue-600' : 'text-gray-600 hover:bg-gray-100'
        } ${disabled || isUploading ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}
        title="上传文件"
      >
        {isUploading ? (
          <div className="flex items-center space-x-2">
            <svg className="h-5 w-5 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              />
            </svg>
            {uploadProgress && <span className="text-xs">{uploadProgress.percentage}%</span>}
          </div>
        ) : (
          <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
            />
          </svg>
        )}
      </button>

      {/* 拖拽提示 */}
      {isDragging && (
        <div className="bg-opacity-90 pointer-events-none absolute inset-0 flex items-center justify-center rounded-lg border-2 border-dashed border-blue-400 bg-blue-50">
          <div className="font-medium text-blue-600">释放以上传文件</div>
        </div>
      )}
    </div>
  );
}
