import { useEffect, useRef, useState } from 'react';
import type { FileAttachmentFieldProps, FileItem } from './FileAttachmentField';
import { uploadFile } from '~/shared/services/fileupload/uploadService';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { publicFilePid } from '~/framework/meta/runtime/actions/uploadedFiles';
import copy from './StagedFileAttachmentField.i18n.json';

type Selection = {
  key: number;
  file: File;
  phase: 'pending' | 'failed' | 'uploaded';
  item?: FileItem;
  error?: keyof typeof copy;
};

/** The official upload-only path with explicit per-file staging outcomes. No link/delete RPCs. */
export function StagedFileAttachmentField(props: FileAttachmentFieldProps) {
  const { locale } = useI18n();
  const label = (key: keyof typeof copy) => getLocalizedText(copy[key], locale);
  const [selections, setSelections] = useState<Selection[]>([]);
  const current = useRef<Selection[]>([]);
  const callbacks = useRef(props);
  callbacks.current = props;
  const active = useRef(true);
  const sequence = useRef(0);
  const input = useRef<HTMLInputElement>(null);
  const maxCount = props.maxCount ?? 10;
  const maxBytes = (props.maxSize ?? 10) * 1024 * 1024;
  const busy = selections.some((row) => row.phase === 'pending');
  const disabled = props.disabled || props.readOnly;

  const publish = (rows: Selection[]) => {
    if (!active.current) return;
    current.current = rows;
    setSelections(rows);
    callbacks.current.onChange?.(rows.flatMap((row) => (row.item ? [row.item] : [])));
    const pendingCount = rows.filter((row) => row.phase === 'pending').length;
    const failedCount = rows.filter((row) => row.phase === 'failed').length;
    callbacks.current.onUploadStateChange?.({
      pendingCount,
      failedCount,
      blocked: pendingCount > 0 || failedCount > 0,
    });
  };
  useEffect(() => {
    active.current = true;
    callbacks.current.onUploadStateChange?.({ pendingCount: 0, failedCount: 0, blocked: false });
    return () => {
      active.current = false;
    };
  }, []);

  const allowed = (file: File) =>
    !props.accept ||
    props.accept.split(',').some((part) => {
      const accept = part.trim().toLowerCase();
      return accept.startsWith('.')
        ? file.name.toLowerCase().endsWith(accept)
        : accept.endsWith('/*')
          ? file.type.toLowerCase().startsWith(accept.slice(0, -1))
          : file.type.toLowerCase() === accept;
    });
  const run = async (row: Selection) => {
    const invalid =
      row.file.size > maxBytes ? 'tooLarge' : !allowed(row.file) ? 'wrongType' : undefined;
    let item: FileItem | undefined,
      error: keyof typeof copy | undefined = invalid;
    if (!invalid) {
      try {
        const response = await uploadFile(row.file);
        const result = await response.json();
        if (!response.ok || result.code !== '0' || !publicFilePid(result.data?.fileId))
          throw new Error('Invalid upload receipt');
        item = {
          fileId: result.data.fileId,
          name: row.file.name,
          size: row.file.size,
          type: row.file.type,
          url: '/api/file/download/' + encodeURIComponent(result.data.fileId),
        };
      } catch {
        error = 'failed';
      }
    }
    if (!active.current || !current.current.some((value) => value.key === row.key)) return;
    if (
      item &&
      current.current.some((value) => value.key !== row.key && value.item?.fileId === item?.fileId)
    ) {
      item = undefined;
      error = 'failed';
    }
    publish(
      current.current.map((value) =>
        value.key === row.key
          ? { ...value, phase: item ? 'uploaded' : 'failed', item, error }
          : value,
      ),
    );
  };
  const choose = async (files: File[]) => {
    if (disabled || current.current.some((row) => row.phase === 'pending') || !files.length) return;
    const over = current.current.length + files.length > maxCount;
    const added: Selection[] = files.map((file) => ({
      key: ++sequence.current,
      file,
      phase: over ? 'failed' : 'pending',
      ...(over ? { error: 'tooMany' as const } : {}),
    }));
    publish([...current.current, ...added]);
    if (!over) for (const row of added) await run(row);
  };
  const retry = (row: Selection) => {
    if (
      disabled ||
      current.current.some((value) => value.phase === 'pending') ||
      current.current.length > maxCount
    )
      return;
    publish(
      current.current.map((value) =>
        value.key === row.key ? { ...value, phase: 'pending', error: undefined } : value,
      ),
    );
    void run(row);
  };
  return (
    <div data-testid="staged-file-field" aria-busy={busy}>
      <input
        ref={input}
        type="file"
        tabIndex={-1}
        multiple
        accept={props.accept}
        data-testid="attachment-file-input"
        className="sr-only"
        disabled={disabled || busy || selections.length >= maxCount}
        aria-label={label('add')}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? []);
          event.target.value = '';
          void choose(files);
        }}
      />
      <ul className="space-y-2">
        {selections.map((row) => (
          <li
            key={row.key}
            data-upload-phase={row.phase}
            className="border-border rounded-control border p-3"
          >
            <span className="text-text block text-sm [overflow-wrap:anywhere]">
              {row.file.name}
            </span>
            <span
              role={row.phase === 'failed' ? 'alert' : 'status'}
              className="text-text-2 text-xs"
            >
              {label(row.error ?? row.phase)}
            </span>
            <div className="mt-2 flex gap-3">
              {row.phase === 'failed' && (
                <button
                  type="button"
                  disabled={disabled || busy || selections.length > maxCount}
                  className="text-accent text-sm disabled:opacity-50"
                  onClick={() => retry(row)}
                >
                  {label('retry')}
                </button>
              )}
              <button
                type="button"
                disabled={disabled || busy}
                className="text-text-2 text-sm disabled:opacity-50"
                aria-label={label('remove') + ' ' + row.file.name}
                onClick={() => publish(current.current.filter((value) => value.key !== row.key))}
              >
                {label('remove')}
              </button>
            </div>
          </li>
        ))}
      </ul>
      <button
        type="button"
        disabled={disabled || busy || selections.length >= maxCount}
        onClick={() => input.current?.click()}
        className="border-border text-text-2 rounded-control mt-3 border border-dashed px-3 py-2 text-sm disabled:opacity-50"
      >
        {label('add')}
      </button>
      {selections.some((row) => row.phase === 'failed') && (
        <p role="status" className="text-status-red mt-2 text-sm">
          {label('resolve')}
        </p>
      )}
      <p className="text-text-3 mt-2 text-xs">{label('staging')}</p>
    </div>
  );
}
