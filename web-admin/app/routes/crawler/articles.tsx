import { useState, useEffect } from 'react';
import { useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { useToast } from '~/contexts/ToastContext';
import { getArticles } from '~/shared/services/crawler';

// Loader - 获取文章列表
export async function loader({ request }: LoaderFunctionArgs) {
  try {
    const url = new URL(request.url);
    const source = url.searchParams.get('source') || '';
    const stock = url.searchParams.get('stock') || '';

    const articles = await getArticles(request, source, stock);
    return { articles, error: null };
  } catch (error) {
    console.error('Failed to load articles:', error);
    return {
      articles: [],
      error: error instanceof Error ? error.message : 'Failed to load articles',
    };
  }
}

export default function CrawlerArticles() {
  const { showErrorToast } = useToast();
  const { articles, error } = useLoaderData<typeof loader>();
  const [filters, setFilters] = useState({ source: '', stock: '' });
  const [expandedRows, setExpandedRows] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (error) {
      showErrorToast(error);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [error]);

  const handleFilter = () => {
    const params = new URLSearchParams();
    if (filters.source) params.append('source', filters.source);
    if (filters.stock) params.append('stock', filters.stock);
    window.location.href = `/crawler/articles?${params}`;
  };

  const toggleRow = (id: number) => {
    const newExpanded = new Set(expandedRows);
    if (newExpanded.has(id)) {
      newExpanded.delete(id);
    } else {
      newExpanded.add(id);
    }
    setExpandedRows(newExpanded);
  };

  const getSiteColor = (source: string) => {
    const colorMap: Record<string, string> = {
      xueqiu: 'bg-blue-100 text-blue-800',
      wechat_mp: 'bg-green-100 text-green-800',
    };
    return colorMap[source] || 'bg-gray-100 text-gray-800';
  };

  return (
    <div className="p-6">
      <div className="rounded-lg bg-white shadow-md">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4">
          <h1 className="text-2xl font-semibold text-gray-900">爬取文章</h1>
        </div>

        {/* Filters */}
        <div className="border-b border-gray-200 bg-gray-50 px-6 py-4">
          <div className="flex space-x-4">
            <select
              value={filters.source}
              onChange={(e) => setFilters({ ...filters, source: e.target.value })}
              className="rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500"
            >
              <option value="">全部来源</option>
              <option value="xueqiu">雪球</option>
              <option value="wechat_mp">微信公众号</option>
            </select>

            <input
              type="text"
              value={filters.stock}
              onChange={(e) => setFilters({ ...filters, stock: e.target.value })}
              placeholder="股票代码"
              className="rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500"
            />

            <button
              onClick={handleFilter}
              className="rounded-md bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
            >
              筛选
            </button>

            {(filters.source || filters.stock) && (
              <button
                onClick={() => {
                  setFilters({ source: '', stock: '' });
                  window.location.href = '/crawler/articles';
                }}
                className="rounded-md bg-gray-300 px-4 py-2 text-gray-700 transition-colors hover:bg-gray-400"
              >
                清空
              </button>
            )}
          </div>
        </div>

        {/* Article List */}
        <div className="p-6">
          {articles.length === 0 ? (
            <div className="py-12 text-center text-gray-500">暂无文章数据</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="w-8 px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"></th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      标题
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      来源
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      股票
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      作者
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      抓取时间
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 bg-white">
                  {articles.map((article: any) => (
                    <>
                      <tr key={article.id} className="hover:bg-gray-50">
                        <td className="px-6 py-4">
                          <button
                            onClick={() => toggleRow(article.id)}
                            className="text-gray-400 hover:text-gray-600"
                          >
                            {expandedRows.has(article.id) ? '▼' : '▶'}
                          </button>
                        </td>
                        <td className="px-6 py-4">
                          <a
                            href={article.url}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-blue-600 hover:text-blue-900 hover:underline"
                          >
                            {article.title}
                          </a>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <span
                            className={`rounded-full px-2 py-1 text-xs font-medium ${getSiteColor(article.source)}`}
                          >
                            {article.source}
                          </span>
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">
                          {article.stock || '-'}
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                          {article.author || '-'}
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                          {new Date(article.createdAt).toLocaleString('zh-CN')}
                        </td>
                      </tr>
                      {expandedRows.has(article.id) && (
                        <tr>
                          <td colSpan={6} className="bg-gray-50 px-6 py-4">
                            <div className="prose max-w-none">
                              <p className="text-sm whitespace-pre-wrap text-gray-700">
                                {article.contentText}
                              </p>
                            </div>
                          </td>
                        </tr>
                      )}
                    </>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
