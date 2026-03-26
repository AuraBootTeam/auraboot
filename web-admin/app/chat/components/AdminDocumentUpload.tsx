/**
 * Admin Document Upload Component
 * Engineering-grade file upload with XHR progress tracking
 *
 * Architecture: Browser → BFF → Spring Boot (stream forwarding)
 * - Zero disk storage in BFF layer
 * - Real-time progress tracking via XHR
 * - Proper error handling and cancellation
 * - Large file support (up to 2GB)
 */

import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from 'react';
import { useUser } from '~/contexts/AuthContext';

interface AdminDocumentUploadProps {
  onUploadSuccess?: (response: AdminDocumentUploadResponse) => void;
  onUploadError?: (error: string) => void;
  disabled?: boolean;
}

interface AdminDocumentUploadResponse {
  task_id: string;
  document_id: string;
  status: string;
  priority: number;
  approval_required: boolean;
  message: string;
  estimated_processing_time?: number;
  file_name?: string;
  file_size?: number;
  download_url?: string;
}

interface UploadProgress {
  loaded: number;
  total: number;
  percentage: number;
}

interface AdminDocumentMetadata {
  admin_user_id: string;
  document_type: string;
  priority: number;
  approval_required: boolean;
  title?: string;
  symbol?: string;
  publish_date?: string;
  broker?: string;
  admin_notes?: string;
}

// File type configuration with higher limits for admin
const ADMIN_FILE_CONFIG = {
  allowedExtensions: ['.pdf', '.docx', '.doc', '.txt', '.xlsx', '.xls', '.pptx', '.ppt'],
  maxFileSize: 2 * 1024 * 1024 * 1024, // 2GB for admin users
  supportedTypes: {
    pdf: { label: 'PDF文档', icon: '📄' },
    word: { label: 'Word文档', icon: '📝' },
    excel: { label: 'Excel表格', icon: '📊' },
    powerpoint: { label: 'PowerPoint演示', icon: '📈' },
    text: { label: '文本文件', icon: '📃' },
  },
} as const;

export function AdminDocumentUpload({
  onUploadSuccess,
  onUploadError,
  disabled,
}: AdminDocumentUploadProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const xhrRef = useRef<XMLHttpRequest | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [uploadedFiles, setUploadedFiles] = useState<AdminDocumentUploadResponse[]>([]);

  // Get authenticated user context
  const { user } = useUser();

  // Form state with enhanced metadata
  const [formData, setFormData] = useState({
    title: '',
    document_type: 'research_report',
    symbol: '',
    publish_date: '',
    broker: '',
    priority: 3,
    approval_required: true,
    admin_notes: '',
  });

  /**
   * Validate uploaded file against admin constraints
   */
  const validateFile = (file: File): string | null => {
    // Check file size (2GB for admin)
    if (file.size > ADMIN_FILE_CONFIG.maxFileSize) {
      const maxSizeGB = ADMIN_FILE_CONFIG.maxFileSize / (1024 * 1024 * 1024);
      return `文件大小超过限制（最大 ${maxSizeGB}GB）`;
    }

    // Check file type
    const extension = '.' + file.name.split('.').pop()?.toLowerCase();
    if (!ADMIN_FILE_CONFIG.allowedExtensions.includes(extension as any)) {
      return `不支持的文件类型：${extension}。支持的格式：${ADMIN_FILE_CONFIG.allowedExtensions.join(', ')}`;
    }

    // Check for empty files
    if (file.size === 0) {
      return '文件不能为空';
    }

    return null;
  };

  useEffect(() => {
    return () => {
      // Cleanup: abort any ongoing upload
      if (xhrRef.current) {
        xhrRef.current.abort();
      }
    };
  }, []);

  /**
   * Engineering-grade file upload with XHR progress tracking
   * Architecture: Browser → BFF (/api/upload) → Spring Boot ()
   */
  const handleFileUpload = async (file: File) => {
    // Check if user is authenticated
    if (!user) {
      onUploadError?.('用户未登录，请先登录后再上传文件');
      return;
    }

    // Validate file first
    const validationError = validateFile(file);
    if (validationError) {
      onUploadError?.(validationError);
      return;
    }

    // Abort any existing upload
    if (xhrRef.current) {
      xhrRef.current.abort();
    }

    setIsUploading(true);
    setUploadProgress({ loaded: 0, total: file.size, percentage: 0 });

    // Prepare metadata for headers (backend expects octet-stream with metadata in headers)
    const metadata: AdminDocumentMetadata = {
      admin_user_id: user?.pid || user?.id || 'unknown_user',
      title: formData.title || file.name,
      document_type: formData.document_type,
      priority: formData.priority,
      approval_required: formData.approval_required,
      symbol: formData.symbol,
      publish_date: formData.publish_date,
      broker: formData.broker,
      admin_notes: formData.admin_notes,
    };

    // Create XHR instance
    const xhr = new XMLHttpRequest();
    xhrRef.current = xhr;

    return new Promise<void>((resolve, reject) => {
      // Upload progress tracking
      xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
          const percentage = Math.round((event.loaded / event.total) * 100);
          setUploadProgress({
            loaded: event.loaded,
            total: event.total,
            percentage,
          });
        }
      });

      // Upload completion
      xhr.addEventListener('load', () => {
        try {
          if (xhr.status >= 200 && xhr.status < 300) {
            const response = JSON.parse(xhr.responseText);
            // 处理响应结构: ApiResponse.success(AdminFileUploadResponse)
            const actualData = response.data || response;

            // 添加文件信息到已上传列表 (taskId will be created during submit)
            const fileInfo = {
              task_id: actualData.taskId || null, // Upload stage doesn't create task
              document_id: actualData.documentId,
              status: actualData.status || 'uploaded',
              priority: 3,
              approval_required: true,
              message: response.message || 'Upload successful',
              file_name: file.name,
              file_size: file.size,
              download_url: actualData.documentId
                ? `/api/files/${actualData.documentId}/download`
                : '#',
            };

            setUploadedFiles((prev) => [...prev, fileInfo]);

            // 不显示toast通知，不重置表单
            // onUploadSuccess?.(response); // 移除toast通知
            resolve();
          } else {
            const errorResponse = JSON.parse(xhr.responseText);
            const errorMessage = errorResponse.message || `上传失败 (${xhr.status})`;
            onUploadError?.(errorMessage);
            reject(new Error(errorMessage));
          }
        } catch (parseError) {
          const errorMessage = `响应解析失败: ${xhr.responseText}`;
          onUploadError?.(errorMessage);
          reject(new Error(errorMessage));
        }
      });

      // Network errors
      xhr.addEventListener('error', () => {
        const errorMessage = '网络错误，请检查连接后重试';
        onUploadError?.(errorMessage);
        reject(new Error(errorMessage));
      });

      // Upload timeout
      xhr.addEventListener('timeout', () => {
        const errorMessage = '上传超时，请重试';
        onUploadError?.(errorMessage);
        reject(new Error(errorMessage));
      });

      // Upload abortion
      xhr.addEventListener('abort', () => {
        const errorMessage = '上传已取消';
        onUploadError?.(errorMessage);
        reject(new Error(errorMessage));
      });

      // Configure and send request - 使用octet-stream格式
      xhr.open('post', '/api/files/upload');
      xhr.timeout = 10 * 60 * 1000; // 10 minutes timeout for large files
      xhr.withCredentials = true; // Include cookies for authentication

      // Set headers for metadata (backend expects metadata in headers)
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));
      xhr.setRequestHeader('X-Mime-Type', file.type || 'application/octet-stream');
      xhr.setRequestHeader('X-Request-Id', Date.now().toString());

      // Set metadata headers
      xhr.setRequestHeader('X-Meta-document_type', metadata.document_type);
      xhr.setRequestHeader('X-Meta-admin_user_id', metadata.admin_user_id);
      xhr.setRequestHeader('X-Meta-priority', metadata.priority.toString());
      xhr.setRequestHeader('X-Meta-approval_required', metadata.approval_required.toString());

      if (metadata.title) {
        xhr.setRequestHeader('X-Meta-title', encodeURIComponent(metadata.title));
      }
      if (metadata.symbol) {
        xhr.setRequestHeader('X-Meta-symbol', encodeURIComponent(metadata.symbol));
      }
      if (metadata.publish_date) {
        xhr.setRequestHeader('X-Meta-publish_date', encodeURIComponent(metadata.publish_date));
      }
      if (metadata.broker) {
        xhr.setRequestHeader('X-Meta-broker', encodeURIComponent(metadata.broker));
      }
      if (metadata.admin_notes) {
        xhr.setRequestHeader('X-Meta-admin_notes', encodeURIComponent(metadata.admin_notes));
      }

      // Send the file as binary data
      xhr.send(file);
    }).finally(() => {
      xhrRef.current = null;
      setIsUploading(false);
      setUploadProgress(null);
    });
  };

  /**
   * Submit complete document with metadata and files
   */
  const handleSubmit = async () => {
    if (uploadedFiles.length === 0) {
      onUploadError?.('请先上传文件');
      return;
    }

    if (!user) {
      onUploadError?.('用户未登录，请先登录后再提交');
      return;
    }

    try {
      // 准备完整的提交数据 - 使用驼峰命名匹配后端DTO
      const submitData = {
        // 表单元数据
        title: formData.title || uploadedFiles[0].file_name,
        documentType: formData.document_type,
        symbol: formData.symbol,
        publishDate: formData.publish_date,
        broker: formData.broker,
        priority: formData.priority,
        approvalRequired: formData.approval_required,
        adminNotes: formData.admin_notes,
        adminUserId: user?.pid || user?.id || 'unknown_user',

        // 已上传文件信息 (taskId will be null from upload, created during submit)
        uploadedFiles: uploadedFiles.map((file) => ({
          taskId: file.task_id || null, // Upload doesn't create task, submit will create it
          documentId: file.document_id,
          fileName: file.file_name,
          fileSize: file.file_size,
          downloadUrl: file.download_url,
        })),
      };

      // 发送提交请求到后端 - 修正URL
      const response = await fetch('/api/files/submit', {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(submitData),
      });

      if (response.ok) {
        const result = await response.json();

        // 处理 ApiResponse 包装结构
        const actualData = result.data || result;

        // 调用成功回调，传递提交结果
        onUploadSuccess?.(actualData);

        // 清空已上传文件列表，但保留表单数据以便继续操作
        setUploadedFiles([]);
      } else {
        const errorData = await response.json();
        // 处理验证错误，显示详细信息
        let errorMessage = errorData.message || `提交失败 (${response.status})`;
        if (errorData.data && typeof errorData.data === 'object') {
          const validationErrors = Object.entries(errorData.data)
            .map(([field, message]) => `${field}: ${message}`)
            .join(', ');
          errorMessage = `验证失败: ${validationErrors}`;
        }

        onUploadError?.(errorMessage);
      }
    } catch (error) {
      console.error('提交过程中发生错误:', error);
      onUploadError?.('提交失败，请检查网络连接后重试');
    }
  };

  /**
   * Remove uploaded file
   */
  const removeUploadedFile = (taskId: string) => {
    setUploadedFiles((prev) => prev.filter((file) => file.task_id !== taskId));
  };

  /**
   * Cancel ongoing upload
   */
  const cancelUpload = () => {
    if (xhrRef.current) {
      xhrRef.current.abort();
    }
  };

  /**
   * Handle file selection from input
   */
  const handleFileSelect = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      handleFileUpload(file).catch(console.error);
    }
    // Clear input to allow re-upload of same file
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  /**
   * Handle click to open file dialog
   */
  const handleClick = () => {
    if (!disabled && !isUploading) {
      fileInputRef.current?.click();
    }
  };

  /**
   * Drag and drop event handlers
   */
  const handleDragEnter = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!disabled && !isUploading) {
      setIsDragging(true);
    }
  };

  const handleDragLeave = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  };

  const handleDragOver = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
  };

  const handleDrop = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);

    if (disabled || isUploading) {
      return;
    }

    const file = e.dataTransfer.files?.[0];
    if (file) {
      handleFileUpload(file).catch(console.error);
    }
  };

  /**
   * Format file size for display
   */
  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${(bytes / Math.pow(k, i)).toFixed(2)} ${sizes[i]}`;
  };

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-medium text-gray-900">管理员文档上传</h3>
        {user ? (
          <div className="flex items-center space-x-2 text-sm text-green-600">
            <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                clipRule="evenodd"
              />
            </svg>
            <span>已登录: {user.name || user.pid || user.id}</span>
          </div>
        ) : (
          <div className="flex items-center space-x-2 text-sm text-red-600">
            <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z"
                clipRule="evenodd"
              />
            </svg>
            <span>未登录</span>
          </div>
        )}
      </div>

      {!user && (
        <div className="mb-4 rounded-md border border-yellow-200 bg-yellow-50 p-3">
          <div className="flex">
            <svg className="mr-2 h-5 w-5 text-yellow-400" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                clipRule="evenodd"
              />
            </svg>
            <div>
              <p className="text-sm text-yellow-800">
                <strong>注意：</strong>您需要先登录才能上传文件。请先登录后再进行文档上传操作。
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Upload Form */}
      <div className="mb-6 space-y-4">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">文档标题</label>
            <input
              type="text"
              value={formData.title}
              onChange={(e) => setFormData({ ...formData, title: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              placeholder="留空将使用文件名"
              disabled={isUploading}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">文档类型</label>
            <select
              value={formData.document_type}
              onChange={(e) => setFormData({ ...formData, document_type: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              disabled={isUploading}
            >
              <option value="research_report">研究报告</option>
              <option value="financial_statement">财务报表</option>
              <option value="market_analysis">市场分析</option>
              <option value="news">新闻资讯</option>
              <option value="user_note">用户笔记</option>
            </select>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">股票代码</label>
            <input
              type="text"
              value={formData.symbol}
              onChange={(e) => setFormData({ ...formData, symbol: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              placeholder="如：TSLA, 000001"
              disabled={isUploading}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">发布日期</label>
            <input
              type="date"
              value={formData.publish_date}
              onChange={(e) => setFormData({ ...formData, publish_date: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              disabled={isUploading}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">券商名称</label>
            <input
              type="text"
              value={formData.broker}
              onChange={(e) => setFormData({ ...formData, broker: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              placeholder="如：中信证券"
              disabled={isUploading}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">处理优先级</label>
            <select
              value={formData.priority}
              onChange={(e) => setFormData({ ...formData, priority: parseInt(e.target.value) })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              disabled={isUploading}
            >
              <option value={1}>低优先级 (1)</option>
              <option value={2}>较低优先级 (2)</option>
              <option value={3}>普通优先级 (3)</option>
              <option value={4}>高优先级 (4)</option>
              <option value={5}>最高优先级 (5)</option>
            </select>
          </div>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">管理员备注</label>
          <textarea
            value={formData.admin_notes}
            onChange={(e) => setFormData({ ...formData, admin_notes: e.target.value })}
            rows={3}
            className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            placeholder="添加处理说明或备注信息"
            disabled={isUploading}
          />
        </div>

        <div className="flex items-center">
          <input
            type="checkbox"
            id="approval_required"
            checked={formData.approval_required}
            onChange={(e) => setFormData({ ...formData, approval_required: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            disabled={isUploading}
          />
          <label htmlFor="approval_required" className="ml-2 block text-sm text-gray-700">
            需要内容审批
          </label>
        </div>
      </div>

      {/* Upload Area */}
      <div
        className={`relative rounded-lg border-2 border-dashed p-8 text-center transition-colors ${
          isDragging ? 'border-blue-400 bg-blue-50' : 'border-gray-300 hover:border-gray-400'
        } ${disabled || isUploading ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}
        onDragEnter={handleDragEnter}
        onDragLeave={handleDragLeave}
        onDragOver={handleDragOver}
        onDrop={handleDrop}
        onClick={handleClick}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept={ADMIN_FILE_CONFIG.allowedExtensions.join(',')}
          onChange={handleFileSelect}
          className="hidden"
        />

        {isUploading ? (
          <div className="space-y-4">
            <div className="flex items-center justify-center space-x-3">
              <svg className="h-8 w-8 animate-spin text-blue-600" fill="none" viewBox="0 0 24 24">
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                />
              </svg>
              <button
                onClick={cancelUpload}
                className="rounded-md border border-red-300 px-3 py-1 text-sm text-red-600 transition-colors hover:bg-red-50 hover:text-red-800"
              >
                取消上传
              </button>
            </div>
            <div>
              <p className="text-sm font-medium text-gray-900">正在上传文件...</p>
              {uploadProgress && (
                <div className="mt-3 space-y-2">
                  <div className="flex justify-between text-xs text-gray-600">
                    <span>{uploadProgress.percentage}%</span>
                    <span>
                      {formatFileSize(uploadProgress.loaded)} /{' '}
                      {formatFileSize(uploadProgress.total)}
                    </span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-gray-200">
                    <div
                      className="h-2 rounded-full bg-blue-600 transition-all duration-300 ease-out"
                      style={{ width: `${uploadProgress.percentage}%` }}
                    />
                  </div>
                  <div className="text-center text-xs text-gray-500">
                    通过 BFF 层流式转发到 Spring Boot 后端
                  </div>
                </div>
              )}
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-center">
              <svg
                className="h-12 w-12 text-gray-400"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                />
              </svg>
            </div>
            <div className="text-center">
              <p className="text-lg font-medium text-gray-900">
                {isDragging ? '释放文件以上传' : '点击或拖拽文件到此处'}
              </p>
              <p className="mt-1 text-sm text-gray-500">
                支持 {ADMIN_FILE_CONFIG.allowedExtensions.join(', ')} 格式
              </p>
              <p className="mt-1 text-xs text-gray-400">
                最大文件大小: {formatFileSize(ADMIN_FILE_CONFIG.maxFileSize)}
              </p>
            </div>
            <div className="flex justify-center space-x-4 text-xs text-gray-400">
              {Object.entries(ADMIN_FILE_CONFIG.supportedTypes).map(([key, { label, icon }]) => (
                <div key={key} className="flex items-center space-x-1">
                  <span>{icon}</span>
                  <span>{label}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Uploaded Files Display */}
      {uploadedFiles.length > 0 && (
        <div className="mt-6 mb-6">
          <h4 className="mb-3 text-sm font-medium text-gray-900">已上传文件</h4>
          <div className="space-y-2">
            {uploadedFiles.map((file) => (
              <div
                key={file.task_id}
                className="flex items-center justify-between rounded-md border border-green-200 bg-green-50 p-3"
              >
                <div className="flex items-center space-x-3">
                  <div className="flex-shrink-0">
                    <svg className="h-5 w-5 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                      <path
                        fillRule="evenodd"
                        d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                        clipRule="evenodd"
                      />
                    </svg>
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-gray-900">{file.file_name}</p>
                    <p className="text-xs text-gray-500">
                      {formatFileSize(file.file_size || 0)} • 文档ID: {file.document_id}
                      {file.task_id && ` • 任务ID: ${file.task_id}`}
                    </p>
                  </div>
                </div>
                <div className="flex items-center space-x-2">
                  <a
                    href={file.download_url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-sm text-blue-600 hover:text-blue-800"
                  >
                    下载
                  </a>
                  <button
                    onClick={() => removeUploadedFile(file.task_id)}
                    className="text-sm text-red-600 hover:text-red-800"
                  >
                    移除
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Submit Button */}
      <div className="mt-6 flex justify-center">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={isUploading || uploadedFiles.length === 0}
          className="rounded-md border border-transparent bg-blue-600 px-8 py-3 text-base font-medium text-white shadow-sm hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
        >
          提交文档 {uploadedFiles.length > 0 && `(${uploadedFiles.length})`}
        </button>
      </div>
    </div>
  );
}
