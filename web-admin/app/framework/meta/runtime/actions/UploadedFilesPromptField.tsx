import { useEffect, useRef, useState } from 'react';
import {
  FileAttachmentField,
  type FileItem,
  type FileUploadState,
} from '~/ui/smart/display/FileAttachmentField';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '../expression/i18n-renderer';
import { uploadedFilesConfig } from './uploadedFiles';
import copy from '~/ui/smart/display/StagedFileAttachmentField.i18n.json';

export function UploadedFilesPromptField({
  config,
  value,
  onChange,
  onStatus,
}: {
  config: unknown;
  value: unknown;
  onChange: (value: string[]) => void;
  onStatus: (state: FileUploadState) => void;
}) {
  const { locale } = useI18n();
  const [files, setFiles] = useState<FileItem[]>([]);
  const callbacks = useRef({ onChange, onStatus });
  callbacks.current = { onChange, onStatus };
  let parsed: ReturnType<typeof uploadedFilesConfig> | undefined;
  try {
    parsed = uploadedFilesConfig(config);
  } catch {
    /* Render a blocking configuration state. */
  }
  const valid = Boolean(
    parsed &&
    (value === undefined ||
      (Array.isArray(value) &&
        JSON.stringify(value) === JSON.stringify(files.map((file) => file.fileId)))),
  );
  useEffect(() => {
    if (!valid) callbacks.current.onStatus({ pendingCount: 0, failedCount: 1, blocked: true });
  }, [valid]);
  if (!valid || !parsed) return <p role="alert">{getLocalizedText(copy.invalid, locale)}</p>;
  return (
    <FileAttachmentField
      uploadMode="staged-pids"
      value={files}
      multiple
      accept={parsed.accept}
      maxCount={parsed.maxCount}
      maxSize={parsed.maxBytes / (1024 * 1024)}
      onChange={(items) => {
        setFiles(items);
        callbacks.current.onChange(items.map((item) => item.fileId!));
      }}
      onUploadStateChange={(status) => callbacks.current.onStatus(status)}
    />
  );
}
