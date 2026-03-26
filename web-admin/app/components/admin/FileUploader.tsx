/**
 * 文件上传组件
 * 支持拖拽上传和点击上传
 */

import { useState, useRef, type DragEvent, type ChangeEvent } from 'react';
import type { DocumentType } from '~/services/documentService';

interface FileUploaderProps {
  onFileSelect: (file: File, metadata: FileMetadata) => void;
  disabled?: boolean;
}

export interface FileMetadata {
  document_type: DocumentType;
  symbol?: string;
  broker?: string;
  publish_date?: string;
}

const MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
const ALLOWED_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-excel',
];

export function FileUploader({ onFileSelect, disabled }: FileUploaderProps) {
  const [isDragging, setIsDragging] = useState(false);
  const [showMetadataForm, setShowMetadataForm] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [metadata, setMetadata] = useState<FileMetadata>({
    document_type: 'research_report',
  });
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const validateFile = (file: File): string | null => {
    if (file.size > MAX_FILE_SIZE) {
      return `文件大小超过限制（最大 50MB）`;
    }
    if (!ALLOWED_TYPES.includes(file.type)) {
      return `不支持的文件类型（仅支持 PDF、Word、Excel）`;
    }
    return null;
  };

  const handleDragEnter = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    if (!disabled) {
      setIsDragging(true);
    }
  };

  const handleDragLeave = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  };

  const handleDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);

    if (disabled) return;

    const files = Array.from(e.dataTransfer.files);
    if (files.length > 0) {
      handleFileSelection(files[0]);
    }
  };

  const handleFileInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (files && files.length > 0) {
      handleFileSelection(files[0]);
    }
  };

  const handleFileSelection = (file: File) => {
    const validationError = validateFile(file);
    if (validationError) {
      setError(validationError);
      return;
    }

    setError(null);
    setSelectedFile(file);
    setShowMetadataForm(true);
  };

  const handleClickUpload = () => {
    if (!disabled) {
      fileInputRef.current?.click();
    }
  };

  const handleSubmit = () => {
    if (selectedFile) {
      onFileSelect(selectedFile, metadata);
      // 重置状态
      setSelectedFile(null);
      setShowMetadataForm(false);
      setMetadata({ document_type: 'research_report' });
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    }
  };

  const handleCancel = () => {
    setSelectedFile(null);
    setShowMetadataForm(false);
    setError(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  return (
    <div className="space-y-4">
      {/* 拖拽上传区域 */}
      {!showMetadataForm && (
        <div
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          onClick={handleClickUpload}
          className={`cursor-pointer rounded-lg border-2 border-dashed p-12 text-center transition-colors duration-200 ${isDragging ? 'border-blue-500 bg-blue-50' : 'border-gray-300 hover:border-gray-400'} ${disabled ? 'cursor-not-allowed opacity-50' : ''} `}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".pdf,.doc,.docx,.xls,.xlsx"
            onChange={handleFileInputChange}
            className="hidden"
            disabled={disabled}
          />
          <div className="space-y-2">
            <div className="text-4xl">📁</div>
            <div className="text-lg font-medium text-gray-700">
              {isDragging ? '释放文件以上传' : '拖拽文件到此处或点击上传'}
            </div>
            <div className="text-sm text-gray-500">支持 PDF、Word、Excel 文件，最大 50MB</div>
          </div>
        </div>
      )}

      {/* 错误提示 */}
      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <div className="flex items-center space-x-2">
            <span className="text-red-600">⚠️</span>
            <span className="text-red-700">{error}</span>
          </div>
        </div>
      )}

      {/* 元数据表单 */}
      {showMetadataForm && selectedFile && (
        <div className="space-y-4 rounded-lg border bg-white p-6">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold">文件信息</h3>
            <button onClick={handleCancel} className="text-gray-400 hover:text-gray-600">
              ✕
            </button>
          </div>

          {/* 文件信息 */}
          <div className="rounded bg-gray-50 p-3">
            <div className="flex items-center space-x-3">
              <span className="text-2xl">📄</span>
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium text-gray-900">{selectedFile.name}</div>
                <div className="text-sm text-gray-500">
                  {(selectedFile.size / 1024 / 1024).toFixed(2)} MB
                </div>
              </div>
            </div>
          </div>

          {/* 文档类型 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">
              文档类型 <span className="text-red-500">*</span>
            </label>
            <select
              value={metadata.document_type}
              onChange={(e) =>
                setMetadata({ ...metadata, document_type: e.target.value as DocumentType })
              }
              className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
            >
              <option value="research_report">研究报告</option>
              <option value="disclosure">信息披露</option>
              <option value="news">新闻资讯</option>
              <option value="user_note">用户笔记</option>
            </select>
          </div>

          {/* 股票代码 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">股票代码</label>
            <input
              type="text"
              value={metadata.symbol || ''}
              onChange={(e) => setMetadata({ ...metadata, symbol: e.target.value })}
              placeholder="例如：600519"
              className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* 券商/机构 */}
          {metadata.document_type === 'research_report' && (
            <div>
              <label className="mb-2 block text-sm font-medium text-gray-700">券商/机构</label>
              <input
                type="text"
                value={metadata.broker || ''}
                onChange={(e) => setMetadata({ ...metadata, broker: e.target.value })}
                placeholder="例如：中金公司"
                className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
              />
            </div>
          )}

          {/* 发布日期 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">发布日期</label>
            <input
              type="date"
              value={metadata.publish_date || ''}
              onChange={(e) => setMetadata({ ...metadata, publish_date: e.target.value })}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* 操作按钮 */}
          <div className="flex space-x-3 pt-4">
            <button
              onClick={handleSubmit}
              className="flex-1 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
            >
              开始上传
            </button>
            <button
              onClick={handleCancel}
              className="rounded-lg border border-gray-300 px-4 py-2 transition-colors hover:bg-gray-50"
            >
              取消
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
