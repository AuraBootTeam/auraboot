/**
 * Dictionary Detail Page
 *
 * Displays dictionary details with tabs for basic info, items, and versions
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import {
  useNavigate,
  useParams,
  useLoaderData,
  useLocation,
  type LoaderFunctionArgs,
} from 'react-router';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import tz from 'dayjs/plugin/timezone';
import { dictService } from '~/shared/services/dictService';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';
import { useTimezone } from '~/contexts/TimezoneContext';
import type { DictDTO, DictItemData, DictTreeNode } from '~/types/dict';

dayjs.extend(utc);
dayjs.extend(tz);

type TabType = 'basic' | 'items' | 'versions';
type EditableItem = DictItemData & { _id: string };

const VALID_TABS: TabType[] = ['basic', 'items', 'versions'];

const toEditableItems = (source: DictItemData[]): EditableItem[] =>
  source.map((item, index) => ({
    ...item,
    _id: `${item.value || 'item'}-${index}-${Math.random().toString(36).slice(2, 8)}`,
    order: item.order ?? item.sortOrder ?? index,
  }));

const normalizeSiblingOrder = (list: EditableItem[]): EditableItem[] => {
  const bucket = new Map<string, EditableItem[]>();
  for (const item of list) {
    const key = item.parentValue || '__ROOT__';
    if (!bucket.has(key)) {
      bucket.set(key, []);
    }
    bucket.get(key)!.push(item);
  }

  return list.map((item) => {
    const key = item.parentValue || '__ROOT__';
    const siblings = bucket.get(key) || [];
    const sorted = [...siblings].sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    const nextOrder = sorted.findIndex((x) => x._id === item._id);
    return { ...item, order: nextOrder < 0 ? 0 : nextOrder };
  });
};

const parseTabFromHash = (hash: string): TabType => {
  const tab = hash.replace('#', '');
  return VALID_TABS.includes(tab as TabType) ? (tab as TabType) : 'basic';
};

function formatDisplayDateTime(value: string | null | undefined, userTimezone: string): string {
  if (!value) return '-';
  const d = dayjs(value);
  if (!d.isValid()) return '-';
  const effectiveTz = userTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;
  return d.tz(effectiveTz).format('YYYY-MM-DD HH:mm:ss');
}

const flattenTreeNodes = (root: DictTreeNode | undefined): DictItemData[] => {
  if (!root) return [];
  const rows: DictItemData[] = [];
  const walk = (node: DictTreeNode, parentValue?: string) => {
    const children = node.children || [];
    const isVirtualRoot = node.value === 'root';
    if (!isVirtualRoot) {
      rows.push({
        value: node.value,
        label: node.label,
        parentValue,
      });
    }
    const nextParent = isVirtualRoot ? undefined : node.value;
    children.forEach((child, index) => {
      walk({ ...child, order: index } as DictTreeNode, nextParent);
    });
  };
  walk(root);
  return rows.map((item, index) => ({ ...item, order: index }));
};

/**
 * Loader function
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pid } = params;

  if (!pid) {
    throw new Response('Dictionary PID is required', { status: 400 });
  }

  try {
    const dict = await dictService.findByPid(pid, request);
    const versions = await dictService.getVersionHistory(dict.code, request).catch((error) => {
      console.warn('Failed to load dictionary version history:', error);
      return [];
    });
    const data = await dictService.loadData(pid, 'latest', undefined, request).catch((error) => {
      console.warn('Failed to load dictionary data:', error);
      return { items: [] } as { items: DictItemData[] };
    });
    if (dict.dictType === 'tree') {
      const tree = await dictService.buildCascadeTree(pid, request).catch((error) => {
        console.warn('Failed to build dictionary tree:', error);
        return undefined;
      });
      if (tree) {
        data.items = flattenTreeNodes(tree);
      }
    }

    return { dict, versions, data };
  } catch (error) {
    console.error('Failed to load dictionary:', error);
    throw new Response('Dictionary not found', { status: 404 });
  }
};

/**
 * Dictionary Detail Page Component
 */
export default function DictDetailPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { pid } = useParams();
  const {
    dict: initialDict,
    versions: initialVersions,
    data: initialData,
  } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { timezone: userTimezone } = useTimezone();

  const [activeTab, setActiveTab] = useState<TabType>(() => parseTabFromHash(location.hash));
  const [dict, setDict] = useState<DictDTO>(initialDict);
  const [versions] = useState<DictDTO[]>(initialVersions || []);
  const [items, setItems] = useState<DictItemData[]>(initialData?.items || []);
  const [editingItems, setEditingItems] = useState<EditableItem[]>(() =>
    toEditableItems(initialData?.items || []),
  );
  const [loading, setLoading] = useState(false);
  const [savingItems, setSavingItems] = useState(false);

  /**
   * Sync active tab with URL hash
   */
  useEffect(() => {
    const syncActiveTab = () => {
      const newTab = parseTabFromHash(window.location.hash || location.hash);
      setActiveTab((currentTab) => (currentTab === newTab ? currentTab : newTab));
    };

    syncActiveTab();
    window.addEventListener('hashchange', syncActiveTab);
    return () => window.removeEventListener('hashchange', syncActiveTab);
  }, [location.hash]);

  /**
   * Handle tab change
   */
  const orderedItems = useMemo(() => normalizeSiblingOrder(editingItems), [editingItems]);

  const rowItems = useMemo(() => {
    const byParent = new Map<string, EditableItem[]>();
    for (const item of orderedItems) {
      const key = item.parentValue || '__ROOT__';
      if (!byParent.has(key)) {
        byParent.set(key, []);
      }
      byParent.get(key)!.push(item);
    }
    for (const [key, group] of byParent.entries()) {
      byParent.set(
        key,
        [...group].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
      );
    }

    const visited = new Set<string>();
    const rows: Array<{ item: EditableItem; depth: number }> = [];
    const walk = (parentValue: string | undefined, depth: number) => {
      const key = parentValue || '__ROOT__';
      const children = byParent.get(key) || [];
      for (const child of children) {
        if (visited.has(child._id)) continue;
        visited.add(child._id);
        rows.push({ item: child, depth });
        walk(child.value, depth + 1);
      }
    };

    walk(undefined, 0);
    for (const item of orderedItems) {
      if (!visited.has(item._id)) {
        rows.push({ item: { ...item, parentValue: undefined }, depth: 0 });
      }
    }
    return rows;
  }, [orderedItems]);

  const hasItemChanges = useMemo(() => {
    const normalize = (source: DictItemData[]) =>
      normalizeSiblingOrder(toEditableItems(source)).map((item) => ({
        value: item.value,
        label: item.label,
        parentValue: item.parentValue || '',
        order: item.order ?? 0,
        disabled: !!item.disabled,
      }));
    return JSON.stringify(normalize(items)) !== JSON.stringify(normalize(orderedItems));
  }, [items, orderedItems]);

  const upsertSiblingItem = useCallback((base: Partial<EditableItem>) => {
    setEditingItems((prev) => {
      const siblings = prev.filter(
        (it) => (it.parentValue || '') === ((base.parentValue || '') as string),
      );
      const nextOrder = siblings.length;
      return [
        ...prev,
        {
          _id: `new-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          value: '',
          label: '',
          order: nextOrder,
          ...base,
        } as EditableItem,
      ];
    });
  }, []);

  const handleAddRootItem = useCallback(() => {
    upsertSiblingItem({ parentValue: undefined });
  }, [upsertSiblingItem]);

  const handleAddChildItem = useCallback(
    (parent: EditableItem) => {
      upsertSiblingItem({ parentValue: parent.value });
    },
    [upsertSiblingItem],
  );

  const handleItemFieldChange = useCallback((id: string, field: keyof EditableItem, value: any) => {
    setEditingItems((prev) =>
      prev.map((item) => (item._id === id ? { ...item, [field]: value } : item)),
    );
  }, []);

  const handleRemoveItem = useCallback((id: string) => {
    setEditingItems((prev) => {
      const map = new Map(prev.map((x) => [x._id, x]));
      const deleting = new Set<string>();
      const queue: string[] = [id];
      while (queue.length > 0) {
        const currentId = queue.shift()!;
        if (deleting.has(currentId)) continue;
        deleting.add(currentId);
        const current = map.get(currentId);
        if (!current) continue;
        for (const maybeChild of prev) {
          if (maybeChild.parentValue === current.value) {
            queue.push(maybeChild._id);
          }
        }
      }
      return prev.filter((item) => !deleting.has(item._id));
    });
  }, []);

  const handleMoveSibling = useCallback((id: string, direction: 'up' | 'down') => {
    setEditingItems((prev) => {
      const normalized = normalizeSiblingOrder(prev);
      const target = normalized.find((item) => item._id === id);
      if (!target) return prev;
      const siblings = normalized
        .filter((item) => (item.parentValue || '') === (target.parentValue || ''))
        .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
      const index = siblings.findIndex((x) => x._id === id);
      if (index < 0) return prev;
      const swapIndex = direction === 'up' ? index - 1 : index + 1;
      if (swapIndex < 0 || swapIndex >= siblings.length) return prev;
      const current = siblings[index];
      const other = siblings[swapIndex];

      return normalized.map((item) => {
        if (item._id === current._id) {
          return { ...item, order: other.order ?? 0 };
        }
        if (item._id === other._id) {
          return { ...item, order: current.order ?? 0 };
        }
        return item;
      });
    });
  }, []);

  const handleSaveItems = useCallback(async () => {
    if (!pid) return;
    const hasEmptyValue = orderedItems.some((item) => !item.value?.trim() || !item.label?.trim());
    if (hasEmptyValue) {
      showErrorToast('字典项的值和标签不能为空');
      return;
    }
    const valueSet = new Set<string>();
    for (const item of orderedItems) {
      const v = item.value.trim();
      if (valueSet.has(v)) {
        showErrorToast(`存在重复的字典项值: ${v}`);
        return;
      }
      valueSet.add(v);
    }
    for (const item of orderedItems) {
      if (item.parentValue && !valueSet.has(item.parentValue)) {
        showErrorToast(`父级值不存在: ${item.parentValue}`);
        return;
      }
    }
    const parentMap = new Map<string, string | undefined>();
    for (const item of orderedItems) {
      parentMap.set(item.value.trim(), item.parentValue?.trim() || undefined);
    }
    for (const item of orderedItems) {
      const seen = new Set<string>();
      let current = item.value.trim();
      while (current) {
        if (seen.has(current)) {
          showErrorToast(`检测到循环父子关系: ${item.value}`);
          return;
        }
        seen.add(current);
        current = parentMap.get(current) || '';
      }
    }

    setSavingItems(true);
    try {
      await dictService.replaceItems(
        pid,
        orderedItems.map((item, index) => ({
          value: item.value.trim(),
          label: item.label.trim(),
          sortOrder: item.order ?? index,
          parentValue: item.parentValue || undefined,
          disabled: !!item.disabled,
          extension: item.extension,
        })),
      );
      const nextItems =
        dict.dictType === 'tree'
          ? flattenTreeNodes(await dictService.buildCascadeTree(pid))
          : (await dictService.loadData(pid, 'latest'))?.items || [];
      setItems(nextItems);
      setEditingItems(toEditableItems(nextItems));
      showSuccessToast('字典项保存成功');
    } catch (error) {
      console.error('Failed to save dict items:', error);
      showErrorToast('字典项保存失败');
    } finally {
      setSavingItems(false);
    }
  }, [pid, orderedItems, showErrorToast, showSuccessToast, dict.dictType]);

  /**
   * Handle edit
   */
  const handleEdit = useCallback(() => {
    navigate(`/meta/dict/${pid}/edit`);
  }, [pid, navigate]);

  /**
   * Handle delete
   */
  const handleDelete = useCallback(async () => {
    const confirmed = await confirmDialog({
      content: `确定要删除字典 "${dict.name}" 吗？此操作不可恢复。`,
      variant: 'danger',
    });

    if (!confirmed) return;

    setLoading(true);
    try {
      await dictService.delete(pid!);
      showSuccessToast('删除成功');
      navigate('/meta/dict');
    } catch (error) {
      console.error('Failed to delete dictionary:', error);
      showErrorToast('删除失败');
    } finally {
      setLoading(false);
    }
  }, [pid, dict, navigate, showSuccessToast, showErrorToast]);

  /**
   * Handle publish
   */
  const handlePublish = useCallback(async () => {
    setLoading(true);
    try {
      const result = await dictService.publish(pid!);
      setDict(result);
      showSuccessToast('发布成功');
    } catch (error) {
      console.error('Failed to publish dictionary:', error);
      showErrorToast('发布失败');
    } finally {
      setLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast]);

  /**
   * Handle unpublish
   */
  const handleUnpublish = useCallback(async () => {
    setLoading(true);
    try {
      const result = await dictService.unpublish(pid!);
      setDict(result);
      showSuccessToast('取消发布成功');
    } catch (error) {
      console.error('Failed to unpublish dictionary:', error);
      showErrorToast('取消发布失败');
    } finally {
      setLoading(false);
    }
  }, [pid, showSuccessToast, showErrorToast]);

  /**
   * Get type label
   */
  const getTypeLabel = (type: string) => {
    switch (type) {
      case 'simple':
        return '简单字典';
      case 'tree':
        return '树形字典';
      default:
        return type;
    }
  };

  /**
   * Get status badge
   */
  const getStatusBadge = (status: string) => {
    const colors = {
      published: 'bg-green-100 text-green-800',
      draft: 'bg-yellow-100 text-yellow-800',
      archived: 'bg-gray-100 text-gray-800',
    };
    const labels = {
      published: '已发布',
      draft: '草稿',
      archived: '已归档',
    };
    return (
      <span
        className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${colors[status as keyof typeof colors] || colors.draft}`}
      >
        {labels[status as keyof typeof labels] || status}
      </span>
    );
  };

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{dict.name}</h1>
          <p className="mt-1 text-sm text-gray-500">
            字典编码: <span className="font-mono text-blue-600">{dict.code}</span>
            {dict.description && ` · ${dict.description}`}
          </p>
        </div>

        <div className="flex gap-2">
          {dict.status === 'draft' && (
            <button
              onClick={handlePublish}
              className="rounded-md bg-green-600 px-4 py-2 text-white hover:bg-green-700"
              disabled={loading}
            >
              发布
            </button>
          )}
          {dict.status === 'published' && (
            <button
              onClick={handleUnpublish}
              className="rounded-md bg-yellow-600 px-4 py-2 text-white hover:bg-yellow-700"
              disabled={loading}
            >
              取消发布
            </button>
          )}
          <button
            onClick={handleEdit}
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
            disabled={loading}
          >
            编辑
          </button>
          <button
            onClick={handleDelete}
            className="rounded-md border border-red-300 px-4 py-2 text-red-700 hover:bg-red-50"
            disabled={loading}
          >
            删除
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="rounded-lg bg-white shadow">
        <div className="border-b border-gray-200">
          <nav className="-mb-px flex">
            <a
              href={location.pathname}
              data-testid="dict-tab-basic"
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'basic'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              基本信息
            </a>
            <a
              href={`${location.pathname}#items`}
              data-testid="dict-tab-items"
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'items'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              字典项 ({orderedItems.length})
            </a>
            <a
              href={`${location.pathname}#versions`}
              data-testid="dict-tab-versions"
              className={`border-b-2 px-6 py-3 text-sm font-medium ${
                activeTab === 'versions'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              版本历史 ({versions.length})
            </a>
          </nav>
        </div>

        {/* Tab Content */}
        <div className="p-6">
          {/* Basic Info Tab */}
          {activeTab === 'basic' && (
            <div className="space-y-6">
              <div className="grid grid-cols-2 gap-6">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">字典编码</label>
                  <div className="font-mono text-sm text-gray-900">{dict.code}</div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">字典名称</label>
                  <div className="text-sm text-gray-900">{dict.name}</div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">字典类型</label>
                  <div className="text-sm text-gray-900">{getTypeLabel(dict.dictType)}</div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">状态</label>
                  <div className="text-sm">{getStatusBadge(dict.status)}</div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">版本号</label>
                  <div className="font-mono text-sm text-gray-900">v{dict.version}</div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    是否当前版本
                  </label>
                  <div className="text-sm text-gray-900">{dict.isCurrent ? '是' : '否'}</div>
                </div>
                <div className="col-span-2">
                  <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
                  <div className="text-sm text-gray-900">{dict.description || '-'}</div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">创建时间</label>
                  <div className="text-sm text-gray-900">
                    {formatDisplayDateTime(dict.createdAt, userTimezone)}
                  </div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">更新时间</label>
                  <div className="text-sm text-gray-900">
                    {formatDisplayDateTime(dict.updatedAt, userTimezone)}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Items Tab */}
          {activeTab === 'items' && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <p className="text-sm text-gray-500">
                  支持新增、删除、改父级与同级排序；TREE 字典可新增子节点。
                </p>
                <div className="flex gap-2">
                  <button
                    type="button"
                    data-testid="dict-add-root-item"
                    onClick={handleAddRootItem}
                    className="rounded-md bg-gray-100 px-3 py-2 text-sm text-gray-700 hover:bg-gray-200"
                  >
                    新增根节点
                  </button>
                  <button
                    type="button"
                    data-testid="dict-save-items"
                    onClick={handleSaveItems}
                    disabled={savingItems || !hasItemChanges}
                    className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
                  >
                    {savingItems ? '保存中...' : '保存字典项'}
                  </button>
                </div>
              </div>

              {rowItems.length === 0 ? (
                <div className="rounded-md border border-dashed border-gray-300 py-12 text-center text-gray-500">
                  暂无字典项，请新增根节点
                </div>
              ) : (
                <div className="overflow-x-auto rounded-md border border-gray-200">
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          值
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          标签
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          父级
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          顺序
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                          操作
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200 bg-white">
                      {rowItems.map(({ item, depth }, index) => {
                        const canAddChild = dict.dictType !== 'simple' && !!item.value?.trim();
                        return (
                          <tr key={item._id} data-testid={`dict-item-row-${index}`}>
                            <td className="px-4 py-3 text-sm">
                              <div className="flex items-center gap-2">
                                <span
                                  className="text-gray-400"
                                  style={{ paddingLeft: `${depth * 16}px` }}
                                >
                                  {depth > 0 ? '└' : '•'}
                                </span>
                                <input
                                  type="text"
                                  value={item.value || ''}
                                  onChange={(e) =>
                                    handleItemFieldChange(item._id, 'value', e.target.value)
                                  }
                                  placeholder="value"
                                  className="w-full min-w-[180px] rounded border border-gray-300 px-2 py-1 font-mono text-sm"
                                />
                              </div>
                            </td>
                            <td className="px-4 py-3 text-sm">
                              <input
                                type="text"
                                value={item.label || ''}
                                onChange={(e) =>
                                  handleItemFieldChange(item._id, 'label', e.target.value)
                                }
                                placeholder="label"
                                className="w-full min-w-[180px] rounded border border-gray-300 px-2 py-1 text-sm"
                              />
                            </td>
                            <td className="px-4 py-3 text-sm">
                              <select
                                value={item.parentValue || ''}
                                disabled={dict.dictType === 'simple'}
                                onChange={(e) =>
                                  handleItemFieldChange(
                                    item._id,
                                    'parentValue',
                                    e.target.value || undefined,
                                  )
                                }
                                className="w-full min-w-[150px] rounded border border-gray-300 px-2 py-1 text-sm disabled:bg-gray-100"
                              >
                                <option value="">(根节点)</option>
                                {orderedItems
                                  .filter(
                                    (option) => option._id !== item._id && option.value?.trim(),
                                  )
                                  .map((option) => (
                                    <option key={option._id} value={option.value}>
                                      {option.value}
                                    </option>
                                  ))}
                              </select>
                            </td>
                            <td className="px-4 py-3 text-sm text-gray-500">{item.order ?? 0}</td>
                            <td className="px-4 py-3 text-sm">
                              <div className="flex flex-wrap items-center gap-2">
                                <button
                                  type="button"
                                  onClick={() => handleMoveSibling(item._id, 'up')}
                                  className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-50"
                                >
                                  ↑
                                </button>
                                <button
                                  type="button"
                                  onClick={() => handleMoveSibling(item._id, 'down')}
                                  className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-50"
                                >
                                  ↓
                                </button>
                                {canAddChild && (
                                  <button
                                    type="button"
                                    data-testid={`dict-add-child-${index}`}
                                    onClick={() => handleAddChildItem(item)}
                                    className="rounded border border-blue-200 px-2 py-1 text-blue-700 hover:bg-blue-50"
                                  >
                                    新增子节点
                                  </button>
                                )}
                                <button
                                  type="button"
                                  onClick={() => handleRemoveItem(item._id)}
                                  className="rounded border border-red-200 px-2 py-1 text-red-700 hover:bg-red-50"
                                >
                                  删除
                                </button>
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* Versions Tab */}
          {activeTab === 'versions' && (
            <div>
              {versions.length === 0 ? (
                <div className="py-12 text-center text-gray-500">暂无版本历史</div>
              ) : (
                <div className="space-y-4">
                  {versions.map((version) => (
                    <div key={version.version} className="rounded-lg border border-gray-200 p-4">
                      <div className="flex items-start justify-between">
                        <div>
                          <div className="flex items-center gap-2">
                            <h3 className="text-sm font-medium text-gray-900">
                              版本 {version.version}
                            </h3>
                            {version.isCurrent && (
                              <span className="inline-flex rounded bg-green-100 px-2 py-1 text-xs font-medium text-green-800">
                                当前版本
                              </span>
                            )}
                            {getStatusBadge(version.status)}
                          </div>
                          <p className="mt-1 text-xs text-gray-400">
                            {formatDisplayDateTime(version.createdAt, userTimezone)}
                          </p>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
