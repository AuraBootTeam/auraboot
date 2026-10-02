import { useState, useEffect, useCallback } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { requireI18nAdminResult } from '~/shared/services/i18n-admin-response';
import { confirmDialog } from '~/utils/confirmDialog';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '~/ui/ui/dialog';
import {
  MagnifyingGlassIcon,
  PencilSquareIcon,
  TrashIcon,
  ArrowPathIcon,
  LanguageIcon,
  SparklesIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';

async function adminRequest<T>(path: string, options: Parameters<typeof fetchResult>[1]) {
  return requireI18nAdminResult(await fetchResult<T>(path, options));
}


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
  const [batchTranslating, setBatchTranslating] = useState(false);
  const [mutationLoading, setMutationLoading] = useState(false);
  const [rejectTarget, setRejectTarget] = useState<I18nResourceRow | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const page = await adminRequest<I18nPage>('/api/admin/i18n/resources', {
        method: 'get',
        params: {
          pageNum,
          pageSize: 20,
          lang: lang || undefined,
          keyPrefix: keyPrefix || undefined,
          keyword: keyword || undefined,
        },
      });
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
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [pageNum, lang, keyPrefix, keyword, l]);

  useEffect(() => {
    load();
  }, [load]);

  const mutate = useCallback(async (operation: () => Promise<unknown>) => {
    if (mutationLoading) return false;
    setMutationLoading(true);
    setError('');
    try {
      await operation();
      await load();
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : l('操作失败', 'Operation failed'));
      return false;
    } finally {
      setMutationLoading(false);
    }
  }, [load, l, mutationLoading]);

  const saveEdit = async (pid: string) => {
    if (await mutate(() => adminRequest(`/api/admin/i18n/resources/${pid}`, {
      method: 'put', params: { value: editValue },
    }))) setEditingPid(null);
  };

  const submitReview = (pid: string) => mutate(() => adminRequest(
    `/api/admin/i18n/resources/${pid}/submit-review`, { method: 'post' },
  ));

  const approve = (pid: string) => mutate(() => adminRequest(
    `/api/admin/i18n/resources/${pid}/approve`, { method: 'post' },
  ));

  const reject = async () => {
    if (!rejectTarget || !rejectReason.trim()) return;
    if (await mutate(() => adminRequest(`/api/admin/i18n/resources/${rejectTarget.pid}/reject`, {
      method: 'post', params: { reason: rejectReason.trim() },
    }))) setRejectTarget(null);
  };

  const remove = async (pid: string) => {
    if (!await confirmDialog({
      title: l('删除翻译', 'Delete translation'),
      content: l('确认删除该条目？', 'Delete this resource?'), variant: 'danger',
    })) return;
    await mutate(() => adminRequest(`/api/admin/i18n/resources/${pid}`, { method: 'delete' }));
  };

  const create = async () => {
    if (!newKey.trim() || !newValue.trim()) return;
    if (await mutate(() => adminRequest('/api/admin/i18n/resources', {
      method: 'post', params: { key: newKey.trim(), lang: newLang, value: newValue },
    }))) { setNewKey(''); setNewValue(''); }
  };

  const aiTranslateBatch = async (targetLocale: string) => {
    setBatchTranslating(true);
    try {
      await mutate(() => adminRequest('/api/admin/i18n/ai-translate', {
        method: 'post', params: { targetLocale, sourceLocale: 'zh-CN', maxKeys: 200 },
      }));
    } finally { setBatchTranslating(false); }
  };

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
        <button
          onClick={() => aiTranslateBatch('en-US')}
          disabled={batchTranslating || mutationLoading}
          className="border rounded px-2 py-1 text-sm flex items-center gap-1 disabled:opacity-50"
          title={l('为 en-US 补齐缺失翻译(≤200 条)', 'Fill missing en-US translations (≤200)')}
        >
          <SparklesIcon className="h-4 w-4" /> {l('AI 批量翻译 → en-US', 'AI batch translate → en-US')}
        </button>
        <span className="text-sm text-text-3">{total}</span>
      </div>

      <div className="flex flex-wrap items-center gap-2 border rounded p-2">
        <input value={newKey} onChange={(e) => setNewKey(e.target.value)} placeholder="key" className="border rounded px-2 py-1 text-sm" />
        <select value={newLang} onChange={(e) => setNewLang(e.target.value)} className="border rounded px-2 py-1 text-sm" aria-label="new lang">
          {LOCALES.map((x) => <option key={x} value={x}>{x}</option>)}
        </select>
        <input value={newValue} onChange={(e) => setNewValue(e.target.value)} placeholder={l('文案', 'value')} className="border rounded px-2 py-1 text-sm flex-1 min-w-40" />
        <button onClick={create} disabled={mutationLoading || !newKey.trim() || !newValue.trim()} className="border rounded px-3 py-1 text-sm disabled:opacity-50">
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
              <th className="p-2">status</th>
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
                  <span
                    className={
                      r.status === 'approved'
                        ? 'text-green-700 bg-green-100 rounded px-1.5 py-0.5'
                        : r.status === 'deprecated'
                          ? 'text-red-700 bg-red-100 rounded px-1.5 py-0.5'
                          : 'text-amber-700 bg-amber-100 rounded px-1.5 py-0.5'
                    }
                  >
                    {({ draft: l('草稿', 'Draft'), review: l('待审核', 'Pending Review'), approved: l('已批准', 'Approved'), deprecated: l('已停用', 'Deprecated') } as Record<string, string>)[r.status] ?? l('未知状态', 'Unknown status')}
                  </span>
                </td>
                <td className="p-2 whitespace-nowrap">
                  {editingPid === r.pid ? (
                    <button disabled={mutationLoading} onClick={() => saveEdit(r.pid)} className="text-blue-600 px-1">
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
                  {r.status === 'draft' && (
                    <button disabled={mutationLoading} onClick={() => submitReview(r.pid)} className="text-blue-600 px-1" aria-label={`submit-review-${r.i18nKey}`}>
                      {l('提交审核', 'Submit for review')}
                    </button>
                  )}
                  {r.status === 'review' && (
                    <button disabled={mutationLoading} onClick={() => approve(r.pid)} className="text-green-700 px-1" aria-label={`approve-${r.i18nKey}`} title={l('批准', 'Approve')}>
                      ✓
                    </button>
                  )}
                  {r.status === 'review' && (
                    <button disabled={mutationLoading} onClick={() => { setRejectReason(''); setRejectTarget(r); }} className="text-red-600 px-1" aria-label={`reject-${r.i18nKey}`} title={l('驳回', 'Reject')}>
                      ✕
                    </button>
                  )}
                  <button disabled={mutationLoading} onClick={() => remove(r.pid)} className="text-red-600 px-1" aria-label={`delete-${r.i18nKey}`}>
                    <TrashIcon className="h-4 w-4 inline" />
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-text-3">{l('无数据', 'No data')}</td></tr>
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
      <Dialog open={rejectTarget !== null} onOpenChange={(open) => {
        if (!open && !mutationLoading) setRejectTarget(null);
      }}>
        <DialogContent>
          <DialogTitle>{l('驳回翻译', 'Reject translation')}</DialogTitle>
          <DialogDescription>{rejectTarget?.i18nKey}</DialogDescription>
          <label htmlFor="i18n-reject-reason">{l('驳回原因（必填）', 'Rejection reason (required)')}</label>
          <textarea id="i18n-reject-reason" value={rejectReason} disabled={mutationLoading}
            onChange={(event) => setRejectReason(event.target.value)} className="border rounded p-2" />
          <div className="flex justify-end gap-2">
            <button disabled={mutationLoading} onClick={() => setRejectTarget(null)}>{l('取消', 'Cancel')}</button>
            <button disabled={mutationLoading || !rejectReason.trim()} onClick={reject}>{l('驳回', 'Reject')}</button>
          </div>
          {error && <p role="alert" className="text-red-600">{error}</p>}
        </DialogContent>
      </Dialog>
    </div>
  );
}
