import React, { useState } from 'react';
import { useNavigate } from 'react-router';
import { useCrawlerStore } from '~/crawler/store';

interface FormState {
  name: string;
  description: string;
  site: string;
  entryUrls: string;
}

const initialState: FormState = {
  name: '',
  description: '',
  site: '',
  entryUrls: '',
};

export default function TaskCreatePage() {
  const navigate = useNavigate();
  const { createTask } = useCrawlerStore();
  const [formState, setFormState] = useState<FormState>(initialState);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const handleChange = (field: keyof FormState, value: string) => {
    setFormState((prev) => ({ ...prev, [field]: value }));
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setSuccess(null);

    if (!formState.name || !formState.site || !formState.entryUrls.trim()) {
      setError('请完整填写必填信息');
      return;
    }

    setSubmitting(true);
    try {
      const config = {
        entry_urls: formState.entryUrls
          .split('\n')
          .map((url) => url.trim())
          .filter(Boolean),
        depth: 1,
        parser_profile: 'default',
      };

      await createTask({
        name: formState.name,
        description: formState.description,
        site: formState.site,
        config,
      });

      setSuccess('任务创建成功');
      setFormState(initialState);
      window.setTimeout(() => navigate('/crawler/tasks'), 800);
    } catch (err) {
      console.error(err);
      setError('创建失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="p-6">
      <div className="mx-auto max-w-3xl rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-6 text-xl font-semibold text-gray-900">创建爬虫任务</h2>

        {error && (
          <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            {error}
          </div>
        )}
        {success && (
          <div className="mb-4 rounded-md border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700">
            {success}
          </div>
        )}

        <form className="space-y-5" onSubmit={handleSubmit}>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              任务名称 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={formState.name}
              onChange={(e) => handleChange('name', e.target.value)}
              placeholder="例如：雪球寒武纪爬取"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
            <textarea
              value={formState.description}
              onChange={(e) => handleChange('description', e.target.value)}
              rows={2}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
              placeholder="任务描述（可选）"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              站点 <span className="text-red-500">*</span>
            </label>
            <select
              value={formState.site}
              onChange={(e) => handleChange('site', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
            >
              <option value="" disabled>
                选择爬取站点
              </option>
              <option value="xueqiu">雪球</option>
              <option value="wechat_mp" disabled>
                微信公众号（即将支持）
              </option>
            </select>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              入口 URL <span className="text-red-500">*</span>
            </label>
            <textarea
              value={formState.entryUrls}
              onChange={(e) => handleChange('entryUrls', e.target.value)}
              rows={4}
              placeholder="每行一个 URL，例如 https://xueqiu.com/S/SH688256"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
            />
            <p className="mt-1 text-xs text-gray-500">每行一个 URL</p>
          </div>

          <div className="flex items-center gap-3">
            <button
              type="submit"
              disabled={submitting}
              className={`inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none ${
                submitting ? 'opacity-70' : ''
              }`}
            >
              {submitting ? '创建中...' : '创建任务'}
            </button>
            <button
              type="button"
              className="rounded-md border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
              onClick={() => navigate('/crawler/tasks')}
            >
              取消
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
