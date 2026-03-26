import { useState, useEffect } from 'react';
import {
  useNavigate,
  useNavigation,
  useActionData,
  redirect,
  type ActionFunctionArgs,
} from 'react-router';
import { useToast } from '~/contexts/ToastContext';
import { createTask } from '~/services/crawler';

// Action - 处理任务创建
export async function action({ request }: ActionFunctionArgs) {
  const formData = await request.formData();

  try {
    const entryUrls = (formData.get('entryUrls') as string).split('\n').filter((url) => url.trim());

    const config = {
      entry_urls: entryUrls,
      depth: 1,
      parser_profile: 'default',
    };

    await createTask(request, {
      name: formData.get('name') as string,
      description: formData.get('description') as string,
      site: formData.get('site') as string,
      config,
    });

    // 重定向到任务列表页面
    return redirect('/crawler/tasks');
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : '创建失败',
    };
  }
}

export default function CrawlerTaskNew() {
  const { showSuccessToast, showErrorToast } = useToast();
  const navigate = useNavigate();
  const navigation = useNavigation();
  const actionData = useActionData<typeof action>();
  const [formData, setFormData] = useState({
    name: '',
    description: '',
    site: 'xueqiu',
    entryUrls: '',
  });

  const isSubmitting = navigation.state === 'submitting';

  // 显示错误提示
  useEffect(() => {
    if (actionData && !actionData.success) {
      showErrorToast(actionData.error || '创建失败');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionData]);

  const handleSubmit = (e: React.FormEvent) => {
    // 客户端验证
    if (!formData.name) {
      e.preventDefault();
      showErrorToast('请输入任务名称');
      return;
    }

    if (!formData.entryUrls) {
      e.preventDefault();
      showErrorToast('请输入入口 URL');
      return;
    }

    // React Router 会自动处理表单提交
  };

  return (
    <div className="p-6">
      <div className="mx-auto max-w-2xl rounded-lg bg-white shadow-md">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4">
          <h1 className="text-2xl font-semibold text-gray-900">创建爬虫任务</h1>
        </div>

        {/* Form */}
        <form method="post" onSubmit={handleSubmit} className="space-y-6 p-6">
          {/* 任务名称 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">
              任务名称 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              name="name"
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
              placeholder="例如：雪球寒武纪爬取"
              required
            />
          </div>

          {/* 描述 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">描述</label>
            <textarea
              name="description"
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              rows={2}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
              placeholder="任务描述（可选）"
            />
          </div>

          {/* 站点 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">
              站点 <span className="text-red-500">*</span>
            </label>
            <select
              name="site"
              value={formData.site}
              onChange={(e) => setFormData({ ...formData, site: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
              required
            >
              <option value="xueqiu">雪球</option>
              <option value="wechat_mp" disabled>
                微信公众号（即将支持）
              </option>
            </select>
          </div>

          {/* 入口 URL */}
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">
              入口 URL <span className="text-red-500">*</span>
            </label>
            <textarea
              name="entryUrls"
              value={formData.entryUrls}
              onChange={(e) => setFormData({ ...formData, entryUrls: e.target.value })}
              rows={4}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
              placeholder="https://xueqiu.com/S/SH688256"
              required
            />
            <p className="mt-1 text-xs text-gray-500">每行一个 URL</p>
          </div>

          {/* Buttons */}
          <div className="flex space-x-4">
            <button
              type="submit"
              disabled={isSubmitting}
              className="rounded-md bg-blue-600 px-6 py-2 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {isSubmitting ? '创建中...' : '创建任务'}
            </button>
            <button
              type="button"
              onClick={() => navigate('/crawler/tasks')}
              className="rounded-md bg-gray-300 px-6 py-2 text-gray-700 transition-colors hover:bg-gray-400"
            >
              取消
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
