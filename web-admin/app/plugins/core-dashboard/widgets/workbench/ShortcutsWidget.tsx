/**
 * ShortcutsWidget — Workbench widget showing quick action shortcuts.
 *
 * Data source: user favorites from /api/user-engagement API.
 * Falls back to visible menu entries when no favorites are configured.
 */

import React, { useEffect, useState, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  listFavorites,
  removeFavorite,
  reorderFavorites,
  type UserEngagement,
} from '~/shared/services/engagementService';
import { AddFavoriteModal } from '~/plugins/core-dashboard/widgets/workbench/AddFavoriteModal';
import { useRootLoaderData } from '~/root';
import { resolveIcon } from '~/utils/icon-resolver';

export interface ShortcutItem {
  label: string;
  icon: string;
  path: string;
  color?: string;
  engagementId?: string;
}

interface ShortcutsWidgetProps {
  title?: string;
  shortcuts?: ShortcutItem[];
  className?: string;
}

const I18N_KEYS = {
  title: 'workbench.shortcuts.title',
  edit: 'workbench.shortcuts.edit',
  done: 'workbench.shortcuts.done',
  customize: 'workbench.shortcuts.customize',
  addShortcut: 'workbench.shortcuts.add',
  newLead: 'workbench.shortcuts.newLead',
  newAccount: 'workbench.shortcuts.newAccount',
  newOpportunity: 'workbench.shortcuts.newOpportunity',
  taskCenter: 'workbench.shortcuts.taskCenter',
  newContract: 'workbench.shortcuts.newContract',
  reports: 'workbench.shortcuts.reports',
} as const;

interface UiMenuItem {
  icon?: string;
  name?: string;
  nameKey?: string;
  path?: string;
  submenu?: UiMenuItem[];
}

function flattenMenus(items: UiMenuItem[]): UiMenuItem[] {
  return items.flatMap((item) => [item, ...(item.submenu ? flattenMenus(item.submenu) : [])]);
}

function pathMatchesMenu(path: string, menuPath: string): boolean {
  return path === menuPath || path.startsWith(`${menuPath}/`) || path.startsWith(`${menuPath}?`);
}

function mapEngagementToShortcut(e: UserEngagement): ShortcutItem {
  return {
    label: e.targetLabel,
    icon: e.targetContext?.icon || '\u2B50',
    path: e.targetContext?.path || `/${e.targetId}`,
    color: e.targetContext?.color || 'bg-gray-50',
    engagementId: e.id,
  };
}

function renderShortcutIcon(item: ShortcutItem): React.ReactNode {
  const icon = item.icon?.trim();
  if (!icon) return resolveIcon('FileText', item.label, 16);
  if (icon.length <= 2) return icon;
  return resolveIcon(icon, item.label, 16);
}

export function ShortcutsWidget({
  title,
  shortcuts: overrideShortcuts,
  className = '',
}: ShortcutsWidgetProps) {
  const { t } = useI18n();
  const rootData = useRootLoaderData();
  const [items, setItems] = useState<ShortcutItem[]>([]);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [isFromFavorites, setIsFromFavorites] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dropIndex, setDropIndex] = useState<number | null>(null);

  const displayTitle = title ? t(title) : t(I18N_KEYS.title);
  const menuShortcuts = React.useMemo(() => {
    const menus = ((rootData?.menus as UiMenuItem[] | undefined) ?? []);
    return flattenMenus(menus)
      .filter((item) => item.path && item.path.startsWith('/p/'))
      .filter((item) => !item.submenu?.length)
      .map((item) => ({
        label: item.nameKey ? t(item.nameKey) : item.name || item.path || '',
        icon: item.icon || '\uD83D\uDCC4',
        path: item.path as string,
        color: 'bg-gray-50',
      }));
  }, [rootData?.menus, t]);

  const visibleMenuPaths = React.useMemo(
    () => new Set(menuShortcuts.map((item) => item.path)),
    [menuShortcuts],
  );

  const filterToVisibleMenus = useCallback(
    (shortcuts: ShortcutItem[]) => {
      if (visibleMenuPaths.size === 0) return shortcuts;
      return shortcuts.filter((item) =>
        [...visibleMenuPaths].some((menuPath) => pathMatchesMenu(item.path, menuPath)),
      );
    },
    [visibleMenuPaths],
  );

  const loadFavorites = useCallback(async () => {
    if (overrideShortcuts) {
      setItems(filterToVisibleMenus(overrideShortcuts));
      setLoading(false);
      return;
    }

    setLoading(true);
    const favorites = await listFavorites('menu');
    const visibleFavorites = filterToVisibleMenus(favorites.map(mapEngagementToShortcut));
    if (visibleFavorites.length > 0) {
      setItems(visibleFavorites);
      setIsFromFavorites(true);
    } else {
      setItems(menuShortcuts);
      setIsFromFavorites(false);
    }
    setLoading(false);
  }, [filterToVisibleMenus, menuShortcuts, overrideShortcuts]);

  useEffect(() => {
    loadFavorites();
  }, [loadFavorites]);

  const handleRemove = useCallback(
    async (engagementId: string, e: React.MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
      await removeFavorite(engagementId);
      setItems((prev) => prev.filter((item) => item.engagementId !== engagementId));
    },
    [],
  );

  const toggleEditing = useCallback(() => {
    setEditing((prev) => !prev);
    setDragIndex(null);
    setDropIndex(null);
  }, []);

  const handleDragStart = useCallback((index: number, e: React.DragEvent) => {
    setDragIndex(index);
    e.dataTransfer.effectAllowed = 'move';
  }, []);

  const handleDragOver = useCallback((index: number, e: React.DragEvent) => {
    e.preventDefault();
    setDropIndex(index);
  }, []);

  const handleDragLeave = useCallback(() => {
    setDropIndex(null);
  }, []);

  const handleDrop = useCallback(
    (index: number, e: React.DragEvent) => {
      e.preventDefault();
      if (dragIndex !== null && dragIndex !== index) {
        const newItems = [...items];
        const [moved] = newItems.splice(dragIndex, 1);
        newItems.splice(index, 0, moved);
        setItems(newItems);
        const ids = newItems.filter((s) => s.engagementId).map((s) => s.engagementId!);
        if (ids.length > 0) reorderFavorites(ids);
      }
      setDragIndex(null);
      setDropIndex(null);
    },
    [dragIndex, items],
  );

  const handleDragEnd = useCallback(() => {
    setDragIndex(null);
    setDropIndex(null);
  }, []);

  const cardClass = `rounded-[10px] bg-white border border-[#e3e8ee] dark:bg-gray-900 dark:border-gray-700 flex h-full flex-col ${className}`;

  if (loading) {
    return (
      <div className={cardClass}>
        <div className="flex items-center justify-between px-4 pt-3 pb-2">
          <h2 className="text-sm font-medium text-gray-700 dark:text-gray-200">{displayTitle}</h2>
        </div>
        <ul className="flex-1 px-2 pb-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <li
              key={i}
              className="flex animate-pulse items-center gap-3 rounded-md px-2 py-2"
            >
              <div className="h-8 w-8 rounded-lg bg-gray-100 dark:bg-gray-800" />
              <div className="h-3 w-24 rounded bg-gray-100 dark:bg-gray-800" />
            </li>
          ))}
        </ul>
      </div>
    );
  }

  return (
    <div className={cardClass}>
      {/* Header */}
      <div className="flex items-center justify-between px-4 pt-3 pb-2">
        <h2 className="text-sm font-medium text-gray-700 dark:text-gray-200">{displayTitle}</h2>
        {isFromFavorites && (
          <button
            type="button"
            onClick={toggleEditing}
            className="text-xs text-gray-400 transition-colors hover:text-blue-500"
          >
            {editing ? t(I18N_KEYS.done) : t(I18N_KEYS.edit)}
          </button>
        )}
      </div>

      {/* Vertical list */}
      <ul data-testid="shortcuts-list" className="flex-1 px-2 pb-2">
        {items.map((item, index) => {
          const isDragging = dragIndex === index;
          const isDropTarget =
            dropIndex === index && dragIndex !== null && dragIndex !== index;
          return (
            <li key={item.engagementId || item.path} className="list-none">
              <a
                data-testid="shortcut-row"
                href={item.path}
                draggable={editing}
                onDragStart={editing ? (e) => handleDragStart(index, e) : undefined}
                onDragOver={editing ? (e) => handleDragOver(index, e) : undefined}
                onDragLeave={editing ? handleDragLeave : undefined}
                onDrop={editing ? (e) => handleDrop(index, e) : undefined}
                onDragEnd={editing ? handleDragEnd : undefined}
                className={`group flex items-center gap-3 rounded-md px-2 py-2 transition-colors hover:bg-[#f6f8fb] dark:hover:bg-gray-800 ${
                  editing ? (dragIndex !== null ? 'cursor-grabbing' : 'cursor-grab') : 'cursor-pointer'
                } ${isDragging ? 'opacity-50' : ''} ${
                  isDropTarget ? 'ring-1 ring-[#635bff]' : ''
                }`}
              >
                <span
                  data-testid="shortcut-icon"
                  className="w-8 h-8 shrink-0 overflow-hidden rounded-lg bg-[#f0f3f7] dark:bg-gray-800 flex items-center justify-center text-[#635bff] text-[14px] font-semibold"
                >
                  {renderShortcutIcon(item)}
                </span>
                <span className="flex-1 truncate text-[13px] font-medium text-gray-700 dark:text-gray-200">
                  {item.label}
                </span>
                {editing && item.engagementId != null ? (
                  <button
                    type="button"
                    onClick={(e) => handleRemove(item.engagementId!, e)}
                    className="flex h-5 w-5 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-red-50 hover:text-red-500"
                    aria-label={t(I18N_KEYS.edit)}
                  >
                    &times;
                  </button>
                ) : (
                  <span className="text-gray-400" aria-hidden="true">
                    {'›'}
                  </span>
                )}
              </a>
            </li>
          );
        })}

        {/* Add row in edit mode */}
        {editing && (
          <li className="list-none">
            <button
              type="button"
              onClick={() => setModalOpen(true)}
              className="flex w-full items-center gap-3 rounded-md px-2 py-2 text-left transition-colors hover:bg-[#f6f8fb] dark:hover:bg-gray-800"
              data-testid="shortcuts-add-button"
            >
              <span className="w-8 h-8 rounded-lg border border-dashed border-gray-300 dark:border-gray-700 flex items-center justify-center text-gray-400 text-[14px]">
                +
              </span>
              <span className="text-[13px] font-medium text-gray-400">
                {t(I18N_KEYS.addShortcut)}
              </span>
            </button>
          </li>
        )}
      </ul>

      {/* Customize hint when showing defaults — clicking opens the modal */}
      {!isFromFavorites && !editing && (
        <div className="px-4 pb-3 text-center">
          <button
            type="button"
            onClick={() => setModalOpen(true)}
            className="text-[10px] text-gray-300 transition-colors hover:text-blue-400"
            data-testid="shortcuts-customize-button"
          >
            {t(I18N_KEYS.customize)}
          </button>
        </div>
      )}

      {/* Add Favorite Modal */}
      <AddFavoriteModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onChanged={loadFavorites}
      />
    </div>
  );
}
