/**
 * Admin Knowledge Base Management Page
 * Main page for admin document management and monitoring
 */

import { useState } from 'react';
import { AdminDocumentUpload } from './components/AdminDocumentUpload';
import { AdminDocumentList } from './components/AdminDocumentList';
import { AdminTaskMonitor } from './components/AdminTaskMonitor';
import type { AdminDocument } from './types';

interface AdminUploadResponse {
  task_id: string;
  document_id: string;
  status: string;
  priority: number;
  approval_required: boolean;
  message: string;
  estimated_processing_time?: number;
}

interface AdminTaskStatus {
  task_id: string;
  document_id?: string;
  status: string;
  progress: number;
  current_stage: string;
  stage_details: Record<string, any>;
  chunks_created: number;
  quality_score?: number;
  processing_warnings: string[];
  error_message?: string;
  admin_user_id: string;
  priority: number;
  approval_required: boolean;
  created_at: string;
  started_at?: string;
  completed_at?: string;
  estimated_completion?: string;
}

export default function AdminKnowledgeBasePage() {
  const [activeTab, setActiveTab] = useState<'upload' | 'documents' | 'monitor'>('upload');
  const [currentTaskId, setCurrentTaskId] = useState<string | null>(null);
  const [selectedDocument, setSelectedDocument] = useState<AdminDocument | null>(null);
  const [refreshTrigger, setRefreshTrigger] = useState(0);
  const [notifications, setNotifications] = useState<
    Array<{
      id: string;
      type: 'success' | 'error' | 'info';
      message: string;
      timestamp: Date;
    }>
  >([]);

  const addNotification = (type: 'success' | 'error' | 'info', message: string) => {
    const notification = {
      id: Date.now().toString(),
      type,
      message,
      timestamp: new Date(),
    };
    setNotifications((prev) => [notification, ...prev.slice(0, 4)]); // Keep only 5 notifications

    // Auto-remove after 5 seconds
    setTimeout(() => {
      setNotifications((prev) => prev.filter((n) => n.id !== notification.id));
    }, 5000);
  };

  const handleUploadSuccess = (response: AdminUploadResponse) => {
    // 显示提交成功的通知
    if (response.task_id) {
      addNotification(
        'success',
        `文档提交成功！任务 ID: ${response.task_id}。点击"任务监控"标签查看处理进度。`,
      );
      setCurrentTaskId(response.task_id);
    } else {
      addNotification('success', `文档提交成功！文档已保存并开始处理。`);
    }

    // 不自动跳转到监控页面，让用户继续在上传页面操作
    // setActiveTab('monitor'); // 移除自动跳转
    setRefreshTrigger((prev) => prev + 1);
  };

  const handleUploadError = (error: string) => {
    addNotification('error', `上传失败: ${error}`);
  };

  const handleTaskComplete = (task: AdminTaskStatus) => {
    addNotification(
      'success',
      `任务 ${task.task_id} 处理完成！创建了 ${task.chunks_created} 个文档块`,
    );
    setRefreshTrigger((prev) => prev + 1);
  };

  const handleTaskError = (task: AdminTaskStatus) => {
    addNotification('error', `任务 ${task.task_id} 处理失败: ${task.error_message}`);
  };

  const handleDocumentSelect = (document: AdminDocument) => {
    setSelectedDocument(document);
    // Could open a modal or navigate to document details
    addNotification('info', `已选择文档: ${document.title}`);
  };

  const handleDocumentDelete = (documentId: string) => {
    addNotification('success', `文档 ${documentId} 已删除`);
    setRefreshTrigger((prev) => prev + 1);
  };

  const tabs = [
    { id: 'upload', label: '文档上传', icon: '📤' },
    { id: 'documents', label: '文档管理', icon: '📋' },
    { id: 'monitor', label: '任务监控', icon: '📊' },
  ];

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white shadow-sm">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-16 items-center justify-between">
            <div>
              <h1 className="text-xl font-semibold text-gray-900">知识库管理</h1>
              <p className="text-sm text-gray-500">管理员文档上传和知识库维护</p>
            </div>
            <div className="flex items-center space-x-4">
              <span className="text-sm text-gray-500">管理员: admin_001</span>
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-blue-600">
                <span className="text-sm font-medium text-white">A</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Notifications */}
      {notifications.length > 0 && (
        <div className="fixed top-4 right-4 z-50 space-y-2">
          {notifications.map((notification) => (
            <div
              key={notification.id}
              className={`ring-opacity-5 pointer-events-auto w-full max-w-sm overflow-hidden rounded-lg bg-white shadow-lg ring-1 ring-black ${
                notification.type === 'success'
                  ? 'border-l-4 border-green-400'
                  : notification.type === 'error'
                    ? 'border-l-4 border-red-400'
                    : 'border-l-4 border-blue-400'
              }`}
            >
              <div className="p-4">
                <div className="flex items-start">
                  <div className="flex-shrink-0">
                    {notification.type === 'success' && (
                      <div className="h-5 w-5 text-green-400">✓</div>
                    )}
                    {notification.type === 'error' && <div className="h-5 w-5 text-red-400">✗</div>}
                    {notification.type === 'info' && <div className="h-5 w-5 text-blue-400">ℹ</div>}
                  </div>
                  <div className="ml-3 w-0 flex-1">
                    <p className="text-sm font-medium text-gray-900">{notification.message}</p>
                    <p className="mt-1 text-xs text-gray-500">
                      {notification.timestamp.toLocaleTimeString('zh-CN')}
                    </p>
                  </div>
                  <div className="ml-4 flex flex-shrink-0">
                    <button
                      onClick={() =>
                        setNotifications((prev) => prev.filter((n) => n.id !== notification.id))
                      }
                      className="inline-flex rounded-md bg-white text-gray-400 hover:text-gray-500"
                    >
                      <span className="sr-only">Close</span>
                      <span className="h-5 w-5">×</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Main Content */}
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        {/* Tab Navigation */}
        <div className="mb-8">
          <nav className="flex space-x-8">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={`flex items-center rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                  activeTab === tab.id
                    ? 'bg-blue-100 text-blue-700'
                    : 'text-gray-500 hover:bg-gray-100 hover:text-gray-700'
                }`}
              >
                <span className="mr-2">{tab.icon}</span>
                {tab.label}
              </button>
            ))}
          </nav>
        </div>

        {/* Tab Content */}
        <div className="space-y-8">
          {activeTab === 'upload' && (
            <div>
              <AdminDocumentUpload
                onUploadSuccess={handleUploadSuccess}
                onUploadError={handleUploadError}
              />
            </div>
          )}

          {activeTab === 'documents' && (
            <div>
              <AdminDocumentList
                onDocumentSelect={handleDocumentSelect}
                onDocumentDelete={handleDocumentDelete}
                refreshTrigger={refreshTrigger}
              />
            </div>
          )}

          {activeTab === 'monitor' && (
            <div className="grid grid-cols-1 gap-8 lg:grid-cols-2">
              <div>
                <AdminTaskMonitor
                  taskId={currentTaskId || undefined}
                  onTaskComplete={handleTaskComplete}
                  onTaskError={handleTaskError}
                />
              </div>
              <div>
                {/* Task Selection or Recent Tasks */}
                <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
                  <h3 className="mb-4 text-lg font-medium text-gray-900">任务选择</h3>
                  <div className="space-y-3">
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">
                        任务 ID
                      </label>
                      <input
                        type="text"
                        value={currentTaskId || ''}
                        onChange={(e) => setCurrentTaskId(e.target.value || null)}
                        placeholder="输入任务 ID 进行监控"
                        className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      />
                    </div>
                    <button
                      onClick={() => setCurrentTaskId(null)}
                      className="w-full rounded-md bg-gray-100 px-4 py-2 text-gray-700 hover:bg-gray-200"
                    >
                      清除监控
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Selected Document Details (if any) */}
        {selectedDocument && (
          <div className="mt-8">
            <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
              <div className="mb-4 flex items-center justify-between">
                <h3 className="text-lg font-medium text-gray-900">文档详情</h3>
                <button
                  onClick={() => setSelectedDocument(null)}
                  className="text-gray-400 hover:text-gray-600"
                >
                  ×
                </button>
              </div>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <label className="block text-sm font-medium text-gray-700">标题</label>
                  <p className="text-sm text-gray-900">{selectedDocument.title}</p>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">类型</label>
                  <p className="text-sm text-gray-900">{selectedDocument.document_type}</p>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">审批状态</label>
                  <p className="text-sm text-gray-900">{selectedDocument.approval_status}</p>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">质量评分</label>
                  <p className="text-sm text-gray-900">
                    {selectedDocument.quality_score
                      ? `${(selectedDocument.quality_score * 100).toFixed(1)}%`
                      : 'N/A'}
                  </p>
                </div>
              </div>
              {selectedDocument.admin_notes && (
                <div className="mt-4">
                  <label className="block text-sm font-medium text-gray-700">管理员备注</label>
                  <p className="rounded-md bg-gray-50 p-3 text-sm text-gray-900">
                    {selectedDocument.admin_notes}
                  </p>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
