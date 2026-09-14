import { useState, useEffect, useCallback } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import {
  MagnifyingGlassIcon,
  PencilSquareIcon,
  TrashIcon,
  ArrowPathIcon,
  LanguageIcon,
  SparklesIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';

/**
 * I18n Resources admin page — CRUD over /api/admin/i18n/resources.
 *
 * Key directory conventions (server pack keys, see docs/plans/2026-09-14-*
 * i18n docs):
 *   menu.<code>                          menu display name
 *   model.<model>._meta.label            model display name
 *   model.<model>.<field>.label          field display name
 *   <namespace>.<path>…                  chrome/UI strings (common.*, auth.*…)
 */

interface I18nResourceRow {
  pid: string;
  i18nKey: string;
  lang: string;
  value: string;
  source: string | null;
  refType: string | null;
  status: string;
}

interface I18nPage {
  records: I18nResourceRow[];
  total: number;
}

const LOCALES = ['zh-CN', 'en-US', 'ja-JP', 'ko-KR'];

export default function I18nResourcesPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zhCN: string, enUS: string) => (locale === 'zh-CN' ? zhCN : enUS),
    [locale],
  );

  const [rows, setRows] = useState<I18nResourceRow[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [lang, setLang] = useState('zh-CN');
  const [keyPrefix, setKeyPrefix] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const [editingPid, setEditingPid] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const [newKey, setNewKey] = useState('');
  const [newLang, setNewLang] = useState('zh-CN');
  const [newValue, setNewValue] = useState('');
  const [translatingKey, setTranslatingKey] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await fetchResult<I18nPage>('/api/admin/i18n/resources', {
        method: 'get',
        params: {
          pageNum,
          pageSize: 20,
          lang: lang || undefined,
          keyPrefix: keyPrefix || undefined,
          keyword: keyword || undefined,
        },
      });
      const page = (result as { data?: I18nPage }).data;
      if (page && Array.isArray(page.records)) {
        setRows(page.records);
        setTotal(page.total ?? page.records.length);
      } else {
        setRows([]);
        setTotal(0);
      }
    } catch {
      setError(l('加载失败', 'Failed to load resources'));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [pageNum, lang, keyPrefix, keyword, l]);

  useEffect(() => {
    load();
  }, [load]);

  const saveEdit = useCallback(
    async (pid: string) => {
      await fetchResult(`/api/admin/i18n/resources/${pid}`, {
        method: 'put',
        params: { value: editValue, status: 'approved' },
      });
      setEditingPid(null);
      load();
    },
    [editValue, load],
  );

  const remove = useCallback(
    async (pid: string) => {
      if (!window.confirm(l('确认删除该条目？', 'Delete this resource?'))) return;
      await fetchResult(`/api/admin/i18n/resources/${pid}`, { method: 'delete' });
      load();
    },
    [l, load],
  );

  const create = useCallback(async () => {
    if (!newKey || !newValue) return;
    await fetchResult('/api/admin/i18n/resources', {
      method: 'post',
      params: { key: newKey, lang: newLang, value: newValue },
    });
    setNewKey('');
    setNewValue('');
    load();
  }, [newKey, newLang, newValue, load]);

  const aiTranslate = useCallback(
    async (sourceKey: string) => {
      setTranslatingKey(sourceKey);
      try {
        await fetchResult('/api/admin/i18n/ai-translate', {
          method: 'post',
          params: { key: sourceKey, sourceLocale: 'zh-CN', targetLocale: 'en-US' },
        });
        load();
      } finally {
        setTranslatingKey(null);
      }
    },
    [load],
  );

  const totalPages = Math.max(1, Math.ceil(total / 20));

  return (
    <div className="p-6 space-y-4" data-testid="i18n-resources-page">
      <div className="flex items-center gap-2">
        <LanguageIcon className="h-6 w-6 text-text-3" />
        <h1 className="text-lg font-semibold">{l('i18n 资源管理', 'I18n Resources')}</h1>
      </div>
      <p className="text-sm text-text-3">
        {l(
          '目录约定：menu.<code>（菜单）、model.<model>._meta.label（模型）、model.<model>.<field>.label（字段）。',
          'Conventions: menu.<code> (menus), model.<model>._meta.label (models), model.<model>.<field>.label (fields).',
        )}
      </p>

      <div className="flex flex-wrap items-center gap-2">
        <select
          value={lang}
          onChange={(e) => { setLang(e.target.value); setPageNum(1); }}
          className="border rounded px-2 py-1 text-sm"
          aria-label="lang"
        >
          {LOCALES.map((x) => <option key={x} value={x}>{x}</option>)}
        </select>
        <input
          value={keyPrefix}
          onChange={(e) => { setKeyPrefix(e.target.value); setPageNum(1); }}
          placeholder={l('key 前缀，如 menu.', 'key prefix, e.g. menu.')}
          className="border rounded px-2 py-1 text-sm"
        />
        <input
          value={keyword}
          onChange={(e) => { setKeyword(e.target.value); setPageNum(1); }}
          placeholder={l('关键字', 'keyword')}
          className="border rounded px-2 py-1 text-sm"
        />
        <button onClick={load} className="border rounded px-2 py-1 text-sm flex items-center gap-1">
          <MagnifyingGlassIcon className="h-4 w-4" /> {l('查询', 'Search')}
        </button>
        <span className="text-sm text-text-3">{total}</span>
      </div>

      <div className="flex flex-wrap items-center gap-2 border rounded p-2">
        <input value={newKey} onChange={(e) => setNewKey(e.target.value)} placeholder="key" className="border rounded px-2 py-1 text-sm" />
        <select value={newLang} onChange={(e) => setNewLang(e.target.value)} className="border rounded px-2 py-1 text-sm" aria-label="new lang">
          {LOCALES.map((x) => <option key={x} value={x}>{x}</option>)}
        </select>
        <input value={newValue} onChange={(e) => setNewValue(e.target.value)} placeholder={l('文案', 'value')} className="border rounded px-2 py-1 text-sm flex-1 min-w-40" />
        <button onClick={create} disabled={!newKey || !newValue} className="border rounded px-3 py-1 text-sm disabled:opacity-50">
          {l('新增', 'Create')}
        </button>
      </div>

      {error && <div className="text-red-600 text-sm">{error}</div>}
      {loading ? (
        <div className="text-sm text-text-3 flex items-center gap-2">
          <ArrowPathIcon className="h-4 w-4 animate-spin" /> {l('加载中…', 'Loading…')}
        </div>
      ) : (
        <table className="w-full text-sm border" data-testid="i18n-resources-table">
          <thead>
            <tr className="bg-secondary text-left">
              <th className="p-2">key</th>
              <th className="p-2">lang</th>
              <th className="p-2">{l('文案', 'value')}</th>
              <th className="p-2">{l('操作', 'actions')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.pid} className="border-t">
                <td className="p-2 font-mono text-xs break-all">{r.i18nKey}</td>
                <td className="p-2 whitespace-nowrap">{r.lang}</td>
                <td className="p-2">
                  {editingPid === r.pid ? (
                    <input value={editValue} onChange={(e) => setEditValue(e.target.value)} className="border rounded px-2 py-0.5 w-full" />
                  ) : (
                    r.value
                  )}
                </td>
                <td className="p-2 whitespace-nowrap">
                  {editingPid === r.pid ? (
                    <button onClick={() => saveEdit(r.pid)} className="text-blue-600 px-1">
                      {l('保存', 'Save')}
                    </button>
                  ) : (
                    <button
                      onClick={() => { setEditingPid(r.pid); setEditValue(r.value); }}
                      className="text-blue-600 px-1"
                      aria-label={`edit-${r.i18nKey}`}
                    >
                      <PencilSquareIcon className="h-4 w-4 inline" />
                    </button>
                  )}
                  <button
                    onClick={() => aiTranslate(r.i18nKey)}
                    disabled={translatingKey === r.i18nKey}
                    className="text-purple-600 px-1 disabled:opacity-50"
                    aria-label={`translate-${r.i18nKey}`}
                    title={l('AI 翻译', 'AI translate')}
                  >
                    <SparklesIcon className="h-4 w-4 inline" />
                  </button>
                  <button onClick={() => remove(r.pid)} className="text-red-600 px-1" aria-label={`delete-${r.i18nKey}`}>
                    <TrashIcon className="h-4 w-4 inline" />
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={4} className="p-4 text-center text-text-3">{l('无数据', 'No data')}</td></tr>
            )}
          </tbody>
        </table>
      )}

      <div className="flex items-center gap-2 text-sm">
        <button disabled={pageNum <= 1} onClick={() => setPageNum(pageNum - 1)} className="border rounded px-2 py-1 disabled:opacity-50">
          {l('上一页', 'Prev')}
        </button>
        <span>{pageNum} / {totalPages}</span>
        <button disabled={pageNum >= totalPages} onClick={() => setPageNum(pageNum + 1)} className="border rounded px-2 py-1 disabled:opacity-50">
          {l('下一页', 'Next')}
        </button>
      </div>
    </div>
  );
}
