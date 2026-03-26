import { useState } from 'react';

interface Article {
  id: number;
  source: string;
  stock?: string;
  url: string;
  title: string;
  author?: string;
  contentText: string;
  publishTime?: string;
  createdAt: string;
}

interface ArticlePreviewListProps {
  articles: Article[];
}

export default function ArticlePreviewList({ articles }: ArticlePreviewListProps) {
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const toggleExpand = (id: number) => {
    setExpandedId(expandedId === id ? null : id);
  };

  const formatDate = (dateString?: string) => {
    if (!dateString) return '-';
    return new Date(dateString).toLocaleString('zh-CN');
  };

  return (
    <div className="space-y-4">
      {articles.map((article) => (
        <div
          key={article.id}
          className="card bg-base-200 hover:bg-base-300 cursor-pointer transition-colors"
          onClick={() => toggleExpand(article.id)}
        >
          <div className="card-body">
            {/* Header */}
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <h3 className="card-title text-lg">{article.title}</h3>
                <div className="mt-2 flex flex-wrap gap-2 text-sm text-gray-600">
                  {article.author && <span className="badge badge-ghost">👤 {article.author}</span>}
                  <span className="badge badge-ghost">📰 {article.source}</span>
                  {article.stock && <span className="badge badge-ghost">📈 {article.stock}</span>}
                  <span className="badge badge-ghost">🕐 {formatDate(article.publishTime)}</span>
                </div>
              </div>
              <button className="btn btn-ghost btn-sm">
                {expandedId === article.id ? '▲' : '▼'}
              </button>
            </div>

            {/* Expanded Content */}
            {expandedId === article.id && (
              <div className="border-base-300 mt-4 border-t pt-4">
                <div className="prose max-w-none">
                  <p className="text-sm whitespace-pre-wrap">
                    {article.contentText.substring(0, 500)}
                    {article.contentText.length > 500 && '...'}
                  </p>
                </div>
                <div className="mt-4 flex gap-2">
                  <a
                    href={article.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="btn btn-sm btn-primary"
                    onClick={(e) => e.stopPropagation()}
                  >
                    查看原文 🔗
                  </a>
                </div>
              </div>
            )}
          </div>
        </div>
      ))}

      {articles.length === 0 && <div className="py-8 text-center text-gray-500">暂无文章</div>}
    </div>
  );
}
