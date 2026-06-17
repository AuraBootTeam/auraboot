/**
 * Upload Component
 *
 * File upload component with drag-drop, multi-file, and preview support.
 *
 * @since 3.2.0
 */

import React, { useRef, useState, useCallback } from 'react';
import type {
  UploadProps,
  UploadFile,
} from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { useI18n } from '~/contexts/I18nContext';
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

export interface UploadRejection {
  code: 'maxSize' | 'fileType';
  params: Record<string, string | number>;
}

/**
 * Pure file validator — returns a structured rejection (i18n code + params)
 * instead of a hardcoded string, so the component renders a localized inline
 * error (standard §4: inline validation, not toast). `maxSize` is in MB.
 */
export function validateUploadFile(
  file: { name: string; size: number; type: string },
  opts: { maxSize: number; accept: string },
): UploadRejection | null {
  const { maxSize, accept } = opts;
  if (maxSize && file.size > maxSize * 1024 * 1024) {
    return { code: 'maxSize', params: { max: maxSize } };
  }
  if (accept) {
    const acceptTypes = accept.split(',').map((t) => t.trim().toLowerCase());
    const fileExt = '.' + (file.name.split('.').pop()?.toLowerCase() ?? '');
    const fileMime = (file.type || '').toLowerCase();
    const isAccepted = acceptTypes.some((type) => {
      if (type.startsWith('.')) return fileExt === type;
      if (type.endsWith('/*')) return fileMime.startsWith(type.slice(0, -1));
      return fileMime === type;
    });
    if (!isAccepted) return { code: 'fileType', params: { accept } };
  }
  return null;
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
  const { t } = useI18n();
  const inputRef = useRef<HTMLInputElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  // Inline rejection messages (size/type) — shown in the area, not toasted.
  const [rejections, setRejections] = useState<string[]>([]);

  const rejectionMessage = useCallback(
    (r: UploadRejection): string =>
      r.code === 'maxSize'
        ? t('upload.error.maxSize', r.params, `文件大小超过 ${r.params.max}MB 限制`)
        : t('upload.error.fileType', r.params, `不支持的文件类型,请上传 ${r.params.accept} 格式`),
    [t],
  );
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

  const uploadFailedText = t('upload.error.failed', undefined, '上传失败');

  // Send one file to the server and reconcile its status by uid. Reused by the
  // initial upload and by retry.
  const uploadToServer = useCallback(
    (uploadFile: UploadFile, file: File) => {
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
          if (idx === -1) return;
          if (resp.ok && (result.code === '0' || result.success)) {
            const fileData = result.data || result;
            const uploadedFileId = fileData.fileId || fileData.pid || '';
            updatedList[idx] = {
              ...updatedList[idx],
              status: 'done',
              percent: 100,
              url: uploadedFileId
                ? `/api/file/download/${encodeURIComponent(String(uploadedFileId))}`
                : fileData.downloadUrl || fileData.url,
              response: fileData,
              error: undefined,
            };
          } else {
            updatedList[idx] = {
              ...updatedList[idx],
              status: 'error',
              percent: 0,
              error: result.message || result.desc || uploadFailedText,
            };
          }
          field.setValue(updatedList);
        })
        .catch(() => {
          const updatedList = [...fileListRef.current];
          const idx = updatedList.findIndex((f) => f.uid === uploadFile.uid);
          if (idx === -1) return;
          updatedList[idx] = {
            ...updatedList[idx],
            status: 'error',
            percent: 0,
            error: uploadFailedText,
          };
          field.setValue(updatedList);
        });
    },
    [action, headers, field, uploadFailedText],
  );

  // Handle file selection
  const handleFiles = useCallback(
    async (files: FileList | null) => {
      if (!files || files.length === 0 || disabledValue) return;
      setRejections([]);

      const newFiles: UploadFile[] = [];
      const newRejections: string[] = [];
      const currentCount = fileList.length;

      for (let i = 0; i < files.length; i++) {
        if (!multiple && currentCount + newFiles.length >= 1) break;
        if (maxCount && currentCount + newFiles.length >= maxCount) break;

        const file = files[i];
        const rejection = validateUploadFile(file, { maxSize, accept });
        if (rejection) {
          // Inline validation: show in the area, don't silently console.warn.
          newRejections.push(`${file.name}: ${rejectionMessage(rejection)}`);
          continue;
        }

        const uploadFile: UploadFile = {
          uid: generateUploadUid(),
          name: file.name,
          size: file.size,
          type: file.type,
          status: 'uploading',
          percent: 0,
        };
        // Retain the original File so a failed upload can be retried.
        (uploadFile as { originFileObj?: File }).originFileObj = file;

        if (file.type.startsWith('image/') && listType !== 'text') {
          uploadFile.thumbUrl = URL.createObjectURL(file);
        }

        newFiles.push(uploadFile);
        uploadToServer(uploadFile, file);
      }

      if (newRejections.length > 0) setRejections(newRejections);
      if (newFiles.length > 0) {
        field.setValue([...fileList, ...newFiles]);
      }
    },
    [
      fileList,
      multiple,
      maxCount,
      disabledValue,
      maxSize,
      accept,
      listType,
      field,
      rejectionMessage,
      uploadToServer,
    ],
  );

  // Retry a failed upload using the retained original File.
  const handleRetry = useCallback(
    (file: UploadFile) => {
      const origin = (file as { originFileObj?: File }).originFileObj;
      if (!origin) return;
      const updatedList = fileListRef.current.map((f) =>
        f.uid === file.uid
          ? { ...f, status: 'uploading' as const, percent: 0, error: undefined }
          : f,
      );
      field.setValue(updatedList);
      uploadToServer(file, origin);
    },
    [field, uploadToServer],
  );

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    handleFiles(e.target.files);
    if (inputRef.current) {
      inputRef.current.value = '';
    }
  };

  const deleteRemoteFile = useCallback(
    async (file: UploadFile): Promise<boolean> => {
      const response = (file as any).response || {};
      const fileId = response.fileId || response.pid || response.id;
      if (!fileId) return true;

      try {
        const resp = await fetch(`/api/file/${encodeURIComponent(String(fileId))}`, {
          method: 'delete',
          credentials: 'include',
          headers: headers as Record<string, string>,
        });
        const result = await resp.json().catch(() => ({}));
        if (!resp.ok) return false;
        if (result?.code !== undefined && result.code !== '0') return false;
        return result?.data !== false;
      } catch {
        return false;
      }
    },
    [headers],
  );

  const handleRemove = async (file: UploadFile) => {
    if (onRemove) {
      const result = await onRemove(file);
      if (result === false) return;
    }
    const remoteRemoved = await deleteRemoteFile(file);
    if (!remoteRemoved) {
      console.warn(`Failed to delete uploaded file ${file.name}`);
      return;
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

  // Always-on constraint hint (format / size / count) per standard §4.
  const constraintParts: string[] = [];
  if (accept) constraintParts.push(accept);
  if (maxSize)
    constraintParts.push(t('upload.hint.maxSize', { max: maxSize }, `单个 ≤ ${maxSize}MB`));
  if (maxCount && maxCount > 1)
    constraintParts.push(t('upload.hint.maxCount', { max: maxCount }, `最多 ${maxCount} 个`));
  const constraintHint = constraintParts.join(' · ');

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
            className={`rounded-card relative border-2 border-dashed transition-colors ${isDragging ? 'border-accent bg-accent-weak' : 'border-border-strong hover:border-accent'} ${disabledValue ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} ${listType === 'picture-card' ? 'inline-flex h-28 w-28' : 'p-4'} `}
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
              <div className="text-text-3 flex h-full w-full flex-col items-center justify-center">
                <svg className="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 4v16m8-8H4"
                  />
                </svg>
                <span className="text-aux mt-1">{t('upload.button', undefined, '点击上传')}</span>
              </div>
            ) : (
              <div className="flex flex-col items-center text-center">
                <svg
                  className="text-text-3 mb-2 h-10 w-10"
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
                <p className="text-body text-text-2">{buttonText}</p>
                {draggable && (
                  <p className="text-aux text-text-3 mt-1">
                    {t('upload.dragHint', undefined, '或将文件拖拽到此处')}
                  </p>
                )}
                {/* Always-on constraint hints: format / size / count */}
                {constraintHint && <p className="text-aux text-text-3 mt-1">{constraintHint}</p>}
                {hint && <p className="text-aux text-text-3 mt-1">{hint}</p>}
              </div>
            )}
          </div>
        )}

        {/* Inline rejections (size / type) — shown here, never toasted */}
        {rejections.length > 0 && (
          <div className="mt-2 space-y-0.5" data-testid={`upload-rejections-${name}`}>
            {rejections.map((msg, i) => (
              <p key={i} className="text-aux text-status-red">
                {msg}
              </p>
            ))}
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
                role={file.status === 'done' && (file.url || file.thumbUrl) ? 'button' : undefined}
                tabIndex={file.status === 'done' && (file.url || file.thumbUrl) ? 0 : undefined}
                data-testid={`upload-file-${name}`}
                className={`group relative ${
                  listType === 'picture-card'
                    ? 'rounded-card border-border h-28 w-28 overflow-hidden border'
                    : 'rounded-card bg-subtle flex items-center gap-3 p-2'
                } `}
                onClick={() => {
                  if (file.status === 'done' && (file.url || file.thumbUrl)) {
                    handlePreview(file);
                  }
                }}
                onKeyDown={(event) => {
                  if (
                    (event.key === 'Enter' || event.key === ' ') &&
                    file.status === 'done' &&
                    (file.url || file.thumbUrl)
                  ) {
                    event.preventDefault();
                    handlePreview(file);
                  }
                }}
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
                    className={`bg-hover text-text-3 flex items-center justify-center ${listType === 'picture-card' ? 'h-full w-full' : 'h-10 w-10 rounded'} `}
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
                    <p className="text-body text-text truncate">{file.name}</p>
                    {file.status === 'error' ? (
                      <p className="text-aux text-status-red truncate">
                        {file.error || t('upload.error.failed', undefined, '上传失败')}
                      </p>
                    ) : (
                      <p className="text-aux text-text-3">
                        {file.size && formatSize(file.size)}
                        {file.status === 'uploading' && ` · ${file.percent || 0}%`}
                      </p>
                    )}
                  </div>
                )}

                {/* Retry on failed upload */}
                {!disabledValue &&
                  file.status === 'error' &&
                  (file as { originFileObj?: File }).originFileObj && (
                    <button
                      type="button"
                      data-testid="btn-retry-file"
                      onClick={(event) => {
                        event.stopPropagation();
                        handleRetry(file);
                      }}
                      className="rounded-control text-aux text-accent hover:bg-accent-weak px-2 py-1 font-medium transition-colors"
                    >
                      {t('upload.retry', undefined, '重试')}
                    </button>
                  )}

                {/* Status */}
                {file.status === 'uploading' && (
                  <div
                    className={`absolute inset-0 flex items-center justify-center bg-black/30 ${listType === 'picture-card' ? '' : 'rounded-lg'} `}
                  >
                    <div className="h-5 w-5 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  </div>
                )}

                {file.status === 'error' && listType === 'picture-card' && (
                  <div className="bg-status-red/30 absolute inset-0 flex items-center justify-center">
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
                    onClick={(event) => {
                      event.stopPropagation();
                      void handleRemove(file);
                    }}
                    className={` ${
                      listType === 'picture-card'
                        ? 'absolute top-1 right-1 opacity-0 group-hover:opacity-100'
                        : ''
                    } rounded-pill bg-panel/80 text-text-2 hover:bg-panel hover:text-status-red p-1 transition-all`}
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

        {/* Upload count hint */}
        {maxCount && (
          <p className="text-aux text-text-2 mt-2">
            {t(
              'upload.countHint',
              { count: fileList.length, max: maxCount },
              `已上传 ${fileList.length}/${maxCount}`,
            )}
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
