/**
 * FileAttachmentField — Lightweight file upload/display for DSL forms.
 * Stores value as JSON array: [{name, url, size, type}]
 */
import React, { useRef, useState, useCallback } from 'react';
import { uploadFile } from '~/shared/services/fileupload/uploadService';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import FILE_ATTACHMENT_TEXT from './FileAttachmentField.i18n.json';

interface FileItem {
  name: string;
  fileId?: string;
  url: string;
  size?: number;
  type?: string;
}

interface FileAttachmentFieldProps {
  name?: string;
  value?: string | FileItem[];
  disabled?: boolean;
  readOnly?: boolean;
  multiple?: boolean;
  accept?: string;
  maxSize?: number;
  onChange?: (value: FileItem[]) => void;
  context?: any;
}

export function attachmentDownloadUrl(value: string): string | null {
  if (/^\/download\/[A-Za-z0-9_-]+$/.test(value)) return `/api/file${value}`;
  if (/^\/api\/file\/download\/[A-Za-z0-9_-]+$/.test(value)) return value;
  try { const url = new URL(value); return url.protocol === 'https:' ? url.href : null; } catch { return null; }
}

function parseValue(value: any): FileItem[] {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  if (typeof value === 'string') {
    try {
      return JSON.parse(value);
    } catch {
      return [];
    }
  }
  return [];
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function FileAttachmentField({
  value,
  disabled = false,
  readOnly = false,
  multiple = true,
  accept = '',
  maxSize = 10,
  onChange,
}: FileAttachmentFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const files = parseValue(value);
  const { showErrorToast } = useToastContext();
  const { locale } = useI18n();
  const uploadText = useCallback(
    (key: keyof typeof FILE_ATTACHMENT_TEXT) => getLocalizedText(FILE_ATTACHMENT_TEXT[key], locale),
    [locale],
  );

  const handleUpload = useCallback(
    async (fileList: FileList) => {
      const newFiles: FileItem[] = [];
      setUploading(true);
      for (const file of Array.from(fileList)) {
        if (file.size > maxSize * 1024 * 1024) {
          console.warn(
            `[FileAttachmentField] File "${file.name}" exceeds ${maxSize}MB limit, skipped.`,
          );
          showErrorToast(`${file.name} 超出 ${maxSize}MB 限制`);
          continue;
        }
        try {
          const res = await uploadFile(file);
          const json = await res.json();
          if (json.code === '0' && json.data?.fileId) {
            newFiles.push({
              name: file.name,
              fileId: json.data.fileId,
              url: `/api/file/download/${encodeURIComponent(json.data.fileId)}`,
              size: file.size,
              type: file.type,
            });
          } else {
            throw new Error(json.message || json.desc || uploadText('uploadFailed'));
          }
        } catch (err) {
          showErrorToast(`${file.name}: ${uploadText('uploadFailed')}`);
        }
      }
      setUploading(false);
      onChange?.([...files, ...newFiles]);
    },
    [files, maxSize, onChange, showErrorToast, uploadText],
  );

  const handleRemove = useCallback(
    (idx: number) => {
      onChange?.(files.filter((_, i) => i !== idx));
    },
    [files, onChange],
  );

  if (readOnly) {
    if (files.length === 0) return <span className="text-text-3 text-sm">—</span>;
    return (
      <div className="flex flex-wrap gap-x-8 gap-y-4">
        {files.map((f, i) => (
          <a
            key={i}
            href={attachmentDownloadUrl(f.url) || undefined}
            download={f.name}
            aria-disabled={!attachmentDownloadUrl(f.url)}
            data-testid={`attachment-download-${i}`}
            target="_self"
            rel="noopener noreferrer"
            className="text-accent flex items-center gap-2 text-sm hover:underline"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
              />
            </svg>
<span><strong className="text-text block text-xs font-medium">{f.name}</strong>{f.size != null && <small className="text-text-3 mt-1 block text-[11px]">{formatSize(f.size)}</small>}</span>
          </a>
        ))}
      </div>
    );
  }

  return (
    <div>
      <input
        ref={inputRef}
        type="file"
        className="hidden"
        accept={accept}
        multiple={multiple}
        onChange={(e) => e.target.files && handleUpload(e.target.files)}
      />
      {files.length > 0 && (
        <div className="mb-2 space-y-1">
          {files.map((f, i) => (
            <div
              key={i}
              className="border-border flex items-center gap-2 rounded border px-2 py-1 text-sm"
            >
              <span className="flex-1 truncate">{f.name}</span>
              {f.size && <span className="text-text-3">{formatSize(f.size)}</span>}
              {!disabled && (
                <button
                  type="button"
                  onClick={() => handleRemove(i)}
                  className="text-text-3 hover:text-status-red"
                >
                  ×
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      <button
        type="button"
        disabled={disabled || uploading}
        onClick={() => inputRef.current?.click()}
        className="border-border-strong text-text-2 hover:border-accent hover:text-accent rounded border border-dashed px-3 py-1.5 text-sm disabled:opacity-50"
      >
        {uploading ? uploadText('uploading') : uploadText('addFile')}
      </button>
    </div>
  );
}

export default FileAttachmentField;
