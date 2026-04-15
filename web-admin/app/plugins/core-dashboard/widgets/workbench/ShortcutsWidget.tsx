/**
 * ShortcutsWidget — Workbench widget showing quick action shortcuts.
 *
 * Data source: user favorites from /api/user-engagement API.
 * Falls back to DEFAULT_SHORTCUTS when no favorites are configured.
 */

import React, { useEffect, useState, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { listFavorites, removeFavorite, reorderFavorites } from '~/shared/services/engagementService';
import type { UserEngagement } from '~/shared/services/engagementService';
import { AddFavoriteModal } from './AddFavoriteModal';

export interface ShortcutItem {
  label: string;
  icon: string;
  path: string;
  color?: string;
  engagementId?: number;
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
  startProcess: 'workbench.shortcuts.startProcess',
  newContract: 'workbench.shortcuts.newContract',
  reports: 'workbench.shortcuts.reports',
} as const;

function getDefaultShortcuts(t: (key: string) => string): ShortcutItem[] {
  return [
    { label: t(I18N_KEYS.newLead), icon: '\uD83C\uDFAF', path: '/crm_lead?action=create', color: 'bg-blue-50' },
    { label: t(I18N_KEYS.newAccount), icon: '\uD83C\uDFE2', path: '/crm_account?action=create', color: 'bg-green-50' },
    { label: t(I18N_KEYS.newOpportunity), icon: '\uD83D\uDCB0', path: '/crm_opportunity?action=create', color: 'bg-amber-50' },
    { label: t(I18N_KEYS.startProcess), icon: '\uD83D\uDCCB', path: '/bpm/process-management', color: 'bg-violet-50' },
    { label: t(I18N_KEYS.newContract), icon: '\uD83D\uDCC4', path: '/cc_contract?action=create', color: 'bg-orange-50' },
    { label: t(I18N_KEYS.reports), icon: '\uD83D\uDCCA', path: '/reports/overview', color: 'bg-indigo-50' },
  ];
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

export function ShortcutsWidget({
  title,
  shortcuts: overrideShortcuts,
  className = '',
}: ShortcutsWidgetProps) {
  const { t } = useI18n();
  const [items, setItems] = useState<ShortcutItem[]>([]);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [isFromFavorites, setIsFromFavorites] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dropIndex, setDropIndex] = useState<number | null>(null);

  const displayTitle = title || t(I18N_KEYS.title);

  const loadFavorites = useCallback(async () => {
    if (overrideShortcuts) {
      setItems(overrideShortcuts);
      setLoading(false);
      return;
    }

    setLoading(true);
    const favorites = await listFavorites('menu');
    if (favorites.length > 0) {
      setItems(favorites.map(mapEngagementToShortcut));
      setIsFromFavorites(true);
    } else {
      setItems(getDefaultShortcuts(t));
      setIsFromFavorites(false);
    }
    setLoading(false);
  }, [overrideShortcuts, t]);

  useEffect(() => {
    loadFavorites();
  }, [loadFavorites]);

  const handleRemove = useCallback(
    async (engagementId: number, e: React.MouseEvent) => {
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

  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        <div className="mb-3 flex items-center justify-between px-1">
          <span className="text-sm font-medium text-gray-700">{displayTitle}</span>
        </div>
        <div
          className="grid flex-1 gap-2"
          style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(80px, 1fr))' }}
        >
          {Array.from({ length: 6 }).map((_, i) => (
            <div
              key={i}
              className="flex animate-pulse flex-col items-center justify-center gap-1.5 rounded-[10px] bg-gray-50 p-3"
            >
              <div className="h-5 w-5 rounded-full bg-gray-200" />
              <div className="h-3 w-10 rounded bg-gray-200" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className={`flex h-full flex-col ${className}`}>
      {/* Header */}
      <div className="mb-3 flex items-center justify-between px-1">
        <span className="text-sm font-medium text-gray-700">{displayTitle}</span>
        {isFromFavorites && (
          <button
            onClick={toggleEditing}
            className="text-xs text-gray-400 transition-colors hover:text-blue-500"
          >
            {editing ? t(I18N_KEYS.done) : t(I18N_KEYS.edit)}
          </button>
        )}
      </div>

      {/* Grid */}
      <div
        className="grid flex-1 gap-2"
        style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(80px, 1fr))' }}
      >
        {items.map((item, index) => (
          <a
            key={item.engagementId || item.path}
            href={item.path}
            draggable={editing}
            onDragStart={editing ? (e) => handleDragStart(index, e) : undefined}
            onDragOver={editing ? (e) => handleDragOver(index, e) : undefined}
            onDragLeave={editing ? handleDragLeave : undefined}
            onDrop={editing ? (e) => handleDrop(index, e) : undefined}
            onDragEnd={editing ? handleDragEnd : undefined}
            className={`relative flex flex-col items-center justify-center gap-1.5 rounded-[10px] p-3 text-center transition-all hover:-translate-y-0.5 hover:shadow-sm ${item.color || 'bg-gray-50'} ${
              editing ? (dragIndex !== null ? 'cursor-grabbing' : 'cursor-grab') : 'cursor-pointer'
            } ${dragIndex === index ? 'opacity-50' : ''} ${
              dropIndex === index && dragIndex !== null && dragIndex !== index
                ? 'ring-2 ring-blue-400 ring-offset-1'
                : ''
            }`}
          >
            <span className="mb-1 text-xl">{item.icon}</span>
            <span className="text-[11px] font-medium text-gray-600">{item.label}</span>

            {/* Remove button in edit mode */}
            {editing && item.engagementId && (
              <button
                onClick={(e) => handleRemove(item.engagementId!, e)}
                className="absolute -right-1 -top-1 flex h-4 w-4 items-center justify-center rounded-full bg-red-400 text-[10px] leading-none text-white shadow-sm transition-colors hover:bg-red-500"
                aria-label={`Remove ${item.label}`}
              >
                &times;
              </button>
            )}
          </a>
        ))}

        {/* Add button in edit mode */}
        {editing && (
          <button
            onClick={() => setModalOpen(true)}
            className="flex flex-col items-center justify-center gap-1.5 rounded-[10px] border-2 border-dashed border-gray-200 p-3 text-center transition-all hover:border-blue-300 hover:bg-blue-50/50"
            data-testid="shortcuts-add-button"
          >
            <span className="mb-1 text-xl text-gray-300">+</span>
            <span className="text-[11px] font-medium text-gray-400">
              {t(I18N_KEYS.addShortcut)}
            </span>
          </button>
        )}
      </div>

      {/* Customize hint when showing defaults — clicking opens the modal */}
      {!isFromFavorites && !editing && (
        <div className="mt-2 text-center">
          <button
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
