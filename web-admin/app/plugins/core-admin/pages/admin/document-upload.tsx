/**
 * 管理员文档上传页面
 * 支持批量上传文档并查看上传历史
 */

import { useState } from 'react';
import { FileUploader, type FileMetadata } from '~/ui/admin/FileUploader';
import { TaskList } from '~/ui/admin/TaskList';
import { uploadDocument } from '~/shared/services/documentService';
type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [{ title: '文档上传 - 管理员' }, { name: 'description', content: '批量上传研报和文档' }];
}

export default function DocumentUploadPage() {
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploadSuccess, setUploadSuccess] = useState<string | null>(null);
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  const handleFileSelect = async (file: File, metadata: FileMetadata) => {
    try {
      setUploading(true);
      setUploadError(null);
      setUploadSuccess(null);

      const response = await uploadDocument({
        file,
        ...metadata,
      });

      setUploadSuccess(`文档上传成功！任务 ID: ${response.task_id}`);
      // 触发任务列表刷新
      setRefreshTrigger((prev) => prev + 1);
    } catch (err: any) {
      console.error('Upload failed:', err);
      setUploadError(err.response?.data?.detail || err.message || '上传失败');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="border-b bg-white">
        <div className="mx-auto max-w-7xl px-6 py-6">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-bold text-gray-900">文档上传管理</h1>
              <p className="mt-1 text-sm text-gray-500">
                批量上传研报、公告等文档，系统将自动解析并向量化
              </p>
            </div>
            <div className="flex items-center space-x-2 text-sm text-gray-600">
              <span className="inline-flex items-center rounded-full bg-blue-50 px-3 py-1 text-blue-700">
                <span className="mr-2 h-2 w-2 rounded-full bg-blue-600"></span>
                管理员模式
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="mx-auto max-w-7xl px-6 py-8">
        <div className="grid grid-cols-1 gap-8 lg:grid-cols-2">
          {/* Left Column: Upload */}
          <div className="space-y-6">
            <div className="rounded-lg bg-white p-6 shadow-sm">
              <h2 className="mb-4 text-lg font-semibold text-gray-900">上传文档</h2>

              {/* Success Message */}
              {uploadSuccess && (
                <div className="mb-4 rounded-lg border border-green-200 bg-green-50 p-4">
                  <div className="flex items-start space-x-2">
                    <span className="text-xl text-green-600">✓</span>
                    <div className="flex-1">
                      <div className="font-medium text-green-700">上传成功</div>
                      <div className="mt-1 text-sm text-green-600">{uploadSuccess}</div>
                    </div>
                    <button
                      onClick={() => setUploadSuccess(null)}
                      className="text-green-400 hover:text-green-600"
                    >
                      ✕
                    </button>
                  </div>
                </div>
              )}

              {/* Error Message */}
              {uploadError && (
                <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-4">
                  <div className="flex items-start space-x-2">
                    <span className="text-xl text-red-600">⚠️</span>
                    <div className="flex-1">
                      <div className="font-medium text-red-700">上传失败</div>
                      <div className="mt-1 text-sm text-red-600">{uploadError}</div>
                    </div>
                    <button
                      onClick={() => setUploadError(null)}
                      className="text-red-400 hover:text-red-600"
                    >
                      ✕
                    </button>
                  </div>
                </div>
              )}

              {/* File Uploader */}
              <FileUploader onFileSelect={handleFileSelect} disabled={uploading} />

              {/* Uploading Indicator */}
              {uploading && (
                <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 p-4">
                  <div className="flex items-center space-x-3">
                    <div className="h-5 w-5 animate-spin rounded-full border-b-2 border-blue-600"></div>
                    <span className="text-blue-700">正在上传文档...</span>
                  </div>
                </div>
              )}
            </div>

            {/* Upload Guidelines */}
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
              <h3 className="mb-2 font-medium text-blue-900">📋 上传说明</h3>
              <ul className="space-y-1 text-sm text-blue-800">
                <li>• 支持文件格式：PDF、Word、Excel</li>
                <li>• 文件大小限制：最大 50MB</li>
                <li>• 上传后系统将自动解析文档内容</li>
                <li>• 文档将被分块并生成向量索引</li>
                <li>• 处理完成后即可在 AI 对话中使用</li>
              </ul>
            </div>
          </div>

          {/* Right Column: Task List */}
          <div className="space-y-6">
            <div className="rounded-lg bg-white p-6 shadow-sm">
              <div className="mb-4 flex items-center justify-between">
                <h2 className="text-lg font-semibold text-gray-900">上传历史</h2>
                <button
                  onClick={() => setRefreshTrigger((prev) => prev + 1)}
                  className="flex items-center space-x-1 text-sm text-blue-600 hover:text-blue-700"
                >
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
                    />
                  </svg>
                  <span>刷新</span>
                </button>
              </div>

              <TaskList refreshTrigger={refreshTrigger} />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
