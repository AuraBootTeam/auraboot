/**
 * FilePreviewModal Component
 *
 * Modal dialog for previewing uploaded files with type-aware rendering.
 * Supports images, PDFs, text/code files, and a download fallback for others.
 */

import React, { useEffect, useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '~/ui/ui/dialog';

export interface FilePreviewModalProps {
  /** Whether the modal is visible */
  open: boolean;
  /** Close handler */
  onClose: () => void;
  /** URL to the file (e.g. /api/file/download/{fileId}) */
  fileUrl: string;
  /** Display name of the file */
  fileName: string;
  /** MIME type or file extension */
  fileType?: string;
}

type PreviewKind = 'image' | 'pdf' | 'text' | 'other';

const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg'];
const TEXT_EXTENSIONS = [
  'txt',
  'csv',
  'json',
  'xml',
  'md',
  'log',
  'yml',
  'yaml',
  'html',
  'css',
  'js',
  'ts',
];
const IMAGE_MIMES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/svg+xml'];
const TEXT_MIMES = ['text/', 'application/json', 'application/xml'];

/**
 * Determine preview kind from MIME type and file extension.
 */
function resolvePreviewKind(fileName: string, fileType?: string): PreviewKind {
  const ext = fileName.split('.').pop()?.toLowerCase() || '';
  const mime = (fileType || '').toLowerCase();

  // Image check
  if (IMAGE_MIMES.some((m) => mime.startsWith(m)) || IMAGE_EXTENSIONS.includes(ext)) {
    return 'image';
  }

  // PDF check
  if (mime === 'application/pdf' || ext === 'pdf') {
    return 'pdf';
  }

  // Text / code check
  if (TEXT_MIMES.some((m) => mime.startsWith(m)) || TEXT_EXTENSIONS.includes(ext)) {
    return 'text';
  }

  return 'other';
}

/**
 * FilePreviewModal - renders a file preview inside a Radix Dialog.
 */
export const FilePreviewModal: React.FC<FilePreviewModalProps> = ({
  open,
  onClose,
  fileUrl,
  fileName,
  fileType,
}) => {
  const [textContent, setTextContent] = useState<string | null>(null);
  const [textLoading, setTextLoading] = useState(false);
  const [textError, setTextError] = useState<string | null>(null);

  const kind = resolvePreviewKind(fileName, fileType);

  // Fetch text content when modal opens for text files
  useEffect(() => {
    if (!open || kind !== 'text' || !fileUrl) {
      setTextContent(null);
      setTextError(null);
      return;
    }

    let cancelled = false;
    setTextLoading(true);
    setTextError(null);

    fetch(fileUrl, { credentials: 'include' })
      .then(async (resp) => {
        if (!resp.ok) throw new Error(`Failed to load file (HTTP ${resp.status})`);
        const text = await resp.text();
        if (!cancelled) setTextContent(text);
      })
      .catch((err) => {
        if (!cancelled) setTextError(err instanceof Error ? err.message : 'Failed to load file');
      })
      .finally(() => {
        if (!cancelled) setTextLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [open, kind, fileUrl]);

  // Determine max-width class based on preview kind
  const contentMaxWidth = kind === 'text' ? 'max-w-2xl' : 'max-w-4xl';

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <DialogContent className={`${contentMaxWidth} w-[90vw]`} data-testid="file-preview-modal">
        <DialogHeader>
          <DialogTitle className="truncate pr-8" title={fileName}>
            {fileName}
          </DialogTitle>
          <DialogDescription className="sr-only">File preview</DialogDescription>
        </DialogHeader>

        <div className="mt-2">
          {/* Image preview */}
          {kind === 'image' && (
            <div className="flex min-h-[200px] items-center justify-center rounded-lg bg-gray-50 p-4">
              <img
                src={fileUrl}
                alt={fileName}
                className="max-h-[70vh] max-w-full rounded object-contain"
              />
            </div>
          )}

          {/* PDF preview */}
          {kind === 'pdf' && (
            <iframe
              src={fileUrl}
              title={fileName}
              sandbox="allow-scripts allow-same-origin"
              className="h-[70vh] w-full rounded-lg border border-gray-200"
            />
          )}

          {/* Text / code preview */}
          {kind === 'text' && (
            <div className="max-h-[70vh] overflow-auto rounded-lg border border-gray-200 bg-gray-50">
              {textLoading && (
                <div className="flex items-center justify-center p-8 text-gray-500">
                  <div className="mr-2 h-5 w-5 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
                  Loading...
                </div>
              )}
              {textError && <div className="p-4 text-sm text-red-600">{textError}</div>}
              {textContent !== null && !textLoading && (
                <pre className="p-4 font-mono text-sm break-words whitespace-pre-wrap text-gray-800">
                  {textContent}
                </pre>
              )}
            </div>
          )}

          {/* Other / unknown file types */}
          {kind === 'other' && (
            <div className="flex flex-col items-center justify-center py-12 text-gray-500">
              <svg
                className="mb-4 h-16 w-16 text-gray-300"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
              <p className="mb-1 text-sm font-medium text-gray-700">{fileName}</p>
              <p className="mb-4 text-xs text-gray-400">Preview not available for this file type</p>
              <a
                href={fileUrl}
                download={fileName}
                className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                  />
                </svg>
                Download
              </a>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default FilePreviewModal;
