/**
 * PageCard Component
 *
 * Display individual page item in the page list.
 *
 * @since 3.2.0
 */

import React from 'react';
import type { PageMeta } from '../../../services/page-manager';
import { PAGE_MODE_INFO, PAGE_STATUS_INFO } from '../../../services/page-manager';

/**
 * PageCard props
 */
export interface PageCardProps {
  /** Page metadata */
  page: PageMeta;
  /** Whether the card is selected */
  isSelected?: boolean;
  /** Click handler */
  onClick?: (page: PageMeta) => void;
  /** Double click handler (open editor) */
  onDoubleClick?: (page: PageMeta) => void;
  /** Edit action */
  onEdit?: (page: PageMeta) => void;
  /** Duplicate action */
  onDuplicate?: (page: PageMeta) => void;
  /** Delete action */
  onDelete?: (page: PageMeta) => void;
  /** Archive action */
  onArchive?: (page: PageMeta) => void;
  /** Publish action */
  onPublish?: (page: PageMeta) => void;
  /** Whether batch mode is enabled */
  batchMode?: boolean;
  /** Whether the card is checked in batch mode */
  isChecked?: boolean;
  /** Checkbox change handler for batch mode */
  onCheckChange?: (page: PageMeta, checked: boolean) => void;
}

/**
 * Format date string
 */
function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

  if (diffDays === 0) {
    return '今天';
  } else if (diffDays === 1) {
    return '昨天';
  } else if (diffDays < 7) {
    return `${diffDays}天前`;
  } else {
    return date.toLocaleDateString('zh-CN', {
      month: 'short',
      day: 'numeric',
    });
  }
}

/**
 * PageCard component
 */
export const PageCard: React.FC<PageCardProps> = ({
  page,
  isSelected = false,
  onClick,
  onDoubleClick,
  onEdit,
  onDuplicate,
  onDelete,
  onArchive,
  onPublish,
  batchMode = false,
  isChecked = false,
  onCheckChange,
}) => {
  const [showMenu, setShowMenu] = React.useState(false);
  const menuRef = React.useRef<HTMLDivElement>(null);

  const modeInfo = PAGE_MODE_INFO[page.mode];
  const statusInfo = PAGE_STATUS_INFO[page.status];

  // Close menu when clicking outside
  React.useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setShowMenu(false);
      }
    };
    if (showMenu) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showMenu]);

  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (batchMode) {
      onCheckChange?.(page, !isChecked);
    } else {
      onClick?.(page);
    }
  };

  const handleCheckboxChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    e.stopPropagation();
    onCheckChange?.(page, e.target.checked);
  };

  const handleDoubleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onDoubleClick?.(page);
  };

  const handleMenuClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    setShowMenu(!showMenu);
  };

  const handleAction = (action: () => void) => {
    return (e: React.MouseEvent) => {
      e.stopPropagation();
      setShowMenu(false);
      action();
    };
  };

  return (
    <div
      className={`group relative cursor-pointer overflow-hidden rounded-lg border bg-white transition-all duration-200 hover:border-blue-300 hover:shadow-lg ${isSelected ? 'border-blue-500 ring-2 ring-blue-500' : 'border-gray-200'} ${batchMode && isChecked ? 'border-blue-500 ring-2 ring-blue-500' : ''} `}
      onClick={handleClick}
      onDoubleClick={batchMode ? undefined : handleDoubleClick}
    >
      {/* Thumbnail area */}
      <div className="relative flex h-36 items-center justify-center overflow-hidden bg-gray-50">
        {page.thumbnail ? (
          <img src={page.thumbnail} alt={page.title} className="h-full w-full object-cover" />
        ) : (
          <div className="flex flex-col items-center justify-center text-gray-400">
            <svg className="mb-2 h-12 w-12" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1}
                d={modeInfo.icon}
              />
            </svg>
            <span className="text-xs">{modeInfo.label}</span>
          </div>
        )}

        {/* Batch mode checkbox */}
        {batchMode && (
          <div className="absolute top-2 left-2 z-10">
            <input
              type="checkbox"
              checked={isChecked}
              onChange={handleCheckboxChange}
              onClick={(e) => e.stopPropagation()}
              className="h-5 w-5 cursor-pointer rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
          </div>
        )}

        {/* Status badge */}
        <div
          className={`absolute top-2 ${batchMode ? 'left-9' : 'left-2'} rounded px-2 py-0.5 text-xs font-medium ${statusInfo.color} ${statusInfo.bgColor} transition-all`}
        >
          {statusInfo.label}
        </div>

        {/* Menu button */}
        <div className="absolute top-2 right-2 opacity-0 transition-opacity group-hover:opacity-100">
          <button
            className="rounded-full bg-white/90 p-1.5 shadow-sm hover:bg-white"
            onClick={handleMenuClick}
          >
            <svg className="h-4 w-4 text-gray-600" fill="currentColor" viewBox="0 0 20 20">
              <path d="M10 6a2 2 0 110-4 2 2 0 010 4zM10 12a2 2 0 110-4 2 2 0 010 4zM10 18a2 2 0 110-4 2 2 0 010 4z" />
            </svg>
          </button>

          {/* Dropdown menu */}
          {showMenu && (
            <div
              ref={menuRef}
              className="absolute right-0 z-50 mt-1 w-36 rounded-lg border border-gray-200 bg-white py-1 shadow-lg"
            >
              <button
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-gray-50"
                onClick={handleAction(() => onEdit?.(page))}
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                  />
                </svg>
                编辑
              </button>
              <button
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-gray-50"
                onClick={handleAction(() => onDuplicate?.(page))}
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                  />
                </svg>
                复制
              </button>
              {page.status !== 'published' && (
                <button
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-green-600 hover:bg-gray-50"
                  onClick={handleAction(() => onPublish?.(page))}
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                    />
                  </svg>
                  发布
                </button>
              )}
              {page.status !== 'archived' && (
                <button
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-yellow-600 hover:bg-gray-50"
                  onClick={handleAction(() => onArchive?.(page))}
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8v10a2 2 0 002 2h10a2 2 0 002-2V8m-9 4h4"
                    />
                  </svg>
                  归档
                </button>
              )}
              <div className="my-1 border-t border-gray-100" />
              <button
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-red-600 hover:bg-red-50"
                onClick={handleAction(() => onDelete?.(page))}
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                  />
                </svg>
                删除
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Content area */}
      <div className="p-3">
        <h3 className="truncate font-medium text-gray-900" title={page.title}>
          {page.title}
        </h3>
        {page.description && (
          <p className="mt-1 line-clamp-2 text-sm text-gray-500" title={page.description}>
            {page.description}
          </p>
        )}
        <div className="mt-3 flex items-center justify-between text-xs text-gray-400">
          <span className="flex items-center gap-1">
            <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
            {formatDate(page.updatedAt)}
          </span>
          <span className="flex items-center gap-1">
            <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"
              />
            </svg>
            {page.componentCount || 0}
          </span>
          <span className="text-gray-300">v{page.version}</span>
        </div>
      </div>
    </div>
  );
};

export default PageCard;
