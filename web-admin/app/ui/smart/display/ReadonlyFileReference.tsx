import { useEffect, useState } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { publicFilePid } from '~/framework/meta/runtime/actions/uploadedFiles';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import copy from './ReadonlyFileReference.i18n.json';

type FileMetadata = {
  fileId: string;
  originalName?: string;
  fileName?: string;
  fileSize: number;
};
type Observation = { fileId: string; file: FileMetadata | null };

/** Read an existing scalar file reference through the same authorized BFF as its download. */
export function ReadonlyFileReference({ fileId, locale }: { fileId: string; locale: string }) {
  const [observation, setObservation] = useState<Observation | null>(null);
  const valid = publicFilePid(fileId);
  useEffect(() => {
    if (!valid) return;
    let active = true;
    setObservation(null);
    void fetchResult<FileMetadata>(`/api/file/${fileId}`, { method: 'get' })
      .then((result) => {
        if (!active) return;
        const file = result.data;
        const name = typeof file?.originalName === 'string' ? file.originalName.trim() : '';
        const storedName = typeof file?.fileName === 'string' ? file.fileName.trim() : '';
        const accepted =
          result.code === '0' &&
          file?.fileId === fileId &&
          (name || storedName) &&
          Number.isSafeInteger(file.fileSize) &&
          file.fileSize >= 0;
        setObservation({ fileId, file: accepted ? file : null });
      })
      .catch(() => {
        if (active) setObservation({ fileId, file: null });
      });
    return () => {
      active = false;
    };
  }, [fileId, valid]);

  if (!valid || (observation?.fileId === fileId && !observation.file))
    return (
      <span role="alert" className="text-status-red text-sm">
        {getLocalizedText(copy.unavailable, locale)}
      </span>
    );
  if (observation?.fileId !== fileId)
    return (
      <span role="status" className="text-text-3 text-sm">
        {getLocalizedText(copy.loading, locale)}
      </span>
    );
  const file = observation.file!;
  const name =
    typeof file.originalName === 'string' && file.originalName.trim()
      ? file.originalName.trim()
      : file.fileName!.trim();
  const size =
    file.fileSize < 1024
      ? `${file.fileSize} B`
      : file.fileSize < 1048576
        ? `${(file.fileSize / 1024).toFixed(1)} KB`
        : `${(file.fileSize / 1048576).toFixed(1)} MB`;
  return (
    <a
      href={`/api/file/download/${fileId}`}
      download={name}
      target="_self"
      rel="noopener noreferrer"
      className="text-accent inline-flex max-w-full flex-wrap items-center gap-x-2 text-sm hover:underline"
    >
      <span className="break-all">{name}</span> <small className="text-text-3">{size}</small>
    </a>
  );
}
