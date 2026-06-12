import React, { useState, useCallback, useEffect, useRef } from 'react';
import { useDroppable } from '@dnd-kit/core';

interface LocalizedText {
  'en-US'?: string;
  'zh-CN'?: string;
}

interface TabFilterExpression {
  field: string;
  operator: 'EQ' | 'NE' | 'IN' | 'not_in';
  value: any;
}

interface TabChildBlock {
  id: string;
  blockType: string;
  title?: string | LocalizedText;
  props?: Record<string, any>;
  [key: string]: any;
}

interface ListTabConfig {
  key: string;
  label: string | LocalizedText;
  filter: TabFilterExpression | null;
  blocks?: TabChildBlock[];
}

export interface TabFilterEditorProps {
  tabs: ListTabConfig[];
  onChange: (tabs: ListTabConfig[]) => void;
  blockId?: string;
  activeLibraryBlockType?: string | null;
  onDropLibraryBlockToTab?: (parentBlockId: string, tabKey: string, blockType: any) => void;
  readonly?: boolean;
}

function getLabel(label: string | LocalizedText, lang: 'en-US' | 'zh-CN'): string {
  if (typeof label === 'string') return label;
  return label[lang] || label['en-US'] || '';
}

function getDisplayLabel(label: string | LocalizedText): string {
  return getLabel(label, 'en-US') || getLabel(label, 'zh-CN') || '(untitled)';
}

function getTabBlocks(tab: ListTabConfig | undefined): TabChildBlock[] {
  return Array.isArray(tab?.blocks) ? tab.blocks : [];
}

function getChildBlockTitle(block: TabChildBlock, lang: 'en-US' | 'zh-CN'): string {
  if (typeof block.title === 'string') return lang === 'en-US' ? block.title : '';
  return block.title?.[lang] || '';
}

function formatChildBlockProps(props: Record<string, any> | undefined): string {
  try {
    return JSON.stringify(props || {}, null, 2);
  } catch {
    return '{}';
  }
}

function createTextChildBlock(index: number): TabChildBlock {
  return {
    id: `tab_text_${Date.now()}_${index}`,
    blockType: 'text',
    title: { 'en-US': 'Text', 'zh-CN': '文本内容' },
    props: { content: '' },
  };
}

function createButtonChildBlockButton(index: number): Record<string, any> {
  return {
    code: `action_${Date.now()}_${index}`,
    label: { 'en-US': 'Action', 'zh-CN': '操作' },
    action: { type: 'command' },
  };
}

function getChildButtonLabel(button: Record<string, any>, lang: 'en-US' | 'zh-CN'): string {
  const label = button.label;
  if (typeof label === 'string') return lang === 'en-US' ? label : '';
  if (!label || typeof label !== 'object') return '';
  return label[lang] || (lang === 'en-US' ? label.en : '') || '';
}

function getChildButtonAction(button: Record<string, any>): Record<string, any> {
  return button.action && typeof button.action === 'object' && !Array.isArray(button.action)
    ? button.action
    : { type: typeof button.action === 'string' ? button.action : 'command' };
}

function getPointerEventElement(target: EventTarget | null): Element | null {
  if (target instanceof Element) return target;
  if (target instanceof Node) return target.parentElement;
  return null;
}

export function TabFilterEditor({
  tabs,
  onChange,
  blockId,
  activeLibraryBlockType,
  onDropLibraryBlockToTab,
  readonly,
}: TabFilterEditorProps) {
  const [selectedIndex, setSelectedIndex] = useState(0);
  const tabsRef = useRef(tabs);
  const suppressNextButtonClickRef = useRef(false);

  useEffect(() => {
    tabsRef.current = tabs;
  }, [tabs]);

  const emitTabsChange = useCallback(
    (nextTabs: ListTabConfig[]) => {
      tabsRef.current = nextTabs;
      onChange(nextTabs);
    },
    [onChange],
  );

  const updateTab = useCallback(
    (index: number, updates: Partial<ListTabConfig>) => {
      const currentTabs = tabsRef.current;
      if (!currentTabs[index]) return;
      const updated = [...currentTabs];
      updated[index] = { ...updated[index], ...updates };
      emitTabsChange(updated);
    },
    [emitTabsChange],
  );

  const deleteTab = useCallback(
    (index: number) => {
      const updated = tabsRef.current.filter((_, i) => i !== index);
      emitTabsChange(updated);
      setSelectedIndex(Math.min(selectedIndex, updated.length - 1));
    },
    [emitTabsChange, selectedIndex],
  );

  const addTab = useCallback(() => {
    const currentTabs = tabsRef.current;
    const newTab: ListTabConfig = {
      key: `tab_${Date.now()}`,
      label: { 'en-US': 'New Tab', 'zh-CN': '新标签' },
      filter: null,
    };
    emitTabsChange([...currentTabs, newTab]);
    setSelectedIndex(currentTabs.length);
  }, [emitTabsChange]);

  const updateLabel = useCallback(
    (index: number, lang: 'en-US' | 'zh-CN', value: string) => {
      const tab = tabsRef.current[index];
      if (!tab) return;
      const currentLabel =
        typeof tab.label === 'string' ? { 'en-US': tab.label } : { ...tab.label };
      currentLabel[lang] = value;
      updateTab(index, { label: currentLabel as LocalizedText });
    },
    [updateTab],
  );

  const updateFilter = useCallback(
    (index: number, filter: TabFilterExpression | null) => {
      updateTab(index, { filter });
    },
    [updateTab],
  );

  const addTextChildBlock = useCallback(
    (index: number) => {
      const childBlocks = getTabBlocks(tabsRef.current[index]);
      updateTab(index, { blocks: [...childBlocks, createTextChildBlock(childBlocks.length)] });
    },
    [updateTab],
  );

  const updateChildBlock = useCallback(
    (tabIndex: number, blockIndex: number, updates: Partial<TabChildBlock>) => {
      const childBlocks = getTabBlocks(tabsRef.current[tabIndex]);
      const nextBlocks = childBlocks.map((block, index) =>
        index === blockIndex ? { ...block, ...updates } : block,
      );
      updateTab(tabIndex, { blocks: nextBlocks });
    },
    [updateTab],
  );

  const updateChildBlockTitle = useCallback(
    (tabIndex: number, blockIndex: number, lang: 'en-US' | 'zh-CN', value: string) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      const currentTitle =
        typeof childBlock.title === 'string'
          ? { 'en-US': childBlock.title }
          : { ...(childBlock.title || {}) };
      updateChildBlock(tabIndex, blockIndex, {
        title: { ...currentTitle, [lang]: value },
      });
    },
    [updateChildBlock],
  );

  const updateTextChildBlock = useCallback(
    (tabIndex: number, blockIndex: number, content: string) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      updateChildBlock(tabIndex, blockIndex, {
        props: { ...(childBlock.props || {}), content },
      });
    },
    [updateChildBlock],
  );

  const updateChildBlockVisible = useCallback(
    (tabIndex: number, blockIndex: number, visible: string) => {
      updateChildBlock(tabIndex, blockIndex, { visible: visible || undefined });
    },
    [updateChildBlock],
  );

  const updateChildBlockSpan = useCallback(
    (tabIndex: number, blockIndex: number, span: string) => {
      updateChildBlock(tabIndex, blockIndex, {
        span: span ? Number(span) : undefined,
      });
    },
    [updateChildBlock],
  );

  const updateSectionChildBlockProp = useCallback(
    (tabIndex: number, blockIndex: number, propKey: string, value: any) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      updateChildBlock(tabIndex, blockIndex, {
        props: { ...(childBlock.props || {}), [propKey]: value },
      });
    },
    [updateChildBlock],
  );

  const updateSectionChildBlockCollapsible = useCallback(
    (tabIndex: number, blockIndex: number, collapsible: boolean) => {
      updateChildBlock(tabIndex, blockIndex, {
        collapsible,
        defaultCollapsed: collapsible ? undefined : false,
      });
    },
    [updateChildBlock],
  );

  const updateSectionChildBlockDefaultCollapsed = useCallback(
    (tabIndex: number, blockIndex: number, defaultCollapsed: boolean) => {
      updateChildBlock(tabIndex, blockIndex, { defaultCollapsed });
    },
    [updateChildBlock],
  );

  const updateButtonChildBlockAlign = useCallback(
    (tabIndex: number, blockIndex: number, align: string) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      updateChildBlock(tabIndex, blockIndex, {
        props: { ...(childBlock.props || {}), align },
      });
    },
    [updateChildBlock],
  );

  const addButtonChildBlockButton = useCallback(
    (tabIndex: number, blockIndex: number) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      const buttons = Array.isArray(childBlock.buttons) ? childBlock.buttons : [];
      updateChildBlock(tabIndex, blockIndex, {
        buttons: [...buttons, createButtonChildBlockButton(buttons.length)],
      });
    },
    [updateChildBlock],
  );

  const updateButtonChildBlockButton = useCallback(
    (
      tabIndex: number,
      blockIndex: number,
      buttonIndex: number,
      updates: Record<string, any>,
    ) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      const buttons = Array.isArray(childBlock.buttons) ? childBlock.buttons : [];
      const nextButtons = buttons.map((button, index) =>
        index === buttonIndex ? { ...button, ...updates } : button,
      );
      updateChildBlock(tabIndex, blockIndex, { buttons: nextButtons });
    },
    [updateChildBlock],
  );

  const updateButtonChildBlockButtonLabel = useCallback(
    (
      tabIndex: number,
      blockIndex: number,
      buttonIndex: number,
      lang: 'en-US' | 'zh-CN',
      value: string,
    ) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      const button = Array.isArray(childBlock?.buttons)
        ? childBlock?.buttons?.[buttonIndex]
        : undefined;
      if (!button) return;
      const currentLabel =
        typeof button.label === 'string'
          ? { 'en-US': button.label }
          : { ...(button.label || {}) };
      updateButtonChildBlockButton(tabIndex, blockIndex, buttonIndex, {
        label: { ...currentLabel, [lang]: value },
      });
    },
    [updateButtonChildBlockButton],
  );

  const updateButtonChildBlockButtonAction = useCallback(
    (
      tabIndex: number,
      blockIndex: number,
      buttonIndex: number,
      updates: Record<string, any>,
    ) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      const button = Array.isArray(childBlock?.buttons)
        ? childBlock?.buttons?.[buttonIndex]
        : undefined;
      if (!button) return;
      const action = { ...getChildButtonAction(button), ...updates };
      updateButtonChildBlockButton(tabIndex, blockIndex, buttonIndex, { action });
    },
    [updateButtonChildBlockButton],
  );

  const updateButtonChildBlockButtonActionType = useCallback(
    (tabIndex: number, blockIndex: number, buttonIndex: number, type: string) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      const button = Array.isArray(childBlock?.buttons)
        ? childBlock?.buttons?.[buttonIndex]
        : undefined;
      if (!button) return;
      const action: Record<string, any> = {
        ...getChildButtonAction(button),
        type: type || 'command',
      };
      if (action.type !== 'command') delete action.command;
      if (action.type !== 'navigate') delete action.url;
      updateButtonChildBlockButton(tabIndex, blockIndex, buttonIndex, { action });
    },
    [updateButtonChildBlockButton],
  );

  const removeButtonChildBlockButton = useCallback(
    (tabIndex: number, blockIndex: number, buttonIndex: number) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      const buttons = Array.isArray(childBlock.buttons) ? childBlock.buttons : [];
      updateChildBlock(tabIndex, blockIndex, {
        buttons: buttons.filter((_, index) => index !== buttonIndex),
      });
    },
    [updateChildBlock],
  );

  const updateStatChildBlockDataSource = useCallback(
    (tabIndex: number, blockIndex: number, dataSource: string) => {
      updateChildBlock(tabIndex, blockIndex, { dataSource: dataSource || undefined });
    },
    [updateChildBlock],
  );

  const updateStatChildBlockValueField = useCallback(
    (tabIndex: number, blockIndex: number, valueField: string) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      updateChildBlock(tabIndex, blockIndex, {
        props: { ...(childBlock.props || {}), valueField },
      });
    },
    [updateChildBlock],
  );

  const updateStatChildBlockProp = useCallback(
    (tabIndex: number, blockIndex: number, propKey: string, value: any) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      updateChildBlock(tabIndex, blockIndex, {
        props: { ...(childBlock.props || {}), [propKey]: value },
      });
    },
    [updateChildBlock],
  );

  const updateChartChildBlockDataSource = useCallback(
    (tabIndex: number, blockIndex: number, dataSource: string) => {
      updateChildBlock(tabIndex, blockIndex, { dataSource: dataSource || undefined });
    },
    [updateChildBlock],
  );

  const updateChartChildBlockProp = useCallback(
    (tabIndex: number, blockIndex: number, propKey: string, value: any) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      updateChildBlock(tabIndex, blockIndex, {
        props: { ...(childBlock.props || {}), [propKey]: value },
      });
    },
    [updateChildBlock],
  );

  const updateCustomChildBlockComponent = useCallback(
    (tabIndex: number, blockIndex: number, component: string) => {
      updateChildBlock(tabIndex, blockIndex, { component: component || undefined });
    },
    [updateChildBlock],
  );

  const updateCustomChildBlockValueField = useCallback(
    (tabIndex: number, blockIndex: number, valueField: string) => {
      const childBlock = getTabBlocks(tabsRef.current[tabIndex])[blockIndex];
      if (!childBlock) return;
      updateChildBlock(tabIndex, blockIndex, {
        props: {
          ...(childBlock.props || {}),
          valueField: valueField || undefined,
        },
      });
    },
    [updateChildBlock],
  );

  const updateCustomChildBlockProps = useCallback(
    (tabIndex: number, blockIndex: number, propsJson: string) => {
      try {
        const parsed = propsJson.trim() ? JSON.parse(propsJson) : {};
        if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
          return;
        }
        updateChildBlock(tabIndex, blockIndex, { props: parsed });
      } catch {
        return;
      }
    },
    [updateChildBlock],
  );

  const updateChildBlockRefreshInterval = useCallback(
    (tabIndex: number, blockIndex: number, value: string) => {
      const next = Number(value);
      updateChildBlock(tabIndex, blockIndex, {
        refreshInterval: Number.isFinite(next) && next > 0 ? next : undefined,
      });
    },
    [updateChildBlock],
  );

  const removeChildBlock = useCallback(
    (tabIndex: number, blockIndex: number) => {
      const nextBlocks = getTabBlocks(tabsRef.current[tabIndex]).filter(
        (_, index) => index !== blockIndex,
      );
      updateTab(tabIndex, { blocks: nextBlocks });
    },
    [updateTab],
  );

  const moveChildBlock = useCallback(
    (tabIndex: number, blockIndex: number, direction: -1 | 1) => {
      const childBlocks = getTabBlocks(tabsRef.current[tabIndex]);
      const nextIndex = blockIndex + direction;
      if (nextIndex < 0 || nextIndex >= childBlocks.length) return;
      const nextBlocks = [...childBlocks];
      [nextBlocks[blockIndex], nextBlocks[nextIndex]] = [
        nextBlocks[nextIndex],
        nextBlocks[blockIndex],
      ];
      updateTab(tabIndex, { blocks: nextBlocks });
    },
    [updateTab],
  );

  const currentTab = tabs[selectedIndex];
  const currentTabBlocks = getTabBlocks(currentTab);
  const currentTabDropId =
    blockId && currentTab?.key
      ? `tab-child-drop:${blockId}:${encodeURIComponent(currentTab.key)}`
      : 'tab-child-drop:unknown';
  const { setNodeRef: setChildDropRef, isOver: isChildDropOver } = useDroppable({
    id: currentTabDropId,
    disabled: readonly || !blockId || !currentTab,
  });
  const stopChildBlockEditorPointer = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    event.stopPropagation();
  }, []);

  const handleChildDropPointerUp = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const target = getPointerEventElement(event.target);
    if (target?.closest('button,input,select,textarea,label')) return;
    if (readonly || !blockId || !currentTab || !activeLibraryBlockType) return;
    onDropLibraryBlockToTab?.(blockId, currentTab.key, activeLibraryBlockType);
  }, [
    activeLibraryBlockType,
    blockId,
    currentTab,
    onDropLibraryBlockToTab,
    readonly,
  ]);

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
          data-testid="tab-filter-add-tab"
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
              data-testid="tab-filter-key-input"
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
                data-testid="tab-filter-label-en-input"
              />
            </div>
            <div>
              <label className="text-[10px] font-semibold text-gray-500">Label (zh-CN)</label>
              <input
                className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                value={getLabel(currentTab.label, 'zh-CN')}
                onChange={(e) => updateLabel(selectedIndex, 'zh-CN', e.target.value)}
                disabled={readonly}
                data-testid="tab-filter-label-zh-input"
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

          {/* Child blocks */}
          <div>
            <label className="text-[10px] font-semibold text-gray-500">Child Blocks</label>
            <div
              ref={setChildDropRef}
              onPointerUp={handleChildDropPointerUp}
              className={`mt-1 space-y-1.5 rounded border p-1.5 transition-colors ${
                isChildDropOver ? 'border-blue-400 bg-blue-50' : 'bg-gray-50'
              }`}
              data-testid="tab-child-blocks-editor"
              data-drop-id={currentTabDropId}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-[10px] text-gray-400">
                  {currentTabBlocks.length} child block{currentTabBlocks.length === 1 ? '' : 's'}
                </span>
                <button
                  type="button"
                  onClick={() => addTextChildBlock(selectedIndex)}
                  className="rounded border border-blue-200 px-2 py-0.5 text-[10px] text-blue-600 hover:bg-blue-50 disabled:opacity-40"
                  disabled={readonly}
                  data-testid="tab-child-add-text-block"
                >
                  + Text
                </button>
              </div>

              <div
                className={`rounded border border-dashed px-2 py-2 text-center text-[10px] transition-colors ${
                  isChildDropOver
                    ? 'border-blue-400 bg-blue-50 text-blue-600'
                    : 'border-gray-200 bg-white text-gray-400'
                }`}
                data-testid="tab-child-drop-zone"
              >
                {isChildDropOver
                  ? 'Release to add block'
                  : 'Drag a block from the library into this tab'}
              </div>

              {currentTabBlocks.length === 0 ? (
                <div className="rounded border border-dashed bg-white px-2 py-2 text-center text-[10px] text-gray-400">
                  No child blocks
                </div>
              ) : (
                currentTabBlocks.map((block, index) => (
                  <div
                    key={block.id || index}
                    className="rounded border bg-white p-1.5"
                    onPointerDown={stopChildBlockEditorPointer}
                    onPointerUp={stopChildBlockEditorPointer}
                    data-testid={`tab-child-block-${index}`}
                  >
                    <div className="mb-1 flex items-center justify-between gap-2">
                      <span className="font-mono text-[10px] text-gray-500">
                        {block.blockType}
                      </span>
                      <div className="flex items-center gap-1">
                        <button
                          type="button"
                          onClick={() => moveChildBlock(selectedIndex, index, -1)}
                          className="rounded border px-1 py-0.5 text-[10px] text-gray-500 hover:bg-gray-100 disabled:opacity-30"
                          disabled={readonly || index === 0}
                          data-testid={`tab-child-move-up-${index}`}
                        >
                          Up
                        </button>
                        <button
                          type="button"
                          onClick={() => moveChildBlock(selectedIndex, index, 1)}
                          className="rounded border px-1 py-0.5 text-[10px] text-gray-500 hover:bg-gray-100 disabled:opacity-30"
                          disabled={readonly || index === currentTabBlocks.length - 1}
                          data-testid={`tab-child-move-down-${index}`}
                        >
                          Down
                        </button>
                        <button
                          type="button"
                          onClick={() => removeChildBlock(selectedIndex, index)}
                          className="text-[10px] text-red-500 hover:text-red-700 disabled:opacity-40"
                          disabled={readonly}
                          data-testid={`tab-child-remove-${index}`}
                        >
                          Remove
                        </button>
                      </div>
                    </div>
                    <div className="mb-1.5 grid grid-cols-2 gap-1.5">
                      <label className="text-[10px] text-gray-500">
                        Title (en-US)
                        <input
                          className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                          value={getChildBlockTitle(block, 'en-US')}
                          onChange={(event) =>
                            updateChildBlockTitle(
                              selectedIndex,
                              index,
                              'en-US',
                              event.target.value,
                            )
                          }
                          disabled={readonly}
                          data-testid={`tab-child-title-en-input-${index}`}
                        />
                      </label>
                      <label className="text-[10px] text-gray-500">
                        Title (zh-CN)
                        <input
                          className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                          value={getChildBlockTitle(block, 'zh-CN')}
                          onChange={(event) =>
                            updateChildBlockTitle(
                              selectedIndex,
                              index,
                              'zh-CN',
                              event.target.value,
                            )
                          }
                          disabled={readonly}
                          data-testid={`tab-child-title-zh-input-${index}`}
                        />
                      </label>
                    </div>
                    <div className="mb-1.5 grid grid-cols-2 gap-1.5">
                      <label className="text-[10px] text-gray-500">
                        Visibility condition
                        <input
                          className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                          value={String(block.visible || '')}
                          onChange={(event) =>
                            updateChildBlockVisible(selectedIndex, index, event.target.value)
                          }
                          disabled={readonly}
                          placeholder="{{ true }}"
                          data-testid={`tab-child-visible-input-${index}`}
                        />
                      </label>
                      <label className="text-[10px] text-gray-500">
                        Grid span
                        <select
                          className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                          value={block.span || ''}
                          onChange={(event) =>
                            updateChildBlockSpan(selectedIndex, index, event.target.value)
                          }
                          disabled={readonly}
                          data-testid={`tab-child-span-select-${index}`}
                        >
                          <option value="">Auto</option>
                          {[1, 2, 3, 4, 6, 8, 12].map((span) => (
                            <option key={span} value={span}>
                              {span} columns
                            </option>
                          ))}
                        </select>
                      </label>
                    </div>
                    {(block.blockType === 'form-section' ||
                      block.blockType === 'detail-section') && (
                      <div className="mb-1.5 space-y-1.5">
                        <div className="grid grid-cols-2 gap-1.5">
                          <label className="text-[10px] text-gray-500">
                            Columns
                            <select
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={Number(block.props?.columns ?? 2)}
                              onChange={(event) =>
                                updateSectionChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'columns',
                                  Number(event.target.value),
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-section-columns-select-${index}`}
                            >
                              {[1, 2, 3, 4].map((columns) => (
                                <option key={columns} value={columns}>
                                  {columns} columns
                                </option>
                              ))}
                            </select>
                          </label>
                          <label className="text-[10px] text-gray-500">
                            Gutter
                            <select
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={Number(block.props?.gutter ?? 16)}
                              onChange={(event) =>
                                updateSectionChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'gutter',
                                  Number(event.target.value),
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-section-gutter-select-${index}`}
                            >
                              {[8, 16, 24, 32].map((gutter) => (
                                <option key={gutter} value={gutter}>
                                  {gutter}px
                                </option>
                              ))}
                            </select>
                          </label>
                        </div>
                        <div className="grid grid-cols-2 gap-1.5">
                          <div className="flex items-center justify-between gap-2 rounded border px-1.5 py-1">
                            <span className="text-[10px] text-gray-500">Collapsible</span>
                            <button
                              type="button"
                              role="switch"
                              aria-checked={Boolean(block.collapsible ?? false)}
                              onClick={() =>
                                updateSectionChildBlockCollapsible(
                                  selectedIndex,
                                  index,
                                  !Boolean(block.collapsible ?? false),
                                )
                              }
                              disabled={readonly}
                              className={`h-4 w-8 rounded-full text-[8px] ${
                                block.collapsible ?? false
                                  ? 'bg-blue-500 text-white'
                                  : 'bg-gray-200 text-gray-500'
                              } disabled:opacity-40`}
                              data-testid={`tab-child-section-collapsible-switch-${index}`}
                            >
                              {block.collapsible ?? false ? 'On' : 'Off'}
                            </button>
                          </div>
                          {block.collapsible && (
                            <div className="flex items-center justify-between gap-2 rounded border px-1.5 py-1">
                              <span className="text-[10px] text-gray-500">Default collapsed</span>
                              <button
                                type="button"
                                role="switch"
                                aria-checked={Boolean(block.defaultCollapsed ?? false)}
                                onClick={() =>
                                  updateSectionChildBlockDefaultCollapsed(
                                    selectedIndex,
                                    index,
                                    !Boolean(block.defaultCollapsed ?? false),
                                  )
                                }
                                disabled={readonly}
                                className={`h-4 w-8 rounded-full text-[8px] ${
                                  block.defaultCollapsed ?? false
                                    ? 'bg-blue-500 text-white'
                                    : 'bg-gray-200 text-gray-500'
                                } disabled:opacity-40`}
                                data-testid={`tab-child-section-default-collapsed-switch-${index}`}
                              >
                                {block.defaultCollapsed ?? false ? 'On' : 'Off'}
                              </button>
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                    {(block.blockType === 'toolbar' || block.blockType === 'form-buttons') && (
                      <div className="mb-1.5 space-y-1.5">
                        <label className="block text-[10px] text-gray-500">
                          Button alignment
                          <select
                            className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                            value={String(block.props?.align || 'left')}
                            onChange={(event) =>
                              updateButtonChildBlockAlign(
                                selectedIndex,
                                index,
                                event.target.value,
                              )
                            }
                            disabled={readonly}
                            data-testid={`tab-child-button-align-select-${index}`}
                          >
                            <option value="left">Left</option>
                            <option value="center">Center</option>
                            <option value="right">Right</option>
                          </select>
                        </label>
                        <div
                          className="rounded border border-gray-100 bg-gray-50 p-1.5"
                          data-testid={`tab-child-button-actions-${index}`}
                        >
                          <div className="mb-1 flex items-center justify-between gap-2">
                            <span className="text-[10px] font-semibold text-gray-500">
                              Button actions
                            </span>
                            <button
                              type="button"
                              onPointerUp={(event) => {
                                event.stopPropagation();
                                if (readonly || event.button !== 0) return;
                                suppressNextButtonClickRef.current = true;
                                window.setTimeout(() => {
                                  suppressNextButtonClickRef.current = false;
                                }, 0);
                                addButtonChildBlockButton(selectedIndex, index);
                              }}
                              onClick={(event) => {
                                event.stopPropagation();
                                if (suppressNextButtonClickRef.current) {
                                  suppressNextButtonClickRef.current = false;
                                  return;
                                }
                                addButtonChildBlockButton(selectedIndex, index);
                              }}
                              disabled={readonly}
                              className="rounded border border-blue-200 px-2 py-0.5 text-[10px] text-blue-600 hover:bg-blue-50 disabled:opacity-40"
                              data-testid={`tab-child-button-add-${index}`}
                            >
                              + Button
                            </button>
                          </div>
                          {Array.isArray(block.buttons) && block.buttons.length > 0 ? (
                            <div className="space-y-1.5">
                              {block.buttons.map((button: Record<string, any>, buttonIndex) => {
                                const action = getChildButtonAction(button);
                                return (
                                  <div
                                    key={`${button.code || 'button'}-${buttonIndex}`}
                                    className="space-y-1.5 rounded border bg-white p-1.5"
                                    data-testid={`tab-child-button-${index}-${buttonIndex}`}
                                  >
                                    <div className="flex items-center justify-between gap-2">
                                      <span className="font-mono text-[10px] text-gray-500">
                                        {button.code || `button_${buttonIndex + 1}`}
                                      </span>
                                      <button
                                        type="button"
                                        className="text-[10px] text-red-500 hover:text-red-700 disabled:opacity-40"
                                        onClick={() =>
                                          removeButtonChildBlockButton(
                                            selectedIndex,
                                            index,
                                            buttonIndex,
                                          )
                                        }
                                        disabled={readonly}
                                        data-testid={`tab-child-button-remove-${index}-${buttonIndex}`}
                                      >
                                        Remove
                                      </button>
                                    </div>
                                    <div className="grid grid-cols-2 gap-1.5">
                                      <label className="text-[10px] text-gray-500">
                                        Code
                                        <input
                                          className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                                          value={String(button.code || '')}
                                          onChange={(event) =>
                                            updateButtonChildBlockButton(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              {
                                                code: event.target.value || undefined,
                                              },
                                            )
                                          }
                                          disabled={readonly}
                                          data-testid={`tab-child-button-code-input-${index}-${buttonIndex}`}
                                        />
                                      </label>
                                      <label className="text-[10px] text-gray-500">
                                        Action type
                                        <select
                                          className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                                          value={String(action.type || 'command')}
                                          onChange={(event) =>
                                            updateButtonChildBlockButtonActionType(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              event.target.value,
                                            )
                                          }
                                          disabled={readonly}
                                          data-testid={`tab-child-button-action-type-select-${index}-${buttonIndex}`}
                                        >
                                          <option value="command">Command</option>
                                          <option value="navigate">Navigate</option>
                                          <option value="submit">Submit</option>
                                          <option value="cancel">Cancel</option>
                                          <option value="close">Close</option>
                                        </select>
                                      </label>
                                    </div>
                                    <div className="grid grid-cols-2 gap-1.5">
                                      <label className="text-[10px] text-gray-500">
                                        Label (en-US)
                                        <input
                                          className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                                          value={getChildButtonLabel(button, 'en-US')}
                                          onChange={(event) =>
                                            updateButtonChildBlockButtonLabel(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              'en-US',
                                              event.target.value,
                                            )
                                          }
                                          disabled={readonly}
                                          data-testid={`tab-child-button-label-en-input-${index}-${buttonIndex}`}
                                        />
                                      </label>
                                      <label className="text-[10px] text-gray-500">
                                        Label (zh-CN)
                                        <input
                                          className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                                          value={getChildButtonLabel(button, 'zh-CN')}
                                          onChange={(event) =>
                                            updateButtonChildBlockButtonLabel(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              'zh-CN',
                                              event.target.value,
                                            )
                                          }
                                          disabled={readonly}
                                          data-testid={`tab-child-button-label-zh-input-${index}-${buttonIndex}`}
                                        />
                                      </label>
                                    </div>
                                    {String(action.type || 'command') === 'command' && (
                                      <label className="block text-[10px] text-gray-500">
                                        Command
                                        <input
                                          className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                                          value={String(action.command || '')}
                                          onChange={(event) =>
                                            updateButtonChildBlockButtonAction(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              { command: event.target.value || undefined },
                                            )
                                          }
                                          disabled={readonly}
                                          placeholder="pgm:update_page_schema"
                                          data-testid={`tab-child-button-action-command-input-${index}-${buttonIndex}`}
                                        />
                                      </label>
                                    )}
                                    {String(action.type) === 'navigate' && (
                                      <label className="block text-[10px] text-gray-500">
                                        URL
                                        <input
                                          className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                                          value={String(action.url || '')}
                                          onChange={(event) =>
                                            updateButtonChildBlockButtonAction(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              { url: event.target.value || undefined },
                                            )
                                          }
                                          disabled={readonly}
                                          placeholder="/p/page_schema"
                                          data-testid={`tab-child-button-action-url-input-${index}-${buttonIndex}`}
                                        />
                                      </label>
                                    )}
                                    <div className="grid grid-cols-2 gap-1.5">
                                      <label className="flex items-center gap-1 text-[10px] text-gray-500">
                                        <input
                                          type="checkbox"
                                          checked={Boolean(button.primary)}
                                          onChange={(event) =>
                                            updateButtonChildBlockButton(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              {
                                                primary: event.target.checked || undefined,
                                              },
                                            )
                                          }
                                          disabled={readonly}
                                          data-testid={`tab-child-button-primary-checkbox-${index}-${buttonIndex}`}
                                        />
                                        Primary
                                      </label>
                                      <label className="flex items-center gap-1 text-[10px] text-red-600">
                                        <input
                                          type="checkbox"
                                          checked={Boolean(button.danger)}
                                          onChange={(event) =>
                                            updateButtonChildBlockButton(
                                              selectedIndex,
                                              index,
                                              buttonIndex,
                                              {
                                                danger: event.target.checked || undefined,
                                              },
                                            )
                                          }
                                          disabled={readonly}
                                          data-testid={`tab-child-button-danger-checkbox-${index}-${buttonIndex}`}
                                        />
                                        Danger
                                      </label>
                                    </div>
                                  </div>
                                );
                              })}
                            </div>
                          ) : (
                            <div className="rounded border border-dashed bg-white px-2 py-2 text-center text-[10px] text-gray-400">
                              No configured buttons
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                    {block.blockType === 'stat-card' && (
                      <div className="mb-1.5 space-y-1.5">
                        <div className="grid grid-cols-2 gap-1.5">
                          <label className="text-[10px] text-gray-500">
                            Data source
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                              value={typeof block.dataSource === 'string' ? block.dataSource : ''}
                              onChange={(event) =>
                                updateStatChildBlockDataSource(
                                  selectedIndex,
                                  index,
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-stat-data-source-input-${index}`}
                            />
                          </label>
                          <label className="text-[10px] text-gray-500">
                            Value field
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                              value={String(block.props?.valueField || '')}
                              onChange={(event) =>
                                updateStatChildBlockValueField(
                                  selectedIndex,
                                  index,
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-stat-value-field-input-${index}`}
                            />
                          </label>
                        </div>
                        <div className="grid grid-cols-2 gap-1.5">
                          <label className="text-[10px] text-gray-500">
                            Change field
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                              value={String(block.props?.changeField || '')}
                              onChange={(event) =>
                                updateStatChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'changeField',
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-stat-change-field-input-${index}`}
                            />
                          </label>
                          <label className="text-[10px] text-gray-500">
                            Prefix
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={String(block.props?.prefix || '')}
                              onChange={(event) =>
                                updateStatChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'prefix',
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-stat-prefix-input-${index}`}
                            />
                          </label>
                        </div>
                        <div className="grid grid-cols-2 gap-1.5">
                          <label className="text-[10px] text-gray-500">
                            Suffix
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={String(block.props?.suffix || '')}
                              onChange={(event) =>
                                updateStatChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'suffix',
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-stat-suffix-input-${index}`}
                            />
                          </label>
                          <label className="text-[10px] text-gray-500">
                            Color
                            <select
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={String(block.props?.color || 'blue')}
                              onChange={(event) =>
                                updateStatChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'color',
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-stat-color-select-${index}`}
                            >
                              <option value="blue">Blue</option>
                              <option value="green">Green</option>
                              <option value="orange">Orange</option>
                              <option value="red">Red</option>
                              <option value="purple">Purple</option>
                            </select>
                          </label>
                        </div>
                        <label className="block text-[10px] text-gray-500">
                          Refresh interval (ms)
                          <input
                            type="number"
                            className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                            value={
                              typeof block.refreshInterval === 'number'
                                ? block.refreshInterval
                                : ''
                            }
                            onChange={(event) =>
                              updateChildBlockRefreshInterval(
                                selectedIndex,
                                index,
                                event.target.value,
                              )
                            }
                            disabled={readonly}
                            min={0}
                            step={500}
                            data-testid={`tab-child-stat-refresh-interval-input-${index}`}
                          />
                        </label>
                      </div>
                    )}
                    {block.blockType === 'chart-card' && (
                      <div className="mb-1.5 space-y-1.5">
                        <div className="grid grid-cols-2 gap-1.5">
                          <label className="text-[10px] text-gray-500">
                            Data source
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                              value={typeof block.dataSource === 'string' ? block.dataSource : ''}
                              onChange={(event) =>
                                updateChartChildBlockDataSource(
                                  selectedIndex,
                                  index,
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-chart-data-source-input-${index}`}
                            />
                          </label>
                          <label className="text-[10px] text-gray-500">
                            Chart type
                            <select
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={String(block.props?.chartType || 'bar')}
                              onChange={(event) =>
                                updateChartChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'chartType',
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-chart-type-select-${index}`}
                            >
                              <option value="bar">Bar</option>
                              <option value="line">Line</option>
                              <option value="pie">Pie</option>
                              <option value="area">Area</option>
                            </select>
                          </label>
                        </div>
                        <div className="grid grid-cols-2 gap-1.5">
                          <label className="text-[10px] text-gray-500">
                            X field
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                              value={String(block.props?.xField || '')}
                              onChange={(event) =>
                                updateChartChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'xField',
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-chart-x-field-input-${index}`}
                            />
                          </label>
                          <label className="text-[10px] text-gray-500">
                            Y field
                            <input
                              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                              value={String(block.props?.yField || '')}
                              onChange={(event) =>
                                updateChartChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'yField',
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              data-testid={`tab-child-chart-y-field-input-${index}`}
                            />
                          </label>
                        </div>
                        <div className="grid grid-cols-2 gap-1.5">
                          <label className="text-[10px] text-gray-500">
                            Refresh interval (ms)
                            <input
                              type="number"
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={
                                typeof block.refreshInterval === 'number'
                                  ? block.refreshInterval
                                  : ''
                              }
                              onChange={(event) =>
                                updateChildBlockRefreshInterval(
                                  selectedIndex,
                                  index,
                                  event.target.value,
                                )
                              }
                              disabled={readonly}
                              min={0}
                              step={500}
                              data-testid={`tab-child-chart-refresh-interval-input-${index}`}
                            />
                          </label>
                          <label className="text-[10px] text-gray-500">
                            Height
                            <input
                              type="number"
                              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
                              value={Number(block.props?.height ?? 200)}
                              onChange={(event) =>
                                updateChartChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'height',
                                  Number(event.target.value),
                                )
                              }
                              disabled={readonly}
                              min={100}
                              max={600}
                              step={20}
                              data-testid={`tab-child-chart-height-input-${index}`}
                            />
                          </label>
                        </div>
                        <div className="grid grid-cols-2 gap-1.5">
                          <div className="flex items-center justify-between gap-2 rounded border px-1.5 py-1">
                            <span className="text-[10px] text-gray-500">Smooth</span>
                            <button
                              type="button"
                              role="switch"
                              aria-checked={Boolean(block.props?.smooth ?? true)}
                              onClick={() =>
                                updateChartChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'smooth',
                                  !Boolean(block.props?.smooth ?? true),
                                )
                              }
                              disabled={readonly}
                              className={`h-4 w-8 rounded-full text-[8px] ${
                                block.props?.smooth ?? true
                                  ? 'bg-blue-500 text-white'
                                  : 'bg-gray-200 text-gray-500'
                              } disabled:opacity-40`}
                              data-testid={`tab-child-chart-smooth-switch-${index}`}
                            >
                              {block.props?.smooth ?? true ? 'On' : 'Off'}
                            </button>
                          </div>
                          <div className="flex items-center justify-between gap-2 rounded border px-1.5 py-1">
                            <span className="text-[10px] text-gray-500">Legend</span>
                            <button
                              type="button"
                              role="switch"
                              aria-checked={Boolean(block.props?.showLegend ?? true)}
                              onClick={() =>
                                updateChartChildBlockProp(
                                  selectedIndex,
                                  index,
                                  'showLegend',
                                  !Boolean(block.props?.showLegend ?? true),
                                )
                              }
                              disabled={readonly}
                              className={`h-4 w-8 rounded-full text-[8px] ${
                                block.props?.showLegend ?? true
                                  ? 'bg-blue-500 text-white'
                                  : 'bg-gray-200 text-gray-500'
                              } disabled:opacity-40`}
                              data-testid={`tab-child-chart-legend-switch-${index}`}
                            >
                              {block.props?.showLegend ?? true ? 'On' : 'Off'}
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
                    {block.blockType === 'custom' && (
                      <div className="mb-1.5 space-y-1.5">
                        <label className="block text-[10px] text-gray-500">
                          Component
                          <input
                            className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                            value={String(block.component || '')}
                            onChange={(event) =>
                              updateCustomChildBlockComponent(
                                selectedIndex,
                                index,
                                event.target.value,
                              )
                            }
                            disabled={readonly}
                            placeholder="decision-field-impact"
                            data-testid={`tab-child-custom-component-input-${index}`}
                          />
                        </label>
                        <label className="block text-[10px] text-gray-500">
                          Value field
                          <input
                            className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                            value={String(block.props?.valueField || '')}
                            onChange={(event) =>
                              updateCustomChildBlockValueField(
                                selectedIndex,
                                index,
                                event.target.value,
                              )
                            }
                            disabled={readonly}
                            placeholder="pid"
                            data-testid={`tab-child-custom-value-field-input-${index}`}
                          />
                        </label>
                        <label className="block text-[10px] text-gray-500">
                          Props JSON
                          <textarea
                            className="mt-0.5 min-h-24 w-full resize-y rounded border px-1.5 py-1 font-mono text-xs"
                            value={formatChildBlockProps(block.props)}
                            onChange={(event) =>
                              updateCustomChildBlockProps(
                                selectedIndex,
                                index,
                                event.target.value,
                              )
                            }
                            disabled={readonly}
                            data-testid={`tab-child-custom-props-json-input-${index}`}
                          />
                        </label>
                      </div>
                    )}
                    {block.blockType === 'text' ? (
                      <textarea
                        className="min-h-16 w-full resize-y rounded border px-1.5 py-1 text-xs"
                        value={String(block.props?.content || '')}
                        onChange={(event) =>
                          updateTextChildBlock(selectedIndex, index, event.target.value)
                        }
                        disabled={readonly}
                        data-testid={`tab-child-text-content-${index}`}
                      />
                    ) : block.blockType === 'stat-card' ||
                      block.blockType === 'chart-card' ||
                      block.blockType === 'custom' ||
                      block.blockType === 'form-section' ||
                      block.blockType === 'detail-section' ||
                      block.blockType === 'toolbar' ||
                      block.blockType === 'form-buttons' ? null : (
                      <div className="rounded border border-dashed px-2 py-2 text-[10px] text-gray-400">
                        This block type is preserved but not editable here.
                      </div>
                    )}
                  </div>
                ))
              )}
            </div>
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
                    [updated[selectedIndex - 1], updated[selectedIndex]] = [
                      updated[selectedIndex],
                      updated[selectedIndex - 1],
                    ];
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
                    [updated[selectedIndex], updated[selectedIndex + 1]] = [
                      updated[selectedIndex + 1],
                      updated[selectedIndex],
                    ];
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
          data-testid="tab-filter-add-condition"
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
          data-testid="tab-filter-field-input"
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
          data-testid="tab-filter-operator-select"
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
          data-testid="tab-filter-value-input"
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
