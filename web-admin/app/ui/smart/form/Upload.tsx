/**
 * Upload Component
 *
 * File upload component with drag-drop, multi-file, and preview support.
 *
 * @since 3.2.0
 */

import React, { useRef, useState, useCallback } from 'react';
import type { UploadProps, UploadFile } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/ui/ui/field-base';
import { FilePreviewModal } from '~/framework/smart/components/common/FilePreviewModal';

let uploadUidCounter = 0;

function generateUploadUid(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return `upload-${cryptoApi.randomUUID()}`;
  }

  const bytes = new Uint32Array(2);
  cryptoApi?.getRandomValues?.(bytes);
  return `upload-${Date.now()}-${bytes[0].toString(36)}${bytes[1].toString(36)}-${uploadUidCounter++}`;
}

const Upload: React.FC<UploadProps> = ({
  name,
  label,
  value,
  defaultValue,
  required = false,
  disabled = false,
  action = '/api/file/upload',
  accept = '',
  multiple = false,
  maxCount = 1,
  maxSize = 10,
  listType = 'text',
  showUploadList = true,
  draggable = false,
  buttonText = '点击上传',
  hint = '',
  headers = {},
  className = '',
  validationRules = [],
  context,
  expressions = {},
  visible,
  onChange,
  onBlur,
  onRemove,
  onPreview,
}) => {
  const st = useSmartText();
  const inputRef = useRef<HTMLInputElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [previewFile, setPreviewFile] = useState<{
    url: string;
    name: string;
    type: string;
  } | null>(null);

  const {
    labelText,
    helpText: helpTextText,
    required: requiredValue,
    disabled: disabledValue,
    visible: isVisible,
  } = useSmartFieldContract({
    label,
    required,
    disabled,
    expressions,
    context,
    visible,
  });

  const field = useSmartField<UploadFile[]>({
    name,
    value: value || defaultValue || [],
    defaultValue: [],
    required: requiredValue,
    validationRules,
    context,
    onChange,
    onBlur,
  });

  const meta = useSmartFieldMeta({ field });
  const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

  const fileList = field.value || [];
  const fileListRef = useRef<UploadFile[]>(fileList);
  fileListRef.current = fileList;

  // Format file size
  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  // Validate file
  const validateFile = useCallback(
    (file: File): string | null => {
      // Check size
      if (maxSize && file.size > maxSize * 1024 * 1024) {
        return `File size exceeds ${maxSize}MB limit`;
      }
      // Check type
      if (accept) {
        const acceptTypes = accept.split(',').map((t) => t.trim().toLowerCase());
        const fileExt = '.' + file.name.split('.').pop()?.toLowerCase();
        const fileMime = file.type.toLowerCase();

        const isAccepted = acceptTypes.some((type) => {
          if (type.startsWith('.')) return fileExt === type;
          if (type.endsWith('/*')) return fileMime.startsWith(type.slice(0, -1));
          return fileMime === type;
        });

        if (!isAccepted) {
          return `Unsupported file type. Please upload ${accept} files`;
        }
      }
      return null;
    },
    [maxSize, accept],
  );

  // Handle file selection
  const handleFiles = useCallback(
    async (files: FileList | null) => {
      if (!files || files.length === 0 || disabledValue) return;

      const newFiles: UploadFile[] = [];
      const currentCount = fileList.length;

      for (let i = 0; i < files.length; i++) {
        if (!multiple && currentCount + newFiles.length >= 1) break;
        if (maxCount && currentCount + newFiles.length >= maxCount) break;

        const file = files[i];
        const error = validateFile(file);

        if (error) {
          console.warn(error);
          continue;
        }

        // Create upload file object
        const uploadFile: UploadFile = {
          uid: generateUploadUid(),
          name: file.name,
          size: file.size,
          type: file.type,
          status: 'uploading',
          percent: 0,
        };

        // Create thumbnail for images
        if (file.type.startsWith('image/') && listType !== 'text') {
          uploadFile.thumbUrl = URL.createObjectURL(file);
        }

        newFiles.push(uploadFile);

        // Upload file to server
        const formData = new FormData();
        formData.append('file', file);

        fetch(action, {
          method: 'post',
          body: formData,
          credentials: 'include',
          headers: headers as Record<string, string>,
        })
          .then(async (resp) => {
            const result = await resp.json().catch(() => ({}));
            const updatedList = [...fileListRef.current];
            const idx = updatedList.findIndex((f) => f.uid === uploadFile.uid);
            if (idx !== -1) {
              if (resp.ok && (result.code === '0' || result.success)) {
                const fileData = result.data || result;
                updatedList[idx] = {
                  ...updatedList[idx],
                  status: 'done',
                  percent: 100,
                  url:
                    fileData.url ||
                    fileData.downloadUrl ||
                    `/api/file/download/${fileData.fileId || fileData.pid || ''}`,
                  response: fileData,
                };
              } else {
                updatedList[idx] = {
                  ...updatedList[idx],
                  status: 'error',
                  percent: 0,
                  error: result.message || result.desc || 'Upload failed',
                };
              }
              field.setValue(updatedList);
            }
          })
          .catch(() => {
            const updatedList = [...fileListRef.current];
            const idx = updatedList.findIndex((f) => f.uid === uploadFile.uid);
            if (idx !== -1) {
              updatedList[idx] = { ...updatedList[idx], status: 'error', percent: 0 };
              field.setValue(updatedList);
            }
          });
      }

      if (newFiles.length > 0) {
        field.setValue([...fileList, ...newFiles]);
      }
    },
    [fileList, multiple, maxCount, disabledValue, validateFile, field],
  );

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    handleFiles(e.target.files);
    if (inputRef.current) {
      inputRef.current.value = '';
    }
  };

  const handleRemove = async (file: UploadFile) => {
    if (onRemove) {
      const result = await onRemove(file);
      if (result === false) return;
    }
    const newList = fileList.filter((f) => f.uid !== file.uid);
    field.setValue(newList);
  };

  const handlePreview = (file: UploadFile) => {
    if (onPreview) {
      onPreview(file);
    } else if (file.url || file.thumbUrl) {
      setPreviewFile({
        url: file.url || file.thumbUrl || '',
        name: file.name,
        type: file.type || '',
      });
    }
  };

  // Drag handlers
  const handleDragEnter = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!disabledValue && draggable) {
      setIsDragging(true);
    }
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
    if (!disabledValue && draggable) {
      handleFiles(e.dataTransfer.files);
    }
  };

  if (!isVisible) {
    return null;
  }

  const canUploadMore = !maxCount || fileList.length < maxCount;

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      helpText={helpTextText}
      error={meta.showError ? errorText : undefined}
      className="mb-4"
    >
      <div className={className}>
        {/* Upload area */}
        {canUploadMore && (
          <div
            className={`relative rounded-lg border-2 border-dashed transition-colors ${isDragging ? 'border-blue-500 bg-blue-50' : 'border-gray-300 hover:border-blue-400'} ${disabledValue ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} ${listType === 'picture-card' ? 'inline-flex h-28 w-28' : 'p-4'} `}
            data-testid={`upload-area-${name}`}
            onDragEnter={handleDragEnter}
            onDragLeave={handleDragLeave}
            onDragOver={handleDragOver}
            onDrop={handleDrop}
            onClick={() => !disabledValue && inputRef.current?.click()}
          >
            <input
              ref={inputRef}
              type="file"
              accept={accept}
              multiple={multiple}
              disabled={disabledValue}
              className="hidden"
              data-testid={`upload-input-${name}`}
              onChange={handleInputChange}
            />

            {listType === 'picture-card' ? (
              <div className="flex h-full w-full flex-col items-center justify-center text-gray-400">
                <svg className="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 4v16m8-8H4"
                  />
                </svg>
                <span className="mt-1 text-xs">Upload</span>
              </div>
            ) : (
              <div className="flex flex-col items-center text-center">
                <svg
                  className="mb-2 h-10 w-10 text-gray-400"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                  />
                </svg>
                <p className="text-sm text-gray-600">{buttonText}</p>
                {draggable && (
                  <p className="mt-1 text-xs text-gray-400">or drag & drop files here</p>
                )}
                {hint && <p className="mt-1 text-xs text-gray-400">{hint}</p>}
              </div>
            )}
          </div>
        )}

        {/* File list */}
        {showUploadList && fileList.length > 0 && (
          <div
            className={`mt-3 ${listType === 'picture-card' ? 'flex flex-wrap gap-2' : 'space-y-2'}`}
          >
            {fileList.map((file) => (
              <div
                key={file.uid}
                className={`group relative ${
                  listType === 'picture-card'
                    ? 'h-28 w-28 overflow-hidden rounded-lg border'
                    : 'flex items-center gap-3 rounded-lg bg-gray-50 p-2'
                } `}
              >
                {/* Thumbnail or icon */}
                {listType !== 'text' && file.thumbUrl ? (
                  <img
                    src={file.thumbUrl}
                    alt={file.name}
                    className={
                      listType === 'picture-card'
                        ? 'h-full w-full object-cover'
                        : 'h-10 w-10 rounded object-cover'
                    }
                    onClick={() => handlePreview(file)}
                  />
                ) : (
                  <div
                    className={`flex items-center justify-center bg-gray-100 text-gray-400 ${listType === 'picture-card' ? 'h-full w-full' : 'h-10 w-10 rounded'} `}
                  >
                    <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                      />
                    </svg>
                  </div>
                )}

                {/* File info */}
                {listType !== 'picture-card' && (
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm text-gray-700">{file.name}</p>
                    <p className="text-xs text-gray-400">
                      {file.size && formatSize(file.size)}
                      {file.status === 'uploading' && ` - ${file.percent || 0}%`}
                    </p>
                  </div>
                )}

                {/* Status */}
                {file.status === 'uploading' && (
                  <div
                    className={`absolute inset-0 flex items-center justify-center bg-black/30 ${listType === 'picture-card' ? '' : 'rounded-lg'} `}
                  >
                    <div className="h-5 w-5 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  </div>
                )}

                {file.status === 'error' && (
                  <div
                    className={`absolute inset-0 flex items-center justify-center bg-red-500/30 ${listType === 'picture-card' ? '' : 'rounded-lg'} `}
                  >
                    <svg
                      className="h-6 w-6 text-white"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M6 18L18 6M6 6l12 12"
                      />
                    </svg>
                  </div>
                )}

                {/* Remove button */}
                {!disabledValue && file.status !== 'uploading' && (
                  <button
                    type="button"
                    data-testid="btn-remove-file"
                    onClick={() => handleRemove(file)}
                    className={` ${
                      listType === 'picture-card'
                        ? 'absolute top-1 right-1 opacity-0 group-hover:opacity-100'
                        : ''
                    } rounded-full bg-white/80 p-1 text-gray-500 transition-all hover:bg-white hover:text-red-500`}
                  >
                    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
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
            ))}
          </div>
        )}

        {/* Upload limit hint */}
        {maxCount && (
          <p className="mt-2 text-xs text-gray-500">
            {fileList.length}/{maxCount} files uploaded
          </p>
        )}
      </div>

      {/* File preview modal */}
      {previewFile && (
        <FilePreviewModal
          open={!!previewFile}
          onClose={() => setPreviewFile(null)}
          fileUrl={previewFile.url}
          fileName={previewFile.name}
          fileType={previewFile.type}
        />
      )}
    </FieldBase>
  );
};

export { Upload };
export default Upload;
