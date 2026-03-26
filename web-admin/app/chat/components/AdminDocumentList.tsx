import { useEffect, useState } from 'react';
import type { AdminDocument } from '../types';

interface AdminDocumentListProps {
  onDocumentSelect: (document: AdminDocument) => void;
  onDocumentDelete: (documentId: string) => void;
  refreshTrigger: number;
}

export function AdminDocumentList({
  onDocumentSelect,
  onDocumentDelete,
  refreshTrigger,
}: AdminDocumentListProps) {
  const [documents, setDocuments] = useState<AdminDocument[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let isMounted = true;
    const fetchDocuments = async () => {
      setLoading(true);
      setError(null);
      try {
        const response = await fetch(
          '/api/admin/documents?admin_user_id=admin_001&tenant_id=default',
        );
        if (!response.ok) {
          throw new Error(`Failed to load documents: ${response.statusText}`);
        }
        const payload = await response.json();
        const data = Array.isArray(payload) ? payload : (payload?.data ?? []);
        if (isMounted) {
          setDocuments(data as AdminDocument[]);
        }
      } catch (err) {
        if (isMounted) {
          setError((err as Error).message);
          setDocuments([]);
        }
      } finally {
        if (isMounted) {
          setLoading(false);
        }
      }
    };

    fetchDocuments();
    return () => {
      isMounted = false;
    };
  }, [refreshTrigger]);

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-medium text-gray-900">文档列表</h3>
        <button
          onClick={() => setDocuments([])}
          className="rounded bg-gray-100 px-3 py-1 text-sm text-gray-700 hover:bg-gray-200"
        >
          清空列表
        </button>
      </div>

      {loading && <div className="text-sm text-gray-500">加载中...</div>}
      {error && <div className="text-sm text-red-600">加载失败: {error}</div>}

      {!loading && !error && documents.length === 0 && (
        <div className="text-sm text-gray-500">暂无文档</div>
      )}

      {!loading && !error && documents.length > 0 && (
        <div className="space-y-3">
          {documents.map((doc) => (
            <div
              key={doc.document_id}
              className="flex items-center justify-between rounded border border-gray-200 p-3"
            >
              <div>
                <div className="text-sm font-medium text-gray-900">
                  {doc.title || doc.document_id}
                </div>
                <div className="text-xs text-gray-500">
                  类型: {doc.document_type || 'N/A'} · 状态: {doc.status || 'N/A'}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => onDocumentSelect(doc)}
                  className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700"
                >
                  详情
                </button>
                <button
                  onClick={() => onDocumentDelete(doc.document_id)}
                  className="rounded bg-red-600 px-3 py-1 text-xs text-white hover:bg-red-700"
                >
                  删除
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
