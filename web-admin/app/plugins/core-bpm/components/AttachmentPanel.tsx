/**
 * Attachment panel for BPM tasks/processes
 */

import { useState, useEffect } from 'react';
import { Button } from '~/ui/ui/button';
import { RefreshCw } from 'lucide-react';
import { useToastContext } from '~/contexts/ToastContext';
import {
  getProcessAttachments,
  getTaskAttachments,
  deleteAttachment,
  type FileMetadata,
} from '../services/bpmAttachmentService';
import { confirmDialog } from '~/utils/confirmDialog';

// ==================== Helper Functions ====================

function formatDate(dateString?: string): string {
  if (!dateString) return '-';
  try {
    return new Date(dateString).toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return dateString;
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ==================== Component ====================

interface AttachmentPanelProps {
  processInstanceId?: string;
  taskId?: string;
}

export function AttachmentPanel({ processInstanceId, taskId }: AttachmentPanelProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [attachments, setAttachments] = useState<FileMetadata[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadAttachments();
  }, [processInstanceId, taskId]);

  const loadAttachments = async () => {
    setLoading(true);
    try {
      if (taskId) {
        setAttachments(await getTaskAttachments(taskId));
      } else if (processInstanceId) {
        setAttachments(await getProcessAttachments(processInstanceId));
      }
    } catch (error) {
      console.error('Failed to load attachments:', error);
      showErrorToast('Failed to load attachments');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (fileId: string) => {
    if (!(await confirmDialog({ content: 'Delete this attachment?', variant: 'danger' }))) return;
    try {
      await deleteAttachment(fileId);
      setAttachments((prev) => prev.filter((a) => a.pid !== fileId));
      showSuccessToast('Attachment deleted');
    } catch (error) {
      console.error('Failed to delete attachment:', error);
      showErrorToast('Failed to delete attachment');
    }
  };

  return (
    <div className="p-4">
      <h3 className="mb-4 text-lg font-semibold">Attachments</h3>

      {loading ? (
        <div className="flex items-center justify-center py-4 text-gray-500">
          <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
          Loading...
        </div>
      ) : attachments.length === 0 ? (
        <div className="py-4 text-center text-gray-500">No attachments</div>
      ) : (
        <div className="space-y-2">
          {attachments.map((file) => (
            <div
              key={file.pid}
              className="flex items-center justify-between rounded-lg bg-gray-50 p-3"
            >
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-gray-800">{file.fileName}</p>
                <p className="text-xs text-gray-500">
                  {formatSize(file.fileSize)} | {formatDate(file.createdAt)}
                </p>
              </div>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleDelete(file.pid)}
                className="ml-2 text-xs text-red-500 hover:text-red-700"
              >
                Delete
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
