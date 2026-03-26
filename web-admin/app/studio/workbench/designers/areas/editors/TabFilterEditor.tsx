import React, { useState, useCallback } from 'react';

interface LocalizedText {
  'en-US'?: string;
  'zh-CN'?: string;
}

interface TabFilterExpression {
  field: string;
  operator: 'EQ' | 'NE' | 'IN' | 'not_in';
  value: any;
}

interface ListTabConfig {
  key: string;
  label: string | LocalizedText;
  filter: TabFilterExpression | null;
}

export interface TabFilterEditorProps {
  tabs: ListTabConfig[];
  onChange: (tabs: ListTabConfig[]) => void;
  readonly?: boolean;
}

function getLabel(label: string | LocalizedText, lang: 'en-US' | 'zh-CN'): string {
  if (typeof label === 'string') return label;
  return label[lang] || label['en-US'] || '';
}

function getDisplayLabel(label: string | LocalizedText): string {
  return getLabel(label, 'en-US') || getLabel(label, 'zh-CN') || '(untitled)';
}

export function TabFilterEditor({ tabs, onChange, readonly }: TabFilterEditorProps) {
  const [selectedIndex, setSelectedIndex] = useState(0);

  const updateTab = useCallback(
    (index: number, updates: Partial<ListTabConfig>) => {
      const updated = [...tabs];
      updated[index] = { ...updated[index], ...updates };
      onChange(updated);
    },
    [tabs, onChange]
  );

  const deleteTab = useCallback(
    (index: number) => {
      const updated = tabs.filter((_, i) => i !== index);
      onChange(updated);
      setSelectedIndex(Math.min(selectedIndex, updated.length - 1));
    },
    [tabs, onChange, selectedIndex]
  );

  const addTab = useCallback(() => {
    const newTab: ListTabConfig = {
      key: `tab_${Date.now()}`,
      label: { 'en-US': 'New Tab', 'zh-CN': '新标签' },
      filter: null,
    };
    onChange([...tabs, newTab]);
    setSelectedIndex(tabs.length);
  }, [tabs, onChange]);

  const updateLabel = useCallback(
    (index: number, lang: 'en-US' | 'zh-CN', value: string) => {
      const tab = tabs[index];
      const currentLabel =
        typeof tab.label === 'string' ? { 'en-US': tab.label } : { ...tab.label };
      currentLabel[lang] = value;
      updateTab(index, { label: currentLabel as LocalizedText });
    },
    [tabs, updateTab]
  );

  const updateFilter = useCallback(
    (index: number, filter: TabFilterExpression | null) => {
      updateTab(index, { filter });
    },
    [updateTab]
  );

  const currentTab = tabs[selectedIndex];

  return (
    <div className="space-y-3" data-testid="tab-filter-editor">
      <div className="text-xs font-medium text-gray-600">List Tabs</div>

      {/* Mini tab bar preview */}
      <div className="flex items-end gap-0 border-b-2 border-gray-200">
        {tabs.map((tab, index) => (
          <button
            key={tab.key}
            className={`px-2.5 py-1 text-xs transition-colors ${
              selectedIndex === index
                ? 'border-b-2 border-blue-500 font-semibold text-blue-600'
                : 'border-b-2 border-transparent text-gray-400 hover:text-gray-600'
            }`}
            onClick={() => setSelectedIndex(index)}
            data-testid={`tab-${tab.key}`}
          >
            {getDisplayLabel(tab.label)}
          </button>
        ))}
        <button
          className="px-2 py-1 text-xs text-blue-500 hover:text-blue-700"
          onClick={addTab}
          disabled={readonly}
        >
          +
        </button>
      </div>

      {/* Selected tab editor */}
      {currentTab && (
        <div className="space-y-2 text-xs">
          {/* Key */}
          <div>
            <label className="text-[10px] font-semibold text-gray-500">Key</label>
            <input
              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
              value={currentTab.key}
              onChange={(e) => updateTab(selectedIndex, { key: e.target.value })}
              disabled={readonly}
            />
          </div>

          {/* Labels */}
          <div className="grid grid-cols-2 gap-1.5">
            <div>
              <label className="text-[10px] font-semibold text-gray-500">Label (en-US)</label>
              <input
                className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                value={getLabel(currentTab.label, 'en-US')}
                onChange={(e) => updateLabel(selectedIndex, 'en-US', e.target.value)}
                disabled={readonly}
              />
            </div>
            <div>
              <label className="text-[10px] font-semibold text-gray-500">Label (zh-CN)</label>
              <input
                className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                value={getLabel(currentTab.label, 'zh-CN')}
                onChange={(e) => updateLabel(selectedIndex, 'zh-CN', e.target.value)}
                disabled={readonly}
              />
            </div>
          </div>

          {/* Filter condition */}
          <div>
            <label className="text-[10px] font-semibold text-gray-500">Filter Condition</label>
            <FilterConditionEditor
              filter={currentTab.filter}
              onChange={(f) => updateFilter(selectedIndex, f)}
              readonly={readonly}
            />
          </div>

          {/* Footer: reorder + delete */}
          <div className="flex items-center justify-between pt-1">
            {/* Reorder buttons */}
            <div className="flex items-center gap-1">
              <span className="text-[10px] text-gray-400">Order:</span>
              <button
                onClick={() => {
                  if (selectedIndex > 0) {
                    const updated = [...tabs];
                    [updated[selectedIndex - 1], updated[selectedIndex]] = [updated[selectedIndex], updated[selectedIndex - 1]];
                    onChange(updated);
                    setSelectedIndex(selectedIndex - 1);
                  }
                }}
                className="rounded border px-1.5 py-0.5 text-[10px] text-gray-500 hover:bg-gray-100 disabled:opacity-30"
                disabled={readonly || selectedIndex === 0}
                title="Move left"
              >
                ◀
              </button>
              <button
                onClick={() => {
                  if (selectedIndex < tabs.length - 1) {
                    const updated = [...tabs];
                    [updated[selectedIndex], updated[selectedIndex + 1]] = [updated[selectedIndex + 1], updated[selectedIndex]];
                    onChange(updated);
                    setSelectedIndex(selectedIndex + 1);
                  }
                }}
                className="rounded border px-1.5 py-0.5 text-[10px] text-gray-500 hover:bg-gray-100 disabled:opacity-30"
                disabled={readonly || selectedIndex === tabs.length - 1}
                title="Move right"
              >
                ▶
              </button>
            </div>

            <button
              onClick={() => deleteTab(selectedIndex)}
              className="rounded border border-red-200 px-2 py-0.5 text-[10px] text-red-500 hover:bg-red-50"
              disabled={readonly || tabs.length <= 1}
            >
              Delete Tab
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function FilterConditionEditor({
  filter,
  onChange,
  readonly,
}: {
  filter: TabFilterExpression | null;
  onChange: (filter: TabFilterExpression | null) => void;
  readonly?: boolean;
}) {
  if (!filter) {
    return (
      <div className="mt-1 rounded border border-dashed bg-gray-50 px-2 py-2 text-center text-[10px] text-gray-400">
        No filter (shows all records)
        <br />
        <button
          className="mt-1 text-blue-500"
          onClick={() => onChange({ field: '', operator: 'EQ', value: '' })}
          disabled={readonly}
        >
          + Add filter
        </button>
      </div>
    );
  }

  return (
    <div className="mt-1 rounded border bg-gray-50 p-1.5">
      <div className="flex items-center gap-1">
        <input
          className="flex-1 rounded border px-1 py-0.5 font-mono text-xs"
          value={filter.field}
          onChange={(e) => onChange({ ...filter, field: e.target.value })}
          placeholder="field"
          disabled={readonly}
        />
        <select
          className="rounded border bg-white px-1 py-0.5 text-xs"
          value={filter.operator}
          onChange={(e) =>
            onChange({
              ...filter,
              operator: e.target.value as TabFilterExpression['operator'],
            })
          }
          disabled={readonly}
        >
          <option value="EQ">EQ</option>
          <option value="NE">NE</option>
          <option value="IN">IN</option>
          <option value="not_in">NOT_IN</option>
        </select>
        <input
          className="flex-1 rounded border px-1 py-0.5 font-mono text-xs"
          value={typeof filter.value === 'string' ? filter.value : JSON.stringify(filter.value)}
          onChange={(e) => onChange({ ...filter, value: e.target.value })}
          placeholder="value"
          disabled={readonly}
        />
        <button
          onClick={() => onChange(null)}
          className="text-red-400 hover:text-red-600"
          disabled={readonly}
        >
          ×
        </button>
      </div>
    </div>
  );
}
