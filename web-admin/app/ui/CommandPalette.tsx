/**
 * Global Command Palette (Cmd+K / Ctrl+K)
 *
 * Provides quick navigation across all accessible menu pages and
 * real-time record search across published models.
 *
 * Features:
 * - Keyboard shortcut: Cmd+K (Mac) / Ctrl+K (Win/Linux)
 * - Menu page search (instant, no API call)
 * - Record search across models (debounced API calls)
 * - Recent searches persisted in localStorage
 * - Keyboard navigation: ↑↓ select, Enter navigate, Esc close
 */

import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { useRootLoaderData } from '~/root-data';
import { useI18n } from '~/contexts/I18nContext';
import { useHydrated } from '~/hooks/useHydrated';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface FlatMenuItem {
  name: string;
  nameKey?: string | null;
  path: string;
  icon?: string;
  parentName?: string;
}

interface RecordHit {
  pid: string;
  displayText: string;
  modelCode: string;
  modelName: string;
  path: string;
}

interface DocHit {
  chunkPid: string;
  docName: string;
  kbName: string;
  content: string;
  similarity: number;
}

interface SearchModelCandidate {
  modelCode: string;
  modelLabel: string;
  enabled: boolean;
}

interface SearchPreference {
  configured: boolean;
  enabledModelCodes?: string[];
}

type SearchResult =
  | { kind: 'menu'; item: FlatMenuItem }
  | { kind: 'record'; hit: RecordHit }
  | { kind: 'doc'; hit: DocHit }
  | { kind: 'recent'; keyword: string };

// ---------------------------------------------------------------------------
// Local storage helpers
// ---------------------------------------------------------------------------

const RECENT_KEY = 'auraboot_recent_searches';
const MAX_RECENT = 8;

function getRecentStorage(): Storage | null {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return null;
  }
  return window.localStorage;
}

export function loadRecent(): string[] {
  const storage = getRecentStorage();
  if (!storage) return [];
  try {
    return JSON.parse(storage.getItem(RECENT_KEY) || '[]');
  } catch {
    storage.removeItem(RECENT_KEY);
    return [];
  }
}

export function saveRecent(items: string[]) {
  const storage = getRecentStorage();
  if (!storage) return;
  storage.setItem(RECENT_KEY, JSON.stringify(items.slice(0, MAX_RECENT)));
}

function addRecent(keyword: string) {
  const list = loadRecent().filter((k) => k !== keyword);
  list.unshift(keyword);
  saveRecent(list);
}

// ---------------------------------------------------------------------------
// Flatten menu tree → clickable items
// ---------------------------------------------------------------------------

function flattenMenus(menus: any[], parentName?: string): FlatMenuItem[] {
  const result: FlatMenuItem[] = [];
  for (const m of menus) {
    const name = m.name || m.nameKey || '';
    if (m.path && m.type !== 0) {
      result.push({
        name,
        nameKey: m.nameKey,
        path: m.path,
        icon: m.icon,
        parentName,
      });
    }
    if (m.submenu?.length) {
      result.push(...flattenMenus(m.submenu, name));
    }
    if (m.children?.length) {
      result.push(...flattenMenus(m.children, name));
    }
  }
  return result;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function CommandPalette() {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const [recordResults, setRecordResults] = useState<RecordHit[]>([]);
  const [docResults, setDocResults] = useState<DocHit[]>([]);
  const [searching, setSearching] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [candidateModels, setCandidateModels] = useState<SearchModelCandidate[]>([]);
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsSaving, setSettingsSaving] = useState(false);
  const [settingsMessage, setSettingsMessage] = useState('');

  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const navigate = useNavigate();
  const rootData = useRootLoaderData();
  const { t: rawT } = useI18n();
  const isHydrated = useHydrated();
  const menus = useMemo(() => rootData?.menus ?? [], [rootData?.menus]);

  // t() with fallback (I18nContext returns key if missing, so we detect and use fallback)
  const t = useCallback(
    (key: string, fallback?: string) => {
      const val = rawT(key);
      return val === key && fallback ? fallback : val;
    },
    [rawT],
  );

  // Flat menu items (memoized)
  const flatItems = useMemo(() => flattenMenus(menus), [menus]);

  // ---------------------------------------------------------------------------
  // Keyboard shortcut: Cmd+K / Ctrl+K
  // ---------------------------------------------------------------------------
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  // Focus input when dialog opens
  useEffect(() => {
    if (open) {
      setQuery('');
      setRecordResults([]);
      setDocResults([]);
      setActiveIndex(0);
      // Small delay to ensure dialog is mounted
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [open]);

  // ---------------------------------------------------------------------------
  // Menu filtering (instant, no API)
  // ---------------------------------------------------------------------------
  const menuMatches = useMemo(() => {
    if (!query.trim()) return [];
    const q = query.toLowerCase();
    return flatItems
      .filter(
        (item) =>
          item.name.toLowerCase().includes(q) ||
          (item.parentName && item.parentName.toLowerCase().includes(q)) ||
          item.path.toLowerCase().includes(q),
      )
      .slice(0, 10);
  }, [query, flatItems]);

  // ---------------------------------------------------------------------------
  // Record search (debounced API calls)
  // ---------------------------------------------------------------------------
  useEffect(() => {
    if (!query.trim() || query.length < 2) {
      setRecordResults([]);
      return;
    }

    const timer = setTimeout(async () => {
      // Abort previous request
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setSearching(true);
      try {
        // Unified server-side global search: the backend converges candidates
        // to models the caller may read and executes grouped keyword queries
        // in one request (permission enforcement lives server-side).
        const resp = await fetchResult<any>('/api/search/global', {
          method: 'get',
          params: { keyword: query, perModelLimit: '3', maxModels: '12' },
        });
        if (!controller.signal.aborted) {
          if (ResultHelper.isSuccess(resp) && Array.isArray(resp.data?.groups)) {
            const hits = (
              resp.data.groups as Array<{
                modelCode: string;
                modelLabel: string;
                records?: unknown[];
              }>
            ).flatMap((group) =>
              (group.records ?? [])
                .map((record) => toRecordHit(record, group.modelCode, group.modelLabel))
                .filter((hit: RecordHit | null): hit is RecordHit => hit !== null),
            );
            setRecordResults(hits.slice(0, 15));
          } else {
            setRecordResults([]);
          }
        }
      } catch {
        // Search was aborted or failed
      } finally {
        if (!controller.signal.aborted) {
          setSearching(false);
        }
      }
    }, 350);

    return () => clearTimeout(timer);
  }, [query]);

  // ---------------------------------------------------------------------------
  // Doc search via RAG API (debounced)
  // ---------------------------------------------------------------------------
  useEffect(() => {
    if (!query.trim() || query.length < 2) {
      setDocResults([]);
      return;
    }

    const timer = setTimeout(async () => {
      try {
        const resp = await fetchResult<any>('/api/ai/knowledge/retrieve', {
          method: 'post',
          params: { query: query.trim(), topK: 5 },
        });
        if (ResultHelper.isSuccess(resp) && resp.data?.results?.length > 0) {
          setDocResults(
            resp.data.results.map((r: any) => ({
              chunkPid: r.chunkPid,
              docName: r.docName || 'Unknown',
              kbName: r.kbName || '',
              content: r.content || '',
              similarity: r.similarity || 0,
            })),
          );
        } else {
          setDocResults([]);
        }
      } catch {
        setDocResults([]);
      }
    }, 350);

    return () => clearTimeout(timer);
  }, [query]);

  // ---------------------------------------------------------------------------
  // Personal search model selection
  // ---------------------------------------------------------------------------
  const loadSearchSettings = useCallback(async () => {
    setSettingsLoading(true);
    setSettingsMessage('');
    try {
      const resp = await fetchResult<{
        models?: SearchModelCandidate[];
        preference?: SearchPreference;
      }>('/api/search/global/candidates', { method: 'get' });
      if (!ResultHelper.isSuccess(resp) || !Array.isArray(resp.data?.models)) {
        throw new Error(resp?.desc || resp?.message || 'Unable to load search models');
      }
      const models = resp.data.models;
      const validCodes = new Set(models.map((model) => model.modelCode));
      const preference = resp.data.preference;
      const selected = preference?.configured
        ? (preference.enabledModelCodes ?? []).filter((code) => validCodes.has(code))
        : models.map((model) => model.modelCode);
      setCandidateModels(models);
      setSelectedModels(selected);
    } catch (error) {
      setSettingsMessage(error instanceof Error ? error.message : 'Unable to load search models');
      setCandidateModels([]);
      setSelectedModels([]);
    } finally {
      setSettingsLoading(false);
    }
  }, []);

  const openSearchSettings = useCallback(() => {
    setSettingsOpen(true);
    void loadSearchSettings();
  }, [loadSearchSettings]);

  const toggleSearchModel = useCallback((modelCode: string, enabled: boolean) => {
    setSelectedModels((current) =>
      enabled ? [...current, modelCode] : current.filter((code) => code !== modelCode),
    );
  }, []);

  const moveSearchModel = useCallback((modelCode: string, direction: -1 | 1) => {
    setSelectedModels((current) => {
      const index = current.indexOf(modelCode);
      const target = index + direction;
      if (index < 0 || target < 0 || target >= current.length) return current;
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  }, []);

  const saveSearchSettings = useCallback(async () => {
    setSettingsSaving(true);
    setSettingsMessage('');
    try {
      const resp = await fetchResult('/api/search/global/preferences', {
        method: 'put',
        params: { modelCodes: selectedModels },
      });
      if (!ResultHelper.isSuccess(resp)) {
        throw new Error(resp?.desc || resp?.message || 'Unable to save search settings');
      }
      setSettingsMessage('Search settings saved');
    } catch (error) {
      setSettingsMessage(error instanceof Error ? error.message : 'Unable to save search settings');
    } finally {
      setSettingsSaving(false);
    }
  }, [selectedModels]);

  // ---------------------------------------------------------------------------
  // Build combined results list
  // ---------------------------------------------------------------------------
  const allResults = useMemo<SearchResult[]>(() => {
    const results: SearchResult[] = [];

    if (!query.trim()) {
      // Show recent searches when no query
      const recent = loadRecent();
      for (const kw of recent) {
        results.push({ kind: 'recent', keyword: kw });
      }
      return results;
    }

    // Menu matches first
    for (const item of menuMatches) {
      results.push({ kind: 'menu', item });
    }

    // Then record matches
    for (const hit of recordResults) {
      results.push({ kind: 'record', hit });
    }

    // Then doc matches
    for (const hit of docResults) {
      results.push({ kind: 'doc', hit });
    }

    return results;
  }, [query, menuMatches, recordResults, docResults]);

  // Reset active index when results change
  useEffect(() => {
    setActiveIndex(0);
  }, [allResults.length]);

  // ---------------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------------
  const handleSelect = useCallback(
    (result: SearchResult) => {
      setOpen(false);

      if (result.kind === 'menu') {
        if (query.trim()) addRecent(query.trim());
        navigate(result.item.path);
      } else if (result.kind === 'record') {
        if (query.trim()) addRecent(query.trim());
        navigate(result.hit.path);
      } else if (result.kind === 'doc') {
        if (query.trim()) addRecent(query.trim());
        // Navigate to knowledge base page
        navigate('/aurabot/knowledge');
      } else if (result.kind === 'recent') {
        setQuery(result.keyword);
        setOpen(true); // Keep open and search
      }
    },
    [navigate, query],
  );

  // ---------------------------------------------------------------------------
  // Keyboard navigation
  // ---------------------------------------------------------------------------
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (settingsOpen) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActiveIndex((i) => Math.min(i + 1, allResults.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActiveIndex((i) => Math.max(i - 1, 0));
      } else if (e.key === 'Enter' && allResults[activeIndex]) {
        e.preventDefault();
        handleSelect(allResults[activeIndex]);
      }
    },
    [allResults, activeIndex, handleSelect, settingsOpen],
  );

  // Scroll active item into view
  useEffect(() => {
    const el = listRef.current?.querySelector(`[data-index="${activeIndex}"]`);
    el?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex]);

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------
  const isMac = isHydrated && typeof navigator !== 'undefined' && /Mac/.test(navigator.userAgent);

  return (
    <>
      {/* Trigger button in header */}
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="rounded-control border-border-strong bg-panel text-body text-text-2 hover:bg-subtle hidden h-[var(--ds-control-field)] w-9 shrink-0 items-center justify-center gap-2 border px-0 transition-colors sm:flex md:w-48 md:justify-start md:px-3 lg:w-64 xl:w-[360px] dark:border-gray-600 dark:bg-gray-700/50 dark:text-gray-400 dark:hover:bg-gray-700"
        data-testid="header-search-trigger"
      >
        <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
          />
        </svg>
        <span className="hidden md:inline">{t('search.placeholder', 'Search...')}</span>
        <kbd className="bg-hover text-text-3 hidden items-center gap-0.5 rounded px-1.5 py-0.5 font-mono text-[10px] font-medium md:inline-flex dark:bg-gray-600 dark:text-gray-500">
          {isMac ? '⌘' : 'Ctrl'}K
        </kbd>
      </button>

      {/* Dialog */}
      <DialogPrimitive.Root open={open} onOpenChange={setOpen}>
        <DialogPrimitive.Portal>
          <DialogPrimitive.Overlay className="data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 fixed inset-0 z-[100] bg-black/50" />
          <DialogPrimitive.Content
            className="data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 rounded-card-lg border-border bg-panel fixed top-[15%] left-1/2 z-[100] w-full max-w-xl -translate-x-1/2 border shadow-2xl dark:border-gray-700 dark:bg-gray-900"
            onKeyDown={handleKeyDown}
            data-testid="command-palette"
          >
            {/* Visually hidden title for accessibility */}
            <DialogPrimitive.Title className="sr-only">
              {t('search.title', 'Global Search')}
            </DialogPrimitive.Title>

            {/* Search input */}
            <div className="border-border flex items-center gap-3 border-b px-4 py-3 dark:border-gray-700">
              <svg
                className="text-text-3 h-5 w-5 shrink-0"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                />
              </svg>
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t('search.inputPlaceholder', 'Search pages, records, docs...')}
                className="text-text flex-1 bg-transparent text-sm placeholder-gray-400 outline-none dark:text-gray-100 dark:placeholder-gray-500"
                data-testid="command-palette-input"
              />
              {searching && (
                <div className="rounded-pill border-accent h-4 w-4 animate-spin border-2 border-t-transparent" />
              )}
              <button
                type="button"
                onClick={openSearchSettings}
                title={t('search.settings', 'Search settings')}
                aria-label={t('search.settings', 'Search settings')}
                className="text-text-3 hover:bg-subtle hover:text-text-2 rounded-control h-7 w-7 shrink-0 transition-colors dark:hover:bg-gray-800"
                data-testid="command-palette-settings"
              >
                <svg
                  className="mx-auto h-4 w-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                  />
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                  />
                </svg>
              </button>
              <kbd className="border-border bg-hover text-text-3 rounded border px-1.5 py-0.5 font-mono text-[10px] dark:border-gray-700 dark:bg-gray-800 dark:text-gray-500">
                Esc
              </kbd>
            </div>

            {settingsOpen ? (
              <div
                className="max-h-[50vh] overflow-y-auto px-4 py-3"
                data-testid="command-palette-settings-panel"
              >
                <div className="mb-3 flex items-center justify-between gap-3">
                  <div>
                    <div className="text-text text-sm font-semibold">
                      {t('search.settings', 'Search settings')}
                    </div>
                    <div className="text-text-3 mt-0.5 text-xs dark:text-gray-500">
                      {t('search.settingsHint', 'Choose and order the record models searched.')}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setSettingsOpen(false)}
                    className="rounded-control border-border bg-panel text-text-2 hover:bg-subtle h-8 border px-3 text-xs"
                    data-testid="command-palette-settings-close"
                  >
                    {t('common.back', 'Back')}
                  </button>
                </div>

                {settingsLoading ? (
                  <div className="text-text-3 py-8 text-center text-sm">Loading...</div>
                ) : candidateModels.length === 0 ? (
                  <div className="text-text-3 py-8 text-center text-sm">
                    {t('search.noReadableModels', 'No readable search models')}
                  </div>
                ) : (
                  <div className="space-y-1">
                    {candidateModels.map((model) => {
                      const enabled = selectedModels.includes(model.modelCode);
                      return (
                        <div
                          key={model.modelCode}
                          className="border-border hover:bg-subtle rounded-card flex items-center gap-2 border px-3 py-2"
                          data-testid={`search-model-${model.modelCode}`}
                        >
                          <input
                            id={`search-model-checkbox-${model.modelCode}`}
                            type="checkbox"
                            checked={enabled}
                            onChange={(event) =>
                              toggleSearchModel(model.modelCode, event.target.checked)
                            }
                            className="accent-accent h-4 w-4"
                            data-testid={`search-model-checkbox-${model.modelCode}`}
                          />
                          <label
                            htmlFor={`search-model-checkbox-${model.modelCode}`}
                            className="text-text-2 min-w-0 flex-1 cursor-pointer truncate text-sm"
                          >
                            {model.modelLabel}
                          </label>
                          {enabled && (
                            <div className="flex shrink-0 items-center gap-1">
                              <button
                                type="button"
                                onClick={() => moveSearchModel(model.modelCode, -1)}
                                disabled={selectedModels[0] === model.modelCode}
                                className="rounded-control border-border text-text-3 hover:text-text-2 h-6 w-6 border disabled:cursor-not-allowed disabled:opacity-40"
                                aria-label={`${t('common.moveUp', 'Move up')} ${model.modelLabel}`}
                                data-testid={`search-model-up-${model.modelCode}`}
                              >
                                ↑
                              </button>
                              <button
                                type="button"
                                onClick={() => moveSearchModel(model.modelCode, 1)}
                                disabled={
                                  selectedModels[selectedModels.length - 1] === model.modelCode
                                }
                                className="rounded-control border-border text-text-3 hover:text-text-2 h-6 w-6 border disabled:cursor-not-allowed disabled:opacity-40"
                                aria-label={`${t('common.moveDown', 'Move down')} ${model.modelLabel}`}
                                data-testid={`search-model-down-${model.modelCode}`}
                              >
                                ↓
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}

                {settingsMessage && (
                  <div
                    className="text-text-3 mt-3 text-xs"
                    data-testid="command-palette-settings-message"
                  >
                    {settingsMessage}
                  </div>
                )}

                <button
                  type="button"
                  onClick={saveSearchSettings}
                  disabled={settingsSaving || settingsLoading}
                  className="accent-accent rounded-card bg-accent mt-3 h-8 w-full px-3 text-xs font-medium text-white disabled:opacity-60"
                  data-testid="command-palette-settings-save"
                >
                  {settingsSaving ? 'Saving...' : t('common.save', 'Save')}
                </button>
              </div>
            ) : (
              <div
                ref={listRef}
                className="max-h-[50vh] overflow-y-auto px-2 py-2"
                data-testid="command-palette-results"
              >
                {allResults.length === 0 && query.trim() && !searching && (
                  <div className="text-text-3 px-4 py-8 text-center text-sm dark:text-gray-500">
                    {t('search.noResults', 'No results found')}
                  </div>
                )}

                {allResults.length === 0 && !query.trim() && (
                  <div className="text-text-3 px-4 py-6 text-center text-sm dark:text-gray-500">
                    {t('search.hint', 'Type to search pages, records and docs')}
                  </div>
                )}

                {/* Group: Recent */}
                {allResults.some((r) => r.kind === 'recent') && (
                  <div className="mb-1">
                    <div className="text-text-3 px-3 py-1.5 text-[11px] font-medium tracking-wider uppercase dark:text-gray-500">
                      {t('search.recent', 'Recent')}
                    </div>
                    {allResults
                      .filter(
                        (r): r is Extract<SearchResult, { kind: 'recent' }> => r.kind === 'recent',
                      )
                      .map((r, idx) => {
                        const globalIdx = allResults.indexOf(r);
                        return (
                          <ResultRow
                            key={`recent-${idx}`}
                            active={activeIndex === globalIdx}
                            dataIndex={globalIdx}
                            onClick={() => handleSelect(r)}
                            onMouseEnter={() => setActiveIndex(globalIdx)}
                          >
                            <svg
                              className="text-text-3 h-4 w-4 shrink-0"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                              />
                            </svg>
                            <span className="truncate">{r.keyword}</span>
                          </ResultRow>
                        );
                      })}
                  </div>
                )}

                {/* Group: Pages */}
                {menuMatches.length > 0 && (
                  <div className="mb-1">
                    <div className="text-text-3 px-3 py-1.5 text-[11px] font-medium tracking-wider uppercase dark:text-gray-500">
                      {t('search.pages', 'Pages')}
                    </div>
                    {allResults
                      .filter(
                        (r): r is Extract<SearchResult, { kind: 'menu' }> => r.kind === 'menu',
                      )
                      .map((r, idx) => {
                        const globalIdx = allResults.indexOf(r);
                        return (
                          <ResultRow
                            key={`menu-${idx}`}
                            active={activeIndex === globalIdx}
                            dataIndex={globalIdx}
                            onClick={() => handleSelect(r)}
                            onMouseEnter={() => setActiveIndex(globalIdx)}
                          >
                            <svg
                              className="text-text-3 h-4 w-4 shrink-0"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                              />
                            </svg>
                            <div className="min-w-0 flex-1">
                              <span className="truncate">{r.item.name}</span>
                              {r.item.parentName && (
                                <span className="text-text-3 ml-2 text-xs">
                                  {r.item.parentName}
                                </span>
                              )}
                            </div>
                            <span className="text-text-3 shrink-0 text-xs">{r.item.path}</span>
                          </ResultRow>
                        );
                      })}
                  </div>
                )}

                {/* Group: Records */}
                {recordResults.length > 0 && (
                  <div className="mb-1">
                    <div className="text-text-3 px-3 py-1.5 text-[11px] font-medium tracking-wider uppercase dark:text-gray-500">
                      {t('search.records', 'Records')}
                    </div>
                    {allResults
                      .filter(
                        (r): r is Extract<SearchResult, { kind: 'record' }> => r.kind === 'record',
                      )
                      .map((r, idx) => {
                        const globalIdx = allResults.indexOf(r);
                        return (
                          <ResultRow
                            key={`record-${idx}`}
                            active={activeIndex === globalIdx}
                            dataIndex={globalIdx}
                            onClick={() => handleSelect(r)}
                            onMouseEnter={() => setActiveIndex(globalIdx)}
                          >
                            <svg
                              className="text-accent h-4 w-4 shrink-0"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4"
                              />
                            </svg>
                            <div className="min-w-0 flex-1">
                              <span className="truncate">{r.hit.displayText}</span>
                            </div>
                            <span className="bg-hover text-text-3 shrink-0 rounded px-1.5 py-0.5 text-xs dark:bg-gray-800">
                              {r.hit.modelName}
                            </span>
                          </ResultRow>
                        );
                      })}
                  </div>
                )}

                {/* Group: Docs (RAG) */}
                {docResults.length > 0 && (
                  <div className="mb-1">
                    <div className="text-text-3 px-3 py-1.5 text-[11px] font-medium tracking-wider uppercase dark:text-gray-500">
                      {t('search.docs', 'Docs')}
                    </div>
                    {allResults
                      .filter((r): r is Extract<SearchResult, { kind: 'doc' }> => r.kind === 'doc')
                      .map((r, idx) => {
                        const globalIdx = allResults.indexOf(r);
                        const snippet =
                          r.hit.content.length > 120
                            ? r.hit.content.substring(0, 120) + '...'
                            : r.hit.content;
                        return (
                          <ResultRow
                            key={`doc-${idx}`}
                            active={activeIndex === globalIdx}
                            dataIndex={globalIdx}
                            onClick={() => handleSelect(r)}
                            onMouseEnter={() => setActiveIndex(globalIdx)}
                          >
                            <svg
                              className="h-4 w-4 shrink-0 text-emerald-500"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253"
                              />
                            </svg>
                            <div className="min-w-0 flex-1">
                              <div className="truncate font-medium">{r.hit.docName}</div>
                              <div className="text-text-3 truncate text-xs dark:text-gray-500">
                                {snippet}
                              </div>
                            </div>
                            <span className="shrink-0 rounded bg-emerald-50 px-1.5 py-0.5 text-xs text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-400">
                              {Math.round(r.hit.similarity * 100)}%
                            </span>
                          </ResultRow>
                        );
                      })}
                  </div>
                )}
              </div>
            )}

            {/* Footer */}
            <div className="border-border text-text-3 flex items-center justify-between border-t px-4 py-2 text-[11px] dark:border-gray-700 dark:text-gray-500">
              <div className="flex items-center gap-3">
                <span className="flex items-center gap-1">
                  <kbd className="border-border bg-hover rounded border px-1 py-0.5 font-mono dark:border-gray-700 dark:bg-gray-800">
                    ↑↓
                  </kbd>
                  {t('search.navigate', 'Navigate')}
                </span>
                <span className="flex items-center gap-1">
                  <kbd className="border-border bg-hover rounded border px-1 py-0.5 font-mono dark:border-gray-700 dark:bg-gray-800">
                    ↵
                  </kbd>
                  {t('search.open', 'Open')}
                </span>
                <span className="flex items-center gap-1">
                  <kbd className="border-border bg-hover rounded border px-1 py-0.5 font-mono dark:border-gray-700 dark:bg-gray-800">
                    Esc
                  </kbd>
                  {t('search.close', 'Close')}
                </span>
              </div>
            </div>
          </DialogPrimitive.Content>
        </DialogPrimitive.Portal>
      </DialogPrimitive.Root>
    </>
  );
}

// ---------------------------------------------------------------------------
// Result row
// ---------------------------------------------------------------------------

function ResultRow({
  active,
  dataIndex,
  onClick,
  onMouseEnter,
  children,
}: {
  active: boolean;
  dataIndex: number;
  onClick: () => void;
  onMouseEnter: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      data-index={dataIndex}
      onClick={onClick}
      onMouseEnter={onMouseEnter}
      className={`rounded-card flex w-full items-center gap-3 px-3 py-2 text-left text-sm transition-colors ${
        active
          ? 'bg-accent-weak text-accent dark:bg-blue-900/30 dark:text-blue-300'
          : 'text-text-2 hover:bg-subtle dark:text-gray-300 dark:hover:bg-gray-800'
      }`}
    >
      {children}
    </button>
  );
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Extract a human-readable display string from a dynamic record */
function extractDisplayText(record: any): string {
  // Try common display field patterns
  const candidates = [
    'name',
    'title',
    'code',
    'display_name',
    'subject',
    // CRM patterns
    'crm_acc_name',
    'crm_lead_company',
    'crm_opp_name',
    'crm_qt_name',
    // PM patterns
    'pm_prj_name',
    'pm_task_title',
    // Generic patterns with common prefixes
  ];

  for (const key of candidates) {
    if (record[key]) return String(record[key]);
  }

  // Fallback: find first string field that looks like a name
  for (const [key, val] of Object.entries(record)) {
    if (
      typeof val === 'string' &&
      val.length > 0 &&
      val.length < 200 &&
      !key.endsWith('_id') &&
      !key.endsWith('_at') &&
      key !== 'pid' &&
      key !== 'id' &&
      key !== 'tenant_id' &&
      key !== 'created_by' &&
      key !== 'updated_by'
    ) {
      return val;
    }
  }

  return record.pid || 'Unknown';
}

/** Build a public navigation hit without leaking or accepting internal database ids. */
export function toRecordHit(
  record: unknown,
  modelCode: string,
  modelName: string,
): RecordHit | null {
  if (!record || typeof record !== 'object') return null;
  const row = record as Record<string, unknown>;
  if (typeof row.pid !== 'string' || !row.pid.trim()) return null;
  return {
    pid: row.pid,
    displayText: extractDisplayText(row),
    modelCode,
    modelName,
    path: `/p/${modelCode}/view/${encodeURIComponent(row.pid)}`,
  };
}
