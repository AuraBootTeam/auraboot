import { useState, useEffect } from 'react';
import {
  PlusIcon,
  MagnifyingGlassIcon,
  FolderIcon,
  DocumentIcon,
  PhotoIcon,
  VideoCameraIcon,
  MusicalNoteIcon,
  TrashIcon,
  EyeIcon,
  ArrowDownTrayIcon,
  FunnelIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';

interface ContentItem {
  id: string;
  name: string;
  type: 'image' | 'video' | 'audio' | 'document';
  size: number;
  duration?: number; // 视频/音频时长（秒）
  dimensions?: {
    width: number;
    height: number;
  };
  url: string;
  thumbnailUrl?: string;
  createdAt: string;
  updatedAt: string;
  tags: string[];
  category: string;
}

interface ContentCategory {
  id: string;
  name: string;
  count: number;
}

const ContentLibrary = () => {
  const { showErrorToast, showSuccessToast } = useToast();
  const [contents, setContents] = useState<ContentItem[]>([]);
  const [categories, setCategories] = useState<ContentCategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [selectedType, setSelectedType] = useState('all');
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [uploadProgress, setUploadProgress] = useState<{ [key: string]: number }>({});

  useEffect(() => {
    fetchContents();
    fetchCategories();
  }, [selectedCategory, selectedType]);

  const fetchContents = async () => {
    try {
      setLoading(true);
      const params = new URLSearchParams();
      if (selectedCategory !== 'all') params.append('category', selectedCategory);
      if (selectedType !== 'all') params.append('type', selectedType);
      if (searchTerm) params.append('search', searchTerm);

      const response = await fetch(`/api/contents?${params}`);
      const data = await response.json();

      if (data.code === '0') {
        setContents(data.data.items);
      } else {
        showErrorToast('获取内容列表失败');
      }
    } catch (error) {
      console.error('Error fetching contents:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const fetchCategories = async () => {
    try {
      const response = await fetch('/api/content-categories');
      const data = await response.json();

      if (data.code === '0') {
        setCategories(data.data);
      }
    } catch (error) {
      console.error('Error fetching categories:', error);
    }
  };

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files || []);
    setSelectedFiles(files);
  };

  const handleUpload = async () => {
    if (selectedFiles.length === 0) {
      showErrorToast('请选择要上传的文件');
      return;
    }

    setUploading(true);
    const uploadPromises = selectedFiles.map(async (file) => {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('category', selectedCategory !== 'all' ? selectedCategory : 'default');

      try {
        const response = await fetch('/api/contents/upload', {
          method: 'post',
          body: formData,
        });

        const data = await response.json();

        if (data.code === '0') {
          setUploadProgress((prev) => ({ ...prev, [file.name]: 100 }));
          return data.data;
        } else {
          throw new Error(data.message || '上传失败');
        }
      } catch (error) {
        console.error(`Error uploading ${file.name}:`, error);
        showErrorToast(`${file.name} 上传失败`);
        return null;
      }
    });

    try {
      const results = await Promise.all(uploadPromises);
      const successCount = results.filter((result) => result !== null).length;

      if (successCount > 0) {
        showSuccessToast(`成功上传 ${successCount} 个文件`);
        setShowUploadModal(false);
        setSelectedFiles([]);
        setUploadProgress({});
        fetchContents();
        fetchCategories();
      }
    } catch (error) {
      console.error('Error in batch upload:', error);
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async (content: ContentItem) => {
    if (!confirm(`确定要删除 "${content.name}" 吗？`)) {
      return;
    }

    try {
      const response = await fetch(`/api/contents/${content.id}`, {
        method: 'delete',
      });

      const data = await response.json();

      if (data.code === '0') {
        showSuccessToast('删除成功');
        fetchContents();
        fetchCategories();
      } else {
        showErrorToast(data.message || '删除失败');
      }
    } catch (error) {
      console.error('Error deleting content:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleDownload = (content: ContentItem) => {
    const link = document.createElement('a');
    link.href = content.url;
    link.download = content.name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const getFileIcon = (type: string) => {
    switch (type) {
      case 'image':
        return <PhotoIcon className="h-8 w-8 text-green-500" />;
      case 'video':
        return <VideoCameraIcon className="h-8 w-8 text-blue-500" />;
      case 'audio':
        return <MusicalNoteIcon className="h-8 w-8 text-purple-500" />;
      default:
        return <DocumentIcon className="h-8 w-8 text-gray-500" />;
    }
  };

  const formatFileSize = (bytes: number) => {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const filteredContents = contents.filter((content) => {
    const matchesSearch =
      content.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      content.tags.some((tag) => tag.toLowerCase().includes(searchTerm.toLowerCase()));
    return matchesSearch;
  });

  if (loading) {
    return (
      <div className="px-4 sm:px-6 lg:px-8">
        <div className="animate-pulse">
          <div className="mb-4 h-8 w-1/4 rounded bg-gray-200"></div>
          <div className="mb-8 h-4 w-1/2 rounded bg-gray-200"></div>
          <div className="grid grid-cols-1 gap-6 md:grid-cols-3 lg:grid-cols-4">
            {[...Array(8)].map((_, i) => (
              <div key={i} className="h-48 rounded bg-gray-200"></div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 sm:px-6 lg:px-8">
      {/* 页面头部 */}
      <div className="mb-8 sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">内容库</h1>
          <p className="mt-2 text-sm text-gray-700">管理媒体文件和素材资源</p>
        </div>
        <div className="mt-4 sm:mt-0">
          <button
            onClick={() => setShowUploadModal(true)}
            className="inline-flex items-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
          >
            <PlusIcon className="mr-2 h-4 w-4" />
            上传文件
          </button>
        </div>
      </div>

      {/* 筛选和搜索 */}
      <div className="mb-6 space-y-4">
        <div className="flex flex-col space-y-4 sm:flex-row sm:items-center sm:justify-between sm:space-y-0">
          {/* 搜索框 */}
          <div className="relative max-w-md flex-1">
            <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
              <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" />
            </div>
            <input
              type="text"
              placeholder="搜索文件名或标签..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="block w-full rounded-md border border-gray-300 bg-white py-2 pr-3 pl-10 leading-5 placeholder-gray-500 focus:border-indigo-500 focus:placeholder-gray-400 focus:ring-1 focus:ring-indigo-500 focus:outline-none"
            />
          </div>

          {/* 视图切换 */}
          <div className="flex items-center space-x-2">
            <button
              onClick={() => setViewMode('grid')}
              className={`rounded-md p-2 ${
                viewMode === 'grid'
                  ? 'bg-indigo-100 text-indigo-600'
                  : 'text-gray-400 hover:text-gray-600'
              }`}
            >
              <svg className="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
                <path d="M5 3a2 2 0 00-2 2v2a2 2 0 002 2h2a2 2 0 002-2V5a2 2 0 00-2-2H5zM5 11a2 2 0 00-2 2v2a2 2 0 002 2h2a2 2 0 002-2v-2a2 2 0 00-2-2H5zM11 5a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V5zM11 13a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" />
              </svg>
            </button>
            <button
              onClick={() => setViewMode('list')}
              className={`rounded-md p-2 ${
                viewMode === 'list'
                  ? 'bg-indigo-100 text-indigo-600'
                  : 'text-gray-400 hover:text-gray-600'
              }`}
            >
              <svg className="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
                <path
                  fillRule="evenodd"
                  d="M3 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z"
                  clipRule="evenodd"
                />
              </svg>
            </button>
          </div>
        </div>

        {/* 筛选器 */}
        <div className="flex flex-wrap items-center gap-4">
          <div className="flex items-center space-x-2">
            <FunnelIcon className="h-4 w-4 text-gray-400" />
            <span className="text-sm text-gray-500">筛选:</span>
          </div>

          <select
            value={selectedCategory}
            onChange={(e) => setSelectedCategory(e.target.value)}
            className="rounded-md border border-gray-300 px-3 py-1 text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 focus:outline-none"
          >
            <option value="all">所有分类</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name} ({category.count})
              </option>
            ))}
          </select>

          <select
            value={selectedType}
            onChange={(e) => setSelectedType(e.target.value)}
            className="rounded-md border border-gray-300 px-3 py-1 text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 focus:outline-none"
          >
            <option value="all">所有类型</option>
            <option value="image">图片</option>
            <option value="video">视频</option>
            <option value="audio">音频</option>
            <option value="document">文档</option>
          </select>
        </div>
      </div>

      {/* 内容列表 */}
      {filteredContents.length === 0 ? (
        <div className="py-12 text-center">
          <FolderIcon className="mx-auto h-12 w-12 text-gray-400" />
          <h3 className="mt-2 text-sm font-medium text-gray-900">暂无内容</h3>
          <p className="mt-1 text-sm text-gray-500">
            {searchTerm ? '没有找到匹配的文件' : '开始上传第一个文件'}
          </p>
          {!searchTerm && (
            <div className="mt-6">
              <button
                onClick={() => setShowUploadModal(true)}
                className="inline-flex items-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
              >
                <PlusIcon className="mr-2 h-4 w-4" />
                上传文件
              </button>
            </div>
          )}
        </div>
      ) : (
        <div
          className={
            viewMode === 'grid'
              ? 'grid grid-cols-1 gap-6 md:grid-cols-3 lg:grid-cols-4'
              : 'space-y-4'
          }
        >
          {filteredContents.map((content) =>
            viewMode === 'grid' ? (
              <div
                key={content.id}
                className="rounded-lg bg-white shadow transition-shadow hover:shadow-md"
              >
                <div className="aspect-w-16 aspect-h-9 overflow-hidden rounded-t-lg bg-gray-100">
                  {content.type === 'image' ? (
                    <img
                      src={content.thumbnailUrl || content.url}
                      alt={content.name}
                      className="h-32 w-full object-cover"
                    />
                  ) : (
                    <div className="flex h-32 items-center justify-center">
                      {getFileIcon(content.type)}
                    </div>
                  )}
                </div>
                <div className="p-4">
                  <h3 className="truncate text-sm font-medium text-gray-900" title={content.name}>
                    {content.name}
                  </h3>
                  <div className="mt-1 flex items-center justify-between text-xs text-gray-500">
                    <span>{formatFileSize(content.size)}</span>
                    {content.duration && <span>{formatDuration(content.duration)}</span>}
                  </div>
                  {content.dimensions && (
                    <div className="mt-1 text-xs text-gray-500">
                      {content.dimensions.width} × {content.dimensions.height}
                    </div>
                  )}
                  <div className="mt-2 flex items-center justify-between">
                    <div className="flex space-x-1">
                      <button
                        onClick={() => window.open(content.url, '_blank')}
                        className="p-1 text-gray-400 hover:text-indigo-600"
                        title="预览"
                      >
                        <EyeIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleDownload(content)}
                        className="p-1 text-gray-400 hover:text-green-600"
                        title="下载"
                      >
                        <ArrowDownTrayIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleDelete(content)}
                        className="p-1 text-gray-400 hover:text-red-600"
                        title="删除"
                      >
                        <TrashIcon className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div key={content.id} className="rounded-lg bg-white p-4 shadow">
                <div className="flex items-center space-x-4">
                  <div className="flex-shrink-0">
                    {content.type === 'image' ? (
                      <img
                        src={content.thumbnailUrl || content.url}
                        alt={content.name}
                        className="h-12 w-12 rounded object-cover"
                      />
                    ) : (
                      <div className="flex h-12 w-12 items-center justify-center">
                        {getFileIcon(content.type)}
                      </div>
                    )}
                  </div>
                  <div className="min-w-0 flex-1">
                    <h3 className="truncate text-sm font-medium text-gray-900">{content.name}</h3>
                    <div className="mt-1 flex items-center space-x-4 text-xs text-gray-500">
                      <span>{formatFileSize(content.size)}</span>
                      {content.duration && <span>{formatDuration(content.duration)}</span>}
                      {content.dimensions && (
                        <span>
                          {content.dimensions.width} × {content.dimensions.height}
                        </span>
                      )}
                      <span>{new Date(content.createdAt).toLocaleDateString()}</span>
                    </div>
                  </div>
                  <div className="flex items-center space-x-2">
                    <button
                      onClick={() => window.open(content.url, '_blank')}
                      className="p-2 text-gray-400 hover:text-indigo-600"
                      title="预览"
                    >
                      <EyeIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleDownload(content)}
                      className="p-2 text-gray-400 hover:text-green-600"
                      title="下载"
                    >
                      <ArrowDownTrayIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleDelete(content)}
                      className="p-2 text-gray-400 hover:text-red-600"
                      title="删除"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              </div>
            ),
          )}
        </div>
      )}

      {/* 上传模态框 */}
      {showUploadModal && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-20 mx-auto w-96 rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">上传文件</h3>

              <div className="space-y-4">
                <div>
                  <label className="mb-2 block text-sm font-medium text-gray-700">选择文件</label>
                  <input
                    type="file"
                    multiple
                    accept="image/*,video/*,audio/*,.pdf,.doc,.docx,.ppt,.pptx"
                    onChange={handleFileSelect}
                    className="block w-full text-sm text-gray-500 file:mr-4 file:rounded-full file:border-0 file:bg-indigo-50 file:px-4 file:py-2 file:text-sm file:font-semibold file:text-indigo-700 hover:file:bg-indigo-100"
                  />
                </div>

                {selectedFiles.length > 0 && (
                  <div className="space-y-2">
                    <p className="text-sm text-gray-600">已选择 {selectedFiles.length} 个文件:</p>
                    <div className="max-h-32 space-y-1 overflow-y-auto">
                      {selectedFiles.map((file, index) => (
                        <div key={index} className="flex justify-between text-xs text-gray-500">
                          <span className="truncate">{file.name}</span>
                          <span>{formatFileSize(file.size)}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {uploading && (
                  <div className="space-y-2">
                    <p className="text-sm text-gray-600">上传进度:</p>
                    {Object.entries(uploadProgress).map(([fileName, progress]) => (
                      <div key={fileName} className="space-y-1">
                        <div className="flex justify-between text-xs">
                          <span className="truncate">{fileName}</span>
                          <span>{progress}%</span>
                        </div>
                        <div className="h-1 w-full rounded-full bg-gray-200">
                          <div
                            className="h-1 rounded-full bg-indigo-600 transition-all"
                            style={{ width: `${progress}%` }}
                          ></div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div className="mt-6 flex justify-end space-x-3">
                <button
                  onClick={() => {
                    setShowUploadModal(false);
                    setSelectedFiles([]);
                    setUploadProgress({});
                  }}
                  disabled={uploading}
                  className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  onClick={handleUpload}
                  disabled={selectedFiles.length === 0 || uploading}
                  className="rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {uploading ? '上传中...' : '开始上传'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ContentLibrary;
