import React, { useState, useRef, useCallback } from 'react';
import type { FileItem, FileUploadConfig } from './types';

interface FileUploadProps extends FileUploadConfig {
  value?: FileItem[];
  onChange?: (files: FileItem[]) => void;
  disabled?: boolean;
  className?: string;
}

/**
 * FileUpload - file upload component with drag & drop, progress, and preview.
 * Supports configurable upload URL, file type filtering, and size limits.
 *
 * @since 3.7.0
 */
export const FileUpload: React.FC<FileUploadProps> = ({
  value = [],
  onChange,
  accept,
  maxSize = 10 * 1024 * 1024, // 10MB default
  maxCount = 5,
  multiple = true,
  uploadUrl = '/api/file/upload',
  headers = {},
  listType = 'text',
  disabled = false,
  className = '',
}) => {
  const [files, setFiles] = useState<FileItem[]>(value);
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const updateFiles = useCallback(
    (newFiles: FileItem[]) => {
      setFiles(newFiles);
      onChange?.(newFiles);
    },
    [onChange],
  );

  const handleFileSelect = useCallback(
    (selectedFiles: FileList | null) => {
      if (!selectedFiles || disabled) return;

      const remaining = maxCount - files.length;
      const toAdd = Array.from(selectedFiles).slice(0, remaining);
      const newItems: FileItem[] = [];

      for (const file of toAdd) {
        if (file.size > maxSize) {
          newItems.push({
            uid: generateUid(),
            name: file.name,
            size: file.size,
            type: file.type,
            status: 'error',
            errorMessage: `文件过大 (最大 ${formatSize(maxSize)})`,
          });
          continue;
        }

        const item: FileItem = {
          uid: generateUid(),
          name: file.name,
          size: file.size,
          type: file.type,
          status: 'uploading',
          progress: 0,
        };

        newItems.push(item);
        uploadFile(file, item.uid);
      }

      updateFiles([...files, ...newItems]);
    },
    [files, maxCount, maxSize, disabled, uploadUrl, headers, updateFiles],
  );

  const uploadFile = useCallback(
    (file: File, uid: string) => {
      const xhr = new XMLHttpRequest();
      const formData = new FormData();
      formData.append('file', file);

      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          const progress = Math.round((e.loaded / e.total) * 100);
          setFiles((prev) => prev.map((f) => (f.uid === uid ? { ...f, progress } : f)));
        }
      };

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const response = JSON.parse(xhr.responseText);
            const url = response.data?.url ?? response.url ?? '';
            setFiles((prev) => {
              const next = prev.map((f) =>
                f.uid === uid ? { ...f, status: 'done' as const, progress: 100, url } : f,
              );
              onChange?.(next);
              return next;
            });
          } catch {
            markError(uid, '响应解析失败');
          }
        } else {
          markError(uid, `上传失败 (${xhr.status})`);
        }
      };

      xhr.onerror = () => markError(uid, '网络错误');

      xhr.open('post', uploadUrl);
      for (const [key, val] of Object.entries(headers)) {
        xhr.setRequestHeader(key, val);
      }
      xhr.send(formData);
    },
    [uploadUrl, headers, onChange],
  );

  const markError = useCallback(
    (uid: string, message: string) => {
      setFiles((prev) => {
        const next = prev.map((f) =>
          f.uid === uid ? { ...f, status: 'error' as const, errorMessage: message } : f,
        );
        onChange?.(next);
        return next;
      });
    },
    [onChange],
  );

  const removeFile = useCallback(
    (uid: string) => {
      updateFiles(files.filter((f) => f.uid !== uid));
    },
    [files, updateFiles],
  );

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    if (!disabled) setDragging(true);
  };

  const handleDragLeave = () => setDragging(false);

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    handleFileSelect(e.dataTransfer.files);
  };

  const canUploadMore = files.length < maxCount && !disabled;

  return (
    <div className={`space-y-2 ${className}`}>
      {/* Upload area */}
      {canUploadMore && (
        <div
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          onClick={() => inputRef.current?.click()}
          className={`flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed px-4 py-4 transition-colors ${
            dragging
              ? 'border-blue-400 bg-blue-50'
              : 'border-gray-300 hover:border-blue-300 hover:bg-gray-50'
          }`}
        >
          <svg
            className="mb-1 h-8 w-8 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
            />
          </svg>
          <p className="text-xs text-gray-500">点击或拖拽上传</p>
          <p className="mt-0.5 text-[10px] text-gray-400">
            {accept ? `支持 ${accept}` : '支持所有格式'}
            {` / 最大 ${formatSize(maxSize)}`}
            {maxCount > 1 && ` / 最多 ${maxCount} 个`}
          </p>
        </div>
      )}

      <input
        ref={inputRef}
        type="file"
        accept={accept}
        multiple={multiple}
        onChange={(e) => handleFileSelect(e.target.files)}
        className="hidden"
      />

      {/* File list */}
      {files.length > 0 && (
        <div className="space-y-1">
          {files.map((file) => (
            <FileListItem
              key={file.uid}
              file={file}
              listType={listType}
              onRemove={() => removeFile(file.uid)}
              disabled={disabled}
            />
          ))}
        </div>
      )}
    </div>
  );
};

const FileListItem: React.FC<{
  file: FileItem;
  listType: string;
  onRemove: () => void;
  disabled: boolean;
}> = ({ file, onRemove, disabled }) => {
  return (
    <div className="group flex items-center gap-2 rounded bg-gray-50 px-2 py-1.5">
      {/* Icon */}
      <FileIcon type={file.type} />

      {/* Info */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-xs text-gray-700">{file.name}</span>
          <span className="shrink-0 text-[10px] text-gray-400">{formatSize(file.size)}</span>
        </div>
        {file.status === 'uploading' && (
          <div className="mt-0.5 h-1 overflow-hidden rounded bg-gray-200">
            <div
              className="h-full bg-blue-500 transition-all"
              style={{ width: `${file.progress ?? 0}%` }}
            />
          </div>
        )}
        {file.status === 'error' && (
          <p className="mt-0.5 text-[10px] text-red-500">{file.errorMessage}</p>
        )}
      </div>

      {/* Status & actions */}
      {file.status === 'done' && (
        <svg
          className="h-4 w-4 shrink-0 text-green-500"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
        </svg>
      )}
      {!disabled && (
        <button
          onClick={onRemove}
          className="shrink-0 p-0.5 text-gray-400 opacity-0 group-hover:opacity-100 hover:text-red-500"
        >
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      )}
    </div>
  );
};

const FileIcon: React.FC<{ type: string }> = ({ type }) => {
  const isImage = type.startsWith('image/');
  return (
    <svg
      className={`h-4 w-4 shrink-0 ${isImage ? 'text-purple-400' : 'text-gray-400'}`}
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
    >
      {isImage ? (
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
        />
      ) : (
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
        />
      )}
    </svg>
  );
};

let fileUidCounter = 0;

function generateUid(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return `file_${cryptoApi.randomUUID()}`;
  }

  const bytes = new Uint32Array(2);
  cryptoApi?.getRandomValues?.(bytes);
  return `file_${Date.now()}_${bytes[0].toString(36)}${bytes[1].toString(36)}_${fileUidCounter++}`;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}
