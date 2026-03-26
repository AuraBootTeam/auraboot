import { useState, useEffect } from 'react';
import { Link } from 'react-router';
import {
  PlusIcon,
  MagnifyingGlassIcon,
  EyeIcon,
  PencilIcon,
  TrashIcon,
  DocumentDuplicateIcon,
  StarIcon,
  ClockIcon,
  TagIcon,
  RectangleStackIcon,
} from '@heroicons/react/24/outline';
import { StarIcon as StarIconSolid } from '@heroicons/react/24/solid';
import { useToast } from '~/contexts/ToastContext';

interface Template {
  id: string;
  name: string;
  description?: string;
  category: string;
  tags: string[];
  thumbnailUrl?: string;
  isDefault: boolean;
  isFavorite: boolean;
  usageCount: number;
  duration: number;
  resolution: {
    width: number;
    height: number;
  };
  contentCount: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
}

interface TemplateCategory {
  id: string;
  name: string;
  count: number;
}

const TemplateManager = () => {
  const { showErrorToast, showSuccessToast } = useToast();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [categories, setCategories] = useState<TemplateCategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [sortBy, setSortBy] = useState<'name' | 'usage' | 'created'>('created');
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [formData, setFormData] = useState({
    name: '',
    description: '',
    category: '',
    tags: '',
  });

  useEffect(() => {
    fetchTemplates();
    fetchCategories();
  }, [selectedCategory, sortBy]);

  const fetchTemplates = async () => {
    try {
      setLoading(true);
      const params = new URLSearchParams();
      if (selectedCategory !== 'all') params.append('category', selectedCategory);
      if (searchTerm) params.append('search', searchTerm);
      params.append('sortBy', sortBy);

      const response = await fetch(`/api/templates?${params}`);
      const data = await response.json();

      if (data.success) {
        setTemplates(data.data.items);
      } else {
        showErrorToast('获取模板列表失败');
      }
    } catch (error) {
      console.error('Error fetching templates:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const fetchCategories = async () => {
    try {
      const response = await fetch('/api/template-categories');
      const data = await response.json();

      if (data.success) {
        setCategories(data.data);
      }
    } catch (error) {
      console.error('Error fetching categories:', error);
    }
  };

  const handleCreateTemplate = async () => {
    if (!formData.name.trim()) {
      showErrorToast('请输入模板名称');
      return;
    }

    if (!formData.category) {
      showErrorToast('请选择模板分类');
      return;
    }

    try {
      const response = await fetch('/api/templates', {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          ...formData,
          tags: formData.tags
            .split(',')
            .map((tag) => tag.trim())
            .filter((tag) => tag),
        }),
      });

      const data = await response.json();

      if (data.success) {
        showSuccessToast('模板创建成功');
        setShowCreateModal(false);
        setFormData({ name: '', description: '', category: '', tags: '' });
        fetchTemplates();
        fetchCategories();
      } else {
        showErrorToast(data.message || '创建失败');
      }
    } catch (error) {
      console.error('Error creating template:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleDeleteTemplate = async (template: Template) => {
    if (!confirm(`确定要删除模板 "${template.name}" 吗？`)) {
      return;
    }

    try {
      const response = await fetch(`/api/templates/${template.id}`, {
        method: 'delete',
      });

      const data = await response.json();

      if (data.success) {
        showSuccessToast('模板删除成功');
        fetchTemplates();
        fetchCategories();
      } else {
        showErrorToast(data.message || '删除失败');
      }
    } catch (error) {
      console.error('Error deleting template:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleDuplicateTemplate = async (template: Template) => {
    try {
      const response = await fetch(`/api/templates/${template.id}/duplicate`, {
        method: 'post',
      });

      const data = await response.json();

      if (data.success) {
        showSuccessToast('模板复制成功');
        fetchTemplates();
      } else {
        showErrorToast(data.message || '复制失败');
      }
    } catch (error) {
      console.error('Error duplicating template:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleToggleFavorite = async (template: Template) => {
    try {
      const response = await fetch(`/api/templates/${template.id}/favorite`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ isFavorite: !template.isFavorite }),
      });

      const data = await response.json();

      if (data.success) {
        setTemplates((prev) =>
          prev.map((t) => (t.id === template.id ? { ...t, isFavorite: !t.isFavorite } : t)),
        );
        showSuccessToast(template.isFavorite ? '已取消收藏' : '已添加到收藏');
      } else {
        showErrorToast(data.message || '操作失败');
      }
    } catch (error) {
      console.error('Error toggling favorite:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleUseTemplate = async (template: Template) => {
    try {
      const response = await fetch(`/api/templates/${template.id}/use`, {
        method: 'post',
      });

      const data = await response.json();

      if (data.success) {
        showSuccessToast('正在基于模板创建新节目...');
        // 跳转到节目编辑器
        window.location.href = `/content/program/new?templateId=${template.id}`;
      } else {
        showErrorToast(data.message || '使用模板失败');
      }
    } catch (error) {
      console.error('Error using template:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const filteredTemplates = templates.filter((template) => {
    const matchesSearch =
      template.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      template.description?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      template.tags.some((tag) => tag.toLowerCase().includes(searchTerm.toLowerCase()));
    return matchesSearch;
  });

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  if (loading) {
    return (
      <div className="px-4 sm:px-6 lg:px-8">
        <div className="animate-pulse">
          <div className="mb-4 h-8 w-1/4 rounded bg-gray-200"></div>
          <div className="mb-8 h-4 w-1/2 rounded bg-gray-200"></div>
          <div className="grid grid-cols-1 gap-6 md:grid-cols-3 lg:grid-cols-4">
            {[...Array(8)].map((_, i) => (
              <div key={i} className="h-64 rounded bg-gray-200"></div>
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
          <h1 className="text-2xl font-semibold text-gray-900">模板管理</h1>
          <p className="mt-2 text-sm text-gray-700">管理节目模板，快速创建标准化内容</p>
        </div>
        <div className="mt-4 sm:mt-0">
          <button
            onClick={() => setShowCreateModal(true)}
            className="inline-flex items-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
          >
            <PlusIcon className="mr-2 h-4 w-4" />
            新建模板
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
              placeholder="搜索模板名称、描述或标签..."
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
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as 'name' | 'usage' | 'created')}
            className="rounded-md border border-gray-300 px-3 py-1 text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 focus:outline-none"
          >
            <option value="created">按创建时间</option>
            <option value="name">按名称</option>
            <option value="usage">按使用次数</option>
          </select>
        </div>
      </div>

      {/* 模板列表 */}
      {filteredTemplates.length === 0 ? (
        <div className="py-12 text-center">
          <RectangleStackIcon className="mx-auto h-12 w-12 text-gray-400" />
          <h3 className="mt-2 text-sm font-medium text-gray-900">暂无模板</h3>
          <p className="mt-1 text-sm text-gray-500">
            {searchTerm ? '没有找到匹配的模板' : '开始创建第一个模板'}
          </p>
          {!searchTerm && (
            <div className="mt-6">
              <button
                onClick={() => setShowCreateModal(true)}
                className="inline-flex items-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
              >
                <PlusIcon className="mr-2 h-4 w-4" />
                新建模板
              </button>
            </div>
          )}
        </div>
      ) : (
        <div
          className={
            viewMode === 'grid'
              ? 'grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4'
              : 'space-y-4'
          }
        >
          {filteredTemplates.map((template) =>
            viewMode === 'grid' ? (
              <div
                key={template.id}
                className="rounded-lg bg-white shadow transition-shadow hover:shadow-md"
              >
                <div className="aspect-w-16 aspect-h-9 relative overflow-hidden rounded-t-lg bg-gray-100">
                  {template.thumbnailUrl ? (
                    <img
                      src={template.thumbnailUrl}
                      alt={template.name}
                      className="h-32 w-full object-cover"
                    />
                  ) : (
                    <div className="flex h-32 items-center justify-center">
                      <RectangleStackIcon className="h-12 w-12 text-gray-400" />
                    </div>
                  )}

                  {/* 收藏按钮 */}
                  <button
                    onClick={() => handleToggleFavorite(template)}
                    className="bg-opacity-80 hover:bg-opacity-100 absolute top-2 right-2 rounded-full bg-white p-1"
                  >
                    {template.isFavorite ? (
                      <StarIconSolid className="h-4 w-4 text-yellow-400" />
                    ) : (
                      <StarIcon className="h-4 w-4 text-gray-400" />
                    )}
                  </button>

                  {/* 默认模板标识 */}
                  {template.isDefault && (
                    <div className="absolute top-2 left-2">
                      <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
                        默认
                      </span>
                    </div>
                  )}
                </div>

                <div className="p-4">
                  <div className="mb-2 flex items-start justify-between">
                    <h3
                      className="flex-1 truncate text-sm font-medium text-gray-900"
                      title={template.name}
                    >
                      {template.name}
                    </h3>
                  </div>

                  {template.description && (
                    <p className="mb-2 line-clamp-2 text-xs text-gray-500">
                      {template.description}
                    </p>
                  )}

                  <div className="mb-3 flex items-center justify-between text-xs text-gray-500">
                    <div className="flex items-center space-x-2">
                      <ClockIcon className="h-3 w-3" />
                      <span>{formatDuration(template.duration)}</span>
                    </div>
                    <div className="flex items-center space-x-2">
                      <span>{template.contentCount} 项内容</span>
                    </div>
                  </div>

                  {template.tags.length > 0 && (
                    <div className="mb-3 flex flex-wrap gap-1">
                      {template.tags.slice(0, 3).map((tag, index) => (
                        <span
                          key={index}
                          className="inline-flex items-center rounded bg-gray-100 px-2 py-1 text-xs font-medium text-gray-800"
                        >
                          {tag}
                        </span>
                      ))}
                      {template.tags.length > 3 && (
                        <span className="text-xs text-gray-500">+{template.tags.length - 3}</span>
                      )}
                    </div>
                  )}

                  <div className="flex items-center justify-between">
                    <div className="text-xs text-gray-500">使用 {template.usageCount} 次</div>
                    <div className="flex items-center space-x-1">
                      <button
                        onClick={() => handleUseTemplate(template)}
                        className="rounded bg-indigo-100 px-2 py-1 text-xs font-medium text-indigo-600 hover:bg-indigo-200"
                      >
                        使用
                      </button>
                      <button
                        onClick={() =>
                          window.open(`/content/template/${template.id}/preview`, '_blank')
                        }
                        className="p-1 text-gray-400 hover:text-indigo-600"
                        title="预览"
                      >
                        <EyeIcon className="h-4 w-4" />
                      </button>
                      <Link
                        to={`/content/template/${template.id}/edit`}
                        className="p-1 text-gray-400 hover:text-blue-600"
                        title="编辑"
                      >
                        <PencilIcon className="h-4 w-4" />
                      </Link>
                      <button
                        onClick={() => handleDuplicateTemplate(template)}
                        className="p-1 text-gray-400 hover:text-green-600"
                        title="复制"
                      >
                        <DocumentDuplicateIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleDeleteTemplate(template)}
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
              <div key={template.id} className="rounded-lg bg-white p-4 shadow">
                <div className="flex items-center space-x-4">
                  <div className="flex-shrink-0">
                    {template.thumbnailUrl ? (
                      <img
                        src={template.thumbnailUrl}
                        alt={template.name}
                        className="h-12 w-16 rounded object-cover"
                      />
                    ) : (
                      <div className="flex h-12 w-16 items-center justify-center rounded bg-gray-100">
                        <RectangleStackIcon className="h-6 w-6 text-gray-400" />
                      </div>
                    )}
                  </div>

                  <div className="min-w-0 flex-1">
                    <div className="flex items-center space-x-2">
                      <h3 className="truncate text-sm font-medium text-gray-900">
                        {template.name}
                      </h3>
                      {template.isDefault && (
                        <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
                          默认
                        </span>
                      )}
                      <button onClick={() => handleToggleFavorite(template)} className="p-1">
                        {template.isFavorite ? (
                          <StarIconSolid className="h-4 w-4 text-yellow-400" />
                        ) : (
                          <StarIcon className="h-4 w-4 text-gray-400" />
                        )}
                      </button>
                    </div>

                    {template.description && (
                      <p className="mt-1 line-clamp-1 text-sm text-gray-500">
                        {template.description}
                      </p>
                    )}

                    <div className="mt-2 flex items-center space-x-4 text-xs text-gray-500">
                      <div className="flex items-center space-x-1">
                        <ClockIcon className="h-3 w-3" />
                        <span>{formatDuration(template.duration)}</span>
                      </div>
                      <span>{template.contentCount} 项内容</span>
                      <span>使用 {template.usageCount} 次</span>
                      <span>{new Date(template.createdAt).toLocaleDateString()}</span>
                    </div>

                    {template.tags.length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-1">
                        {template.tags.slice(0, 5).map((tag, index) => (
                          <span
                            key={index}
                            className="inline-flex items-center rounded bg-gray-100 px-2 py-1 text-xs font-medium text-gray-800"
                          >
                            {tag}
                          </span>
                        ))}
                        {template.tags.length > 5 && (
                          <span className="text-xs text-gray-500">+{template.tags.length - 5}</span>
                        )}
                      </div>
                    )}
                  </div>

                  <div className="flex items-center space-x-2">
                    <button
                      onClick={() => handleUseTemplate(template)}
                      className="rounded bg-indigo-100 px-3 py-1 text-sm font-medium text-indigo-600 hover:bg-indigo-200"
                    >
                      使用模板
                    </button>
                    <button
                      onClick={() =>
                        window.open(`/content/template/${template.id}/preview`, '_blank')
                      }
                      className="p-2 text-gray-400 hover:text-indigo-600"
                      title="预览"
                    >
                      <EyeIcon className="h-4 w-4" />
                    </button>
                    <Link
                      to={`/content/template/${template.id}/edit`}
                      className="p-2 text-gray-400 hover:text-blue-600"
                      title="编辑"
                    >
                      <PencilIcon className="h-4 w-4" />
                    </Link>
                    <button
                      onClick={() => handleDuplicateTemplate(template)}
                      className="p-2 text-gray-400 hover:text-green-600"
                      title="复制"
                    >
                      <DocumentDuplicateIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleDeleteTemplate(template)}
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

      {/* 创建模板模态框 */}
      {showCreateModal && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-20 mx-auto w-96 rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">新建模板</h3>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700">模板名称 *</label>
                  <input
                    type="text"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="请输入模板名称"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">模板描述</label>
                  <textarea
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    rows={3}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="请输入模板描述（可选）"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">模板分类 *</label>
                  <select
                    value={formData.category}
                    onChange={(e) => setFormData({ ...formData, category: e.target.value })}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                  >
                    <option value="">请选择分类</option>
                    {categories.map((category) => (
                      <option key={category.id} value={category.id}>
                        {category.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">标签</label>
                  <input
                    type="text"
                    value={formData.tags}
                    onChange={(e) => setFormData({ ...formData, tags: e.target.value })}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="请输入标签，用逗号分隔"
                  />
                </div>
              </div>
              <div className="mt-6 flex justify-end space-x-3">
                <button
                  onClick={() => {
                    setShowCreateModal(false);
                    setFormData({ name: '', description: '', category: '', tags: '' });
                  }}
                  className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  取消
                </button>
                <button
                  onClick={handleCreateTemplate}
                  className="rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
                >
                  创建
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default TemplateManager;
