import React, { useEffect, useMemo, useState } from 'react';
import { useCrawlerStore } from '~/crawler/store';

interface Filters {
  source?: string;
  stock?: string;
}

const badgeClass = (variant: 'blue' | 'green') =>
  variant === 'green' ? 'bg-emerald-100 text-emerald-700' : 'bg-blue-100 text-blue-700';

export default function ArticleListPage() {
  const { articles, loading, fetchArticles } = useCrawlerStore();
  const [filters, setFilters] = useState<Filters>({});

  useEffect(() => {
    fetchArticles(filters);
  }, [fetchArticles, filters]);

  const rows = useMemo(() => articles || [], [articles]);

  const handleFilterChange = (next: Partial<Filters>) => {
    setFilters((prev) => ({ ...prev, ...next }));
  };

  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-center gap-4">
        <select
          value={filters.source ?? ''}
          onChange={(e) => handleFilterChange({ source: e.target.value || undefined })}
          className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">选择来源</option>
          <option value="xueqiu">雪球</option>
          <option value="wechat_mp">微信公众号</option>
        </select>

        <div className="flex items-center">
          <input
            type="text"
            placeholder="股票代码"
            value={filters.stock ?? ''}
            onChange={(e) => handleFilterChange({ stock: e.target.value || undefined })}
            className="rounded-l-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
          />
          <button
            type="button"
            onClick={() => fetchArticles(filters)}
            className="rounded-r-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-0 focus:outline-none"
          >
            搜索
          </button>
        </div>
      </div>

      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50 text-xs text-gray-500 uppercase">
            <tr>
              <th className="px-4 py-3 text-left">标题</th>
              <th className="px-4 py-3 text-left">来源</th>
              <th className="px-4 py-3 text-left">股票</th>
              <th className="px-4 py-3 text-left">作者</th>
              <th className="px-4 py-3 text-left">发布时间</th>
              <th className="px-4 py-3 text-left">抓取时间</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-gray-500">
                  加载中...
                </td>
              </tr>
            )}
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-gray-500">
                  暂无数据
                </td>
              </tr>
            )}
            {!loading &&
              rows.map((article) => (
                <tr key={article.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-blue-600">
                    <a
                      href={article.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="hover:underline"
                    >
                      {article.title}
                    </a>
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${badgeClass(
                        article.source === 'wechat_mp' ? 'green' : 'blue',
                      )}`}
                    >
                      {article.source}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{article.stock || '-'}</td>
                  <td className="px-4 py-3 text-gray-700">{article.author || '-'}</td>
                  <td className="px-4 py-3 text-gray-700">
                    {article.publishTime
                      ? new Date(article.publishTime).toLocaleString('zh-CN')
                      : '-'}
                  </td>
                  <td className="px-4 py-3 text-gray-700">
                    {article.createdAt ? new Date(article.createdAt).toLocaleString('zh-CN') : '-'}
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
