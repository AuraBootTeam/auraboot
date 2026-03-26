import { useState, useEffect, useCallback } from 'react';
import {
  useAuditLog,
  type DataChangeLog,
  type FieldChange,
  type ChangeLogQueryParams,
} from '~/hooks/useAuditLog';
import {
  ClipboardDocumentListIcon,
  MagnifyingGlassIcon,
  XMarkIcon,
  PlusCircleIcon,
  PencilSquareIcon,
  TrashIcon,
  ArrowPathIcon,
  ChevronDownIcon,
  ChevronUpIcon,
} from '@heroicons/react/24/outline';
import { ClipboardDocumentListIcon as ClipboardDocumentListSolidIcon } from '@heroicons/react/24/solid';
import { useI18n } from '~/contexts/I18nContext';

/**
 * Audit Logs Page
 */
export default function AuditLogsPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zhCN: string, enUS: string) => (locale === 'zh-CN' ? zhCN : enUS),
    [locale],
  );
  const { myChanges, total, loading, getMyChanges, getRecordHistory, parseChanges } = useAuditLog();

  const [queryParams, setQueryParams] = useState<ChangeLogQueryParams>({
    pageNum: 1,
    pageSize: 20,
    modelCode: '',
    operation: '',
  });

  const [expandedLogs, setExpandedLogs] = useState<Set<number>>(new Set());
  const [showLookupModal, setShowLookupModal] = useState(false);
  const [lookupModelCode, setLookupModelCode] = useState('');
  const [lookupRecordId, setLookupRecordId] = useState('');
  const [recordHistory, setRecordHistory] = useState<DataChangeLog[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  // Initial load
  useEffect(() => {
    getMyChanges(queryParams);
  }, [queryParams, getMyChanges]);

  // Toggle log expansion
  const toggleExpand = useCallback((id: number) => {
    setExpandedLogs((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  // Handle page change
  const handlePageChange = useCallback((page: number) => {
    setQueryParams((prev) => ({ ...prev, pageNum: page }));
  }, []);

  // Lookup record history
  const handleLookup = useCallback(async () => {
    if (!lookupModelCode || !lookupRecordId) return;
    setHistoryLoading(true);
    const history = await getRecordHistory(lookupModelCode, lookupRecordId);
    setRecordHistory(history);
    setHistoryLoading(false);
  }, [lookupModelCode, lookupRecordId, getRecordHistory]);

  // Get operation icon
  const getOperationIcon = (operation: string) => {
    switch (operation) {
      case 'create':
        return <PlusCircleIcon className="h-5 w-5 text-green-500" />;
      case 'update':
        return <PencilSquareIcon className="h-5 w-5 text-blue-500" />;
      case 'delete':
        return <TrashIcon className="h-5 w-5 text-red-500" />;
      default:
        return <ArrowPathIcon className="h-5 w-5 text-gray-500" />;
    }
  };

  // Get operation badge color
  const getOperationBadge = (operation: string) => {
    switch (operation) {
      case 'create':
        return 'bg-green-100 text-green-800';
      case 'update':
        return 'bg-blue-100 text-blue-800';
      case 'delete':
        return 'bg-red-100 text-red-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  const getOperationText = (operation: string) => {
    switch (operation) {
      case 'create':
        return l('创建', 'Create');
      case 'update':
        return l('更新', 'Update');
      case 'delete':
        return l('删除', 'Delete');
      default:
        return operation;
    }
  };

  // Format date
  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleString();
  };

  // Render change log item
  const renderChangeLogItem = (log: DataChangeLog, showModel: boolean = true) => {
    const changes = parseChanges(log.changes);
    const isExpanded = expandedLogs.has(log.id);

    return (
      <div
        key={log.id}
        className="rounded-lg border bg-white p-4 transition-shadow hover:shadow-md"
      >
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            {getOperationIcon(log.operation)}
            <div>
              <div className="flex items-center gap-2">
                <span
                  className={`rounded px-2 py-0.5 text-xs font-medium ${getOperationBadge(log.operation)}`}
                >
                  {getOperationText(log.operation)}
                </span>
                {showModel && (
                  <code className="rounded bg-gray-100 px-2 py-0.5 text-xs">{log.modelCode}</code>
                )}
                <span className="text-xs text-gray-500">
                  {l('ID', 'ID')}: {log.recordId}
                </span>
              </div>
              <div className="mt-1 text-xs text-gray-500">
                {formatDate(log.changedAt)}
                {log.commandCode && (
                  <span className="ml-2">
                    {l('来源', 'via')} {log.commandCode}
                  </span>
                )}
              </div>
            </div>
          </div>
          <button
            onClick={() => toggleExpand(log.id)}
            className="p-1 text-gray-400 hover:text-gray-600"
          >
            {isExpanded ? (
              <ChevronUpIcon className="h-5 w-5" />
            ) : (
              <ChevronDownIcon className="h-5 w-5" />
            )}
          </button>
        </div>

        {isExpanded && (
          <div className="mt-4 border-t pt-4">
            {/* Changes Table */}
            {changes.length > 0 && (
              <div className="mb-4">
                <h4 className="mb-2 text-sm font-medium text-gray-700">
                  {l('字段变更', 'Field Changes')}
                </h4>
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-500">
                          {l('字段', 'Field')}
                        </th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-500">
                          {l('旧值', 'Old Value')}
                        </th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-500">
                          {l('新值', 'New Value')}
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200">
                      {changes.map((change: FieldChange, idx: number) => (
                        <tr key={idx}>
                          <td className="px-3 py-2 font-medium text-gray-900">
                            {change.fieldLabel || change.field}
                          </td>
                          <td className="px-3 py-2 text-red-600">
                            <code className="rounded bg-red-50 px-1">
                              {JSON.stringify(change.oldValue) || '-'}
                            </code>
                          </td>
                          <td className="px-3 py-2 text-green-600">
                            <code className="rounded bg-green-50 px-1">
                              {JSON.stringify(change.newValue) || '-'}
                            </code>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {/* Snapshots */}
            {(log.snapshotBefore || log.snapshotAfter) && (
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                {log.snapshotBefore && (
                  <div>
                    <h4 className="mb-2 text-sm font-medium text-gray-700">
                      {l('变更前快照', 'Snapshot Before')}
                    </h4>
                    <pre className="max-h-40 overflow-x-auto rounded bg-gray-50 p-2 text-xs">
                      {typeof log.snapshotBefore === 'string'
                        ? JSON.stringify(JSON.parse(log.snapshotBefore), null, 2)
                        : JSON.stringify(log.snapshotBefore, null, 2)}
                    </pre>
                  </div>
                )}
                {log.snapshotAfter && (
                  <div>
                    <h4 className="mb-2 text-sm font-medium text-gray-700">
                      {l('变更后快照', 'Snapshot After')}
                    </h4>
                    <pre className="max-h-40 overflow-x-auto rounded bg-gray-50 p-2 text-xs">
                      {typeof log.snapshotAfter === 'string'
                        ? JSON.stringify(JSON.parse(log.snapshotAfter), null, 2)
                        : JSON.stringify(log.snapshotAfter, null, 2)}
                    </pre>
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    );
  };

  // Calculate total pages
  const totalPages = Math.ceil(total / queryParams.pageSize);

  return (
    <div className="mx-auto max-w-5xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <ClipboardDocumentListSolidIcon className="h-8 w-8 text-indigo-600" />
          <div>
            <h1 className="text-2xl font-bold text-gray-900">{l('审计日志', 'Audit Logs')}</h1>
            <p className="text-sm text-gray-500">
              {l('追踪数据变更与修改记录', 'Track data changes and modifications')}
            </p>
          </div>
        </div>
        <button
          onClick={() => setShowLookupModal(true)}
          className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
        >
          <MagnifyingGlassIcon className="h-4 w-4" />
          {l('查询记录', 'Lookup Record')}
        </button>
      </div>

      {/* Filters */}
      <div className="mb-6 rounded-lg bg-gray-50 p-4">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500">
              {l('模型编码', 'Model Code')}
            </label>
            <input
              type="text"
              value={queryParams.modelCode || ''}
              onChange={(e) =>
                setQueryParams((prev) => ({ ...prev, modelCode: e.target.value, pageNum: 1 }))
              }
              placeholder={l('例如：order, customer', 'e.g., order, customer')}
              className="w-full rounded-md border-gray-300 text-sm shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500">
              {l('操作类型', 'Operation')}
            </label>
            <select
              value={queryParams.operation || ''}
              onChange={(e) =>
                setQueryParams((prev) => ({ ...prev, operation: e.target.value, pageNum: 1 }))
              }
              className="w-full rounded-md border-gray-300 text-sm shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
            >
              <option value="">{l('全部操作', 'All Operations')}</option>
              <option value="create">{l('创建', 'Create')}</option>
              <option value="update">{l('更新', 'Update')}</option>
              <option value="delete">{l('删除', 'Delete')}</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500">
              {l('每页条数', 'Per Page')}
            </label>
            <select
              value={queryParams.pageSize}
              onChange={(e) =>
                setQueryParams((prev) => ({
                  ...prev,
                  pageSize: parseInt(e.target.value),
                  pageNum: 1,
                }))
              }
              className="w-full rounded-md border-gray-300 text-sm shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
            >
              <option value={10}>10</option>
              <option value={20}>20</option>
              <option value={50}>50</option>
            </select>
          </div>
        </div>
      </div>

      {/* My Changes List */}
      {loading ? (
        <div className="flex items-center justify-center py-12">
          <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-indigo-600"></div>
          <span className="ml-3 text-gray-500">
            {l('正在加载审计日志...', 'Loading audit logs...')}
          </span>
        </div>
      ) : myChanges.length === 0 ? (
        <div className="rounded-lg bg-gray-50 py-12 text-center">
          <ClipboardDocumentListIcon className="mx-auto mb-4 h-12 w-12 text-gray-400" />
          <h3 className="text-lg font-medium text-gray-900">
            {l('未找到审计日志', 'No audit logs found')}
          </h3>
          <p className="mt-1 text-gray-500">
            {l('你的数据变更会显示在这里', 'Your data changes will appear here')}
          </p>
        </div>
      ) : (
        <div className="space-y-4">{myChanges.map((log) => renderChangeLogItem(log))}</div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="mt-6 flex items-center justify-between border-t border-gray-200 pt-4">
          <div className="text-sm text-gray-700">
            {l('显示', 'Showing')}{' '}
            <span className="font-medium">
              {(queryParams.pageNum - 1) * queryParams.pageSize + 1}
            </span>{' '}
            {l('到', 'to')}{' '}
            <span className="font-medium">
              {Math.min(queryParams.pageNum * queryParams.pageSize, total)}
            </span>{' '}
            {l('共', 'of')} <span className="font-medium">{total}</span> {l('条日志', 'logs')}
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => handlePageChange(queryParams.pageNum - 1)}
              disabled={queryParams.pageNum === 1}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {l('上一页', 'Previous')}
            </button>
            <button
              onClick={() => handlePageChange(queryParams.pageNum + 1)}
              disabled={queryParams.pageNum === totalPages}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {l('下一页', 'Next')}
            </button>
          </div>
        </div>
      )}

      {/* Lookup Modal */}
      {showLookupModal && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-lg bg-white shadow-xl">
            <div className="flex items-center justify-between border-b px-6 py-4">
              <h2 className="text-lg font-semibold text-gray-900">
                {l('查询记录历史', 'Lookup Record History')}
              </h2>
              <button
                onClick={() => setShowLookupModal(false)}
                className="text-gray-400 hover:text-gray-600"
              >
                <XMarkIcon className="h-6 w-6" />
              </button>
            </div>
            <div className="p-6">
              <div className="mb-4 grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('模型编码', 'Model Code')} *
                  </label>
                  <input
                    type="text"
                    value={lookupModelCode}
                    onChange={(e) => setLookupModelCode(e.target.value)}
                    placeholder={l('例如：order', 'e.g., order')}
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('记录 ID', 'Record ID')} *
                  </label>
                  <input
                    type="text"
                    value={lookupRecordId}
                    onChange={(e) => setLookupRecordId(e.target.value)}
                    placeholder={l('例如：abc123', 'e.g., abc123')}
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
              </div>
              <button
                onClick={handleLookup}
                disabled={!lookupModelCode || !lookupRecordId || historyLoading}
                className="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <MagnifyingGlassIcon className="h-4 w-4" />
                {historyLoading ? l('查询中...', 'Searching...') : l('查询历史', 'Search History')}
              </button>

              {/* Record History Results */}
              {recordHistory.length > 0 && (
                <div className="mt-6 space-y-4">
                  <h3 className="text-sm font-medium text-gray-700">
                    {l('找到', 'Found')} {recordHistory.length} {l('条变更', 'change(s)')}
                  </h3>
                  {recordHistory.map((log) => renderChangeLogItem(log, false))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
