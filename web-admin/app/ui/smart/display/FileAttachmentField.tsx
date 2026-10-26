/**
 * FileAttachmentField — Lightweight file upload/display for DSL forms.
 * Stores value as JSON array: [{name, url, size, type}]
 */
import React, { useRef, useState, useCallback, useEffect } from 'react';
import { uploadFile } from '~/shared/services/fileupload/uploadService';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
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
  maxCount?: number;
  limitsEndpoint?: string;
  onChange?: (value: FileItem[]) => void;
  context?: any;
}

export function attachmentDownloadUrl(value: string): string | null {
  if (/^\/download\/[A-Za-z0-9_-]+$/.test(value)) return `/api/file${value}`;
  // Same-origin controlled download references remain subject to server-side authorization.
  if (/^\/api\/(?:[A-Za-z0-9_-]+\/)*(?:download|attachments)\/[A-Za-z0-9_-]+$/.test(value)) return value;
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
  maxCount,
  limitsEndpoint,
  onChange,
}: FileAttachmentFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const files = parseValue(value);
  const [effectiveMaxSize, setEffectiveMaxSize] = useState(maxSize);
  const [limitsReady, setLimitsReady] = useState(!limitsEndpoint || readOnly);
  const [limitsError, setLimitsError] = useState(false);
  const { showErrorToast } = useToastContext();
  const { locale } = useI18n();
  const uploadText = useCallback(
    (key: keyof typeof FILE_ATTACHMENT_TEXT) => getLocalizedText(FILE_ATTACHMENT_TEXT[key], locale),
    [locale],
  );

  useEffect(() => {
    if (!limitsEndpoint || readOnly) return;
    let active = true;
    setLimitsReady(false); setLimitsError(false);
    void fetchResult<{ maxFileBytes: number; maxRequestBytes: number }>(limitsEndpoint, { method: 'get' })
      .then((result) => {
        if (!active) return;
        if (!ResultHelper.isSuccess(result) || !result.data || !Number.isFinite(result.data.maxFileBytes)) {
          setLimitsError(true); return;
        }
        setEffectiveMaxSize(result.data.maxFileBytes < 0 ? Infinity : result.data.maxFileBytes / (1024 * 1024));
        setLimitsReady(true);
      })
      .catch(() => { if (active) setLimitsError(true); });
    return () => { active = false; };
  }, [limitsEndpoint, readOnly]);

  const handleUpload = useCallback(
    async (fileList: FileList) => {
      if (!limitsReady || disabled || readOnly) return;
      if (maxCount !== undefined && files.length + fileList.length > maxCount) {
        showErrorToast(uploadText('tooManyFiles').replace('{count}', String(maxCount))); return;
      }
      const newFiles: FileItem[] = [];
      setUploading(true);
      for (const file of Array.from(fileList)) {
        if (file.size > effectiveMaxSize * 1024 * 1024) {
          console.warn(
            `[FileAttachmentField] File "${file.name}" exceeds ${effectiveMaxSize}MB limit, skipped.`,
          );
          showErrorToast(uploadText('fileTooLarge').replace('{name}', file.name).replace('{size}', String(effectiveMaxSize)));
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
    [files, effectiveMaxSize, maxCount, limitsReady, disabled, readOnly, onChange, showErrorToast, uploadText],
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
            <svg className="border-border bg-subtle text-text-3 h-8 w-7 shrink-0 rounded border p-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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
        disabled={disabled || uploading || !limitsReady || (maxCount !== undefined && files.length >= maxCount)}
        data-testid="attachment-file-input"
        onChange={(e) => e.target.files && handleUpload(e.target.files)}
      />
      {limitsEndpoint && !limitsReady && !limitsError && <p role="status" className="text-text-3 text-sm">{uploadText('limitsLoading')}</p>}
      {limitsError && <p role="alert" className="text-status-red text-sm">{uploadText('limitsUnavailable')}</p>}
      {limitsEndpoint && limitsReady && <p className="text-text-3 text-sm">{Number.isFinite(effectiveMaxSize)
        ? uploadText('effectiveLimit').replace('{size}', String(effectiveMaxSize)) : uploadText('unlimitedSize')}</p>}
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
        disabled={disabled || uploading || !limitsReady || (maxCount !== undefined && files.length >= maxCount)}
        onClick={() => inputRef.current?.click()}
        className="border-border-strong text-text-2 hover:border-accent hover:text-accent rounded border border-dashed px-3 py-1.5 text-sm disabled:opacity-50"
      >
        {uploading ? uploadText('uploading') : uploadText('addFile')}
      </button>
    </div>
  );
}

export default FileAttachmentField;
