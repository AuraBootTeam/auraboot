/**
 * 引用面板组件
 * 显示 AI 回答中引用的文档来源信息
 */

import { useState } from 'react';
import type { Citation } from '~/chat/types';

interface CitationPanelProps {
  citations: Citation[];
  onCitationClick?: (citation: Citation) => void;
}

export function CitationPanel({ citations, onCitationClick }: CitationPanelProps) {
  const [expandedId, setExpandedId] = useState<number | null>(null);

  if (citations.length === 0) {
    return null;
  }

  const toggleExpand = (id: number) => {
    setExpandedId(expandedId === id ? null : id);
  };

  return (
    <div className="mt-3 border-t border-gray-200 pt-3">
      <div className="mb-2 text-xs font-semibold text-gray-600">
        📚 引用来源 ({citations.length})
      </div>
      <div className="space-y-2">
        {citations.map((citation) => (
          <CitationCard
            key={citation.id}
            citation={citation}
            isExpanded={expandedId === citation.id}
            onToggle={() => toggleExpand(citation.id)}
            onClick={() => onCitationClick?.(citation)}
          />
        ))}
      </div>
    </div>
  );
}

interface CitationCardProps {
  citation: Citation;
  isExpanded: boolean;
  onToggle: () => void;
  onClick: () => void;
}

function CitationCard({ citation, isExpanded, onToggle, onClick }: CitationCardProps) {
  const getDocumentTypeLabel = (type: string) => {
    const labels: Record<string, string> = {
      report: '研报',
      disclosure: '公告',
      news: '新闻',
      user_note: '笔记',
      temporary: '临时文档',
    };
    return labels[type] || type;
  };

  const getDocumentTypeColor = (type: string) => {
    const colors: Record<string, string> = {
      report: 'bg-blue-100 text-blue-800',
      disclosure: 'bg-green-100 text-green-800',
      news: 'bg-yellow-100 text-yellow-800',
      user_note: 'bg-purple-100 text-purple-800',
      temporary: 'bg-gray-100 text-gray-800',
    };
    return colors[type] || 'bg-gray-100 text-gray-800';
  };

  return (
    <div className="rounded-lg border border-gray-200 bg-gray-50 p-3 text-sm transition-colors hover:border-gray-300">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex items-center space-x-2">
            <span className="font-medium text-gray-900">[^{citation.id}]</span>
            <span
              className={`rounded px-2 py-0.5 text-xs font-medium ${getDocumentTypeColor(citation.document_type)}`}
            >
              {getDocumentTypeLabel(citation.document_type)}
            </span>
            {citation.similarity !== undefined && (
              <span className="text-xs text-gray-500">
                相关度: {(citation.similarity * 100).toFixed(1)}%
              </span>
            )}
          </div>
          <div className="truncate font-medium text-gray-700">{citation.source}</div>
          {citation.page !== undefined && (
            <div className="mt-1 text-xs text-gray-500">页码: {citation.page}</div>
          )}
        </div>

        {/* Actions */}
        <div className="ml-2 flex items-center space-x-1">
          <button
            onClick={onClick}
            className="rounded p-1 transition-colors hover:bg-gray-200"
            title="查看原文"
          >
            <svg
              className="h-4 w-4 text-gray-600"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"
              />
            </svg>
          </button>
          <button
            onClick={onToggle}
            className="rounded p-1 transition-colors hover:bg-gray-200"
            title={isExpanded ? '收起' : '展开'}
          >
            <svg
              className={`h-4 w-4 text-gray-600 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 9l-7 7-7-7"
              />
            </svg>
          </button>
        </div>
      </div>

      {/* Expanded Content */}
      {isExpanded && citation.text && (
        <div className="mt-2 border-t border-gray-200 pt-2">
          <div className="mb-1 text-xs text-gray-600">引用内容：</div>
          <div className="max-h-32 overflow-y-auto rounded border border-gray-200 bg-white p-2 text-xs text-gray-700">
            {citation.text}
          </div>
        </div>
      )}
    </div>
  );
}
