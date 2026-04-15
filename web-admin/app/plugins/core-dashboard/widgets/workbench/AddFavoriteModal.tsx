/**
 * AddFavoriteModal — Modal for selecting menu items to add as favorites.
 *
 * Fetches the user's menu tree from /api/menu/user, filters to leaf items only,
 * and allows toggling favorites on/off. Changes are persisted via the engagement API.
 */

import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useI18n } from '~/contexts/I18nContext';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { addFavorite, removeFavorite, listFavorites } from '~/shared/services/engagementService';
import type { UserEngagement } from '~/shared/services/engagementService';
import type { MenuItem } from '~/shared/services/menu';

interface AddFavoriteModalProps {
  open: boolean;
  onClose: () => void;
  onChanged: () => void;
}

interface LeafMenuItem {
  id: number;
  pid?: string;
  code?: string;
  name: string;
  path: string;
  icon?: string;
  i18nKey?: string | null;
  parentName: string;
  parentIcon?: string;
}

const I18N_KEYS = {
  title: 'workbench.shortcuts.modal.title',
  search: 'workbench.shortcuts.modal.search',
  noResults: 'workbench.shortcuts.modal.noResults',
  loading: 'workbench.shortcuts.modal.loading',
  close: 'workbench.shortcuts.modal.close',
} as const;

const FALLBACK_LABELS: Record<string, string> = {
  [I18N_KEYS.title]: 'Add Shortcuts',
  [I18N_KEYS.search]: 'Search menu items...',
  [I18N_KEYS.noResults]: 'No matching menu items',
  [I18N_KEYS.loading]: 'Loading...',
  [I18N_KEYS.close]: 'Close',
};

/**
 * Extract leaf menu items (type=1, with a path) from a nested menu tree,
 * grouped by their parent folder name.
 */
function extractLeafItems(menus: MenuItem[], parentName = '', parentIcon?: string): LeafMenuItem[] {
  const leaves: LeafMenuItem[] = [];
  for (const item of menus) {
    if (item.type === 1 && item.path && item.visible !== false) {
      leaves.push({
        id: item.id,
        pid: item.pid,
        code: item.code,
        name: item.name,
        path: item.path,
        icon: item.icon,
        i18nKey: item.i18nKey,
        parentName: parentName || '',
        parentIcon,
      });
    }
    if (item.children && item.children.length > 0) {
      leaves.push(
        ...extractLeafItems(item.children, item.name, item.icon),
      );
    }
  }
  return leaves;
}

/**
 * Build a set of targetIds that are already favorited.
 * The targetId for menu favorites is the menu code or path.
 */
function buildFavoriteLookup(favorites: UserEngagement[]): Map<string, UserEngagement> {
  const map = new Map<string, UserEngagement>();
  for (const fav of favorites) {
    map.set(fav.targetId, fav);
  }
  return map;
}

export function AddFavoriteModal({ open, onClose, onChanged }: AddFavoriteModalProps) {
  const { t } = useI18n();
  const [menuItems, setMenuItems] = useState<LeafMenuItem[]>([]);
  const [favorites, setFavorites] = useState<Map<string, UserEngagement>>(new Map());
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [pendingOps, setPendingOps] = useState<Set<string>>(new Set());
  const backdropRef = useRef<HTMLDivElement>(null);
  const hasChanged = useRef(false);

  const label = useCallback(
    (key: string) => {
      const translated = t(key);
      // If t() returns the key itself, use fallback
      return translated === key ? (FALLBACK_LABELS[key] || key) : translated;
    },
    [t],
  );

  // Fetch menu tree + existing favorites on open
  useEffect(() => {
    if (!open) return;

    let cancelled = false;

    async function loadData() {
      setLoading(true);
      try {
        const [menuResult, favs] = await Promise.all([
          get<MenuItem[]>('/api/menu/user'),
          listFavorites('menu'),
        ]);

        if (cancelled) return;

        const menus = ResultHelper.isSuccess(menuResult) && menuResult.data
          ? menuResult.data
          : [];

        setMenuItems(extractLeafItems(menus));
        setFavorites(buildFavoriteLookup(favs));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    loadData();
    return () => { cancelled = true; };
  }, [open]);

  // Reset search on open
  useEffect(() => {
    if (open) {
      setSearch('');
      hasChanged.current = false;
    }
  }, [open]);

  const handleClose = useCallback(() => {
    if (hasChanged.current) {
      onChanged();
    }
    onClose();
  }, [onClose, onChanged]);

  // Close on backdrop click
  const handleBackdropClick = useCallback(
    (e: React.MouseEvent) => {
      if (e.target === backdropRef.current) {
        handleClose();
      }
    },
    [handleClose],
  );

  // Close on Escape
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, handleClose]);

  const toggleFavorite = useCallback(
    async (item: LeafMenuItem) => {
      const targetId = item.code || item.path;
      if (pendingOps.has(targetId)) return;

      setPendingOps((prev) => new Set(prev).add(targetId));

      try {
        const existing = favorites.get(targetId);

        if (existing) {
          // Remove
          await removeFavorite(existing.id);
          setFavorites((prev) => {
            const next = new Map(prev);
            next.delete(targetId);
            return next;
          });
        } else {
          // Add
          const result = await addFavorite({
            targetType: 'menu',
            targetId,
            targetLabel: item.name,
            targetContext: {
              icon: item.icon,
              path: item.path,
              color: 'bg-blue-50',
            },
          });
          if (result) {
            setFavorites((prev) => {
              const next = new Map(prev);
              next.set(targetId, result);
              return next;
            });
          }
        }
        hasChanged.current = true;
      } finally {
        setPendingOps((prev) => {
          const next = new Set(prev);
          next.delete(targetId);
          return next;
        });
      }
    },
    [favorites, pendingOps],
  );

  // Filter items by search term
  const filteredItems = useMemo(() => {
    if (!search.trim()) return menuItems;
    const term = search.toLowerCase();
    return menuItems.filter(
      (item) =>
        item.name.toLowerCase().includes(term) ||
        item.parentName.toLowerCase().includes(term) ||
        (item.code && item.code.toLowerCase().includes(term)),
    );
  }, [menuItems, search]);

  // Group by parent name
  const grouped = useMemo(() => {
    const groups: Record<string, LeafMenuItem[]> = {};
    for (const item of filteredItems) {
      const key = item.parentName || 'Other';
      if (!groups[key]) groups[key] = [];
      groups[key].push(item);
    }
    return groups;
  }, [filteredItems]);

  if (!open) return null;

  const content = (
    <div
      ref={backdropRef}
      onClick={handleBackdropClick}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      data-testid="add-favorite-modal-backdrop"
    >
      <div
        className="relative mx-4 flex max-h-[70vh] w-full max-w-md flex-col overflow-hidden rounded-2xl bg-white shadow-2xl"
        data-testid="add-favorite-modal"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4">
          <h2 className="text-base font-semibold text-gray-800">
            {label(I18N_KEYS.title)}
          </h2>
          <button
            onClick={handleClose}
            className="flex h-7 w-7 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
            aria-label={label(I18N_KEYS.close)}
            data-testid="add-favorite-modal-close"
          >
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M1 1l12 12M13 1L1 13" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </div>

        {/* Search */}
        <div className="border-b border-gray-100 px-5 py-3">
          <div className="relative">
            <svg
              className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth="2"
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={label(I18N_KEYS.search)}
              className="w-full rounded-lg border border-gray-200 bg-gray-50 py-2 pl-9 pr-3 text-sm text-gray-700 placeholder-gray-400 outline-none transition-colors focus:border-blue-300 focus:bg-white focus:ring-1 focus:ring-blue-200"
              data-testid="add-favorite-modal-search"
              autoFocus
            />
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-5 py-3">
          {loading ? (
            <div className="flex items-center justify-center py-12 text-sm text-gray-400">
              <svg className="mr-2 h-4 w-4 animate-spin" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              {label(I18N_KEYS.loading)}
            </div>
          ) : Object.keys(grouped).length === 0 ? (
            <div className="py-12 text-center text-sm text-gray-400" data-testid="add-favorite-modal-empty">
              {label(I18N_KEYS.noResults)}
            </div>
          ) : (
            Object.entries(grouped).map(([groupName, items]) => (
              <div key={groupName} className="mb-4 last:mb-0">
                <div className="mb-1.5 text-xs font-medium uppercase tracking-wide text-gray-400">
                  {groupName}
                </div>
                <div className="space-y-0.5">
                  {items.map((item) => {
                    const targetId = item.code || item.path;
                    const isFavorited = favorites.has(targetId);
                    const isPending = pendingOps.has(targetId);

                    return (
                      <button
                        key={item.id}
                        onClick={() => toggleFavorite(item)}
                        disabled={isPending}
                        className={`flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-all ${
                          isFavorited
                            ? 'bg-blue-50 text-blue-700'
                            : 'text-gray-700 hover:bg-gray-50'
                        } ${isPending ? 'opacity-50' : ''}`}
                        data-testid={`favorite-item-${targetId}`}
                      >
                        {/* Checkbox */}
                        <div
                          className={`flex h-4.5 w-4.5 flex-shrink-0 items-center justify-center rounded border transition-colors ${
                            isFavorited
                              ? 'border-blue-500 bg-blue-500'
                              : 'border-gray-300 bg-white'
                          }`}
                          style={{ width: '18px', height: '18px' }}
                        >
                          {isFavorited && (
                            <svg width="10" height="8" viewBox="0 0 10 8" fill="none">
                              <path
                                d="M1 4l2.5 2.5L9 1"
                                stroke="white"
                                strokeWidth="1.5"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                              />
                            </svg>
                          )}
                        </div>

                        {/* Icon */}
                        {item.icon && (
                          <span className="flex-shrink-0 text-base">{item.icon}</span>
                        )}

                        {/* Label */}
                        <span className="flex-1 truncate text-sm font-medium">
                          {t(item.i18nKey || (item.code ? `menu.${item.code}` : '')) || item.name}
                        </span>

                        {/* Loading spinner for pending ops */}
                        {isPending && (
                          <svg className="h-3.5 w-3.5 flex-shrink-0 animate-spin text-gray-400" viewBox="0 0 24 24">
                            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                          </svg>
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );

  return createPortal(content, document.body);
}
