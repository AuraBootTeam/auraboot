import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TabFilterEditor } from '../TabFilterEditor';

interface TestTab {
  key: string;
  label: string | { 'en-US'?: string; 'zh-CN'?: string };
  filter: null;
  blocks?: Array<Record<string, any>>;
}

function StatefulTabFilterEditor({
  initialTabs,
  onCommit,
  activeLibraryBlockType,
  onDropLibraryBlockToTab,
}: {
  initialTabs: TestTab[];
  onCommit: (tabs: TestTab[]) => void;
  activeLibraryBlockType?: string | null;
  onDropLibraryBlockToTab?: (parentBlockId: string, tabKey: string, blockType: string) => void;
}) {
  const [tabs, setTabs] = React.useState<TestTab[]>(initialTabs);

  return (
    <>
      <TabFilterEditor
        tabs={tabs as any}
        blockId="tabs_block"
        activeLibraryBlockType={activeLibraryBlockType}
        onDropLibraryBlockToTab={onDropLibraryBlockToTab as any}
        onChange={(nextTabs) => {
          const typedTabs = nextTabs as TestTab[];
          setTabs(typedTabs);
          onCommit(typedTabs);
        }}
      />
      <pre data-testid="serialized-tabs">{JSON.stringify(tabs)}</pre>
    </>
  );
}

function serializedTabs(): TestTab[] {
  return JSON.parse(screen.getByTestId('serialized-tabs').textContent || '[]');
}

describe('TabFilterEditor', () => {
  it('authors text child blocks on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'overview',
            label: { 'en-US': 'Overview', 'zh-CN': '概览' },
            filter: null,
            blocks: [],
          },
          {
            key: 'history',
            label: { 'en-US': 'History', 'zh-CN': '历史' },
            filter: null,
            blocks: [
              {
                id: 'existing_history_text',
                blockType: 'text',
                title: { 'en-US': 'History text', 'zh-CN': '历史文本' },
                props: { content: 'Existing history copy' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-blocks-editor')).toBeVisible();
    expect(screen.getByTestId('tab-child-blocks-editor')).toHaveAttribute(
      'data-drop-id',
      'tab-child-drop:tabs_block:overview',
    );
    expect(screen.getByTestId('tab-child-drop-zone')).toBeVisible();

    fireEvent.click(screen.getByTestId('tab-child-add-text-block'));

    const afterAdd = serializedTabs();
    expect(afterAdd[0].blocks).toHaveLength(1);
    expect(afterAdd[0].blocks?.[0]).toMatchObject({
      id: expect.stringMatching(/^tab_text_/),
      blockType: 'text',
      title: { 'en-US': 'Text', 'zh-CN': '文本内容' },
      props: { content: '' },
    });
    expect(afterAdd[1].blocks?.[0]?.props?.content).toBe('Existing history copy');

    fireEvent.change(screen.getByTestId('tab-child-text-content-0'), {
      target: { value: 'Nested overview copy' },
    });
    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'text',
      props: { content: 'Nested overview copy' },
    });

    fireEvent.click(screen.getByTestId('tab-history'));
    expect(screen.getByTestId('tab-child-text-content-0')).toHaveValue('Existing history copy');

    fireEvent.click(screen.getByTestId('tab-overview'));
    fireEvent.click(screen.getByTestId('tab-child-remove-0'));
    expect(serializedTabs()[0].blocks).toEqual([]);
  });

  it('edits localized titles for non-text child blocks on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'overview',
            label: { 'en-US': 'Overview', 'zh-CN': '概览' },
            filter: null,
            blocks: [
              {
                id: 'overview_stat',
                blockType: 'stat-card',
                title: { 'en-US': 'Metric', 'zh-CN': '指标' },
                props: { valueField: 'name' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-block-0')).toBeVisible();
    expect(screen.getByTestId('tab-child-title-en-input-0')).toHaveValue('Metric');
    expect(screen.getByTestId('tab-child-title-zh-input-0')).toHaveValue('指标');

    fireEvent.change(screen.getByTestId('tab-child-title-en-input-0'), {
      target: { value: 'Nested metric' },
    });
    fireEvent.change(screen.getByTestId('tab-child-title-zh-input-0'), {
      target: { value: '嵌套指标' },
    });

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'stat-card',
      title: { 'en-US': 'Nested metric', 'zh-CN': '嵌套指标' },
      props: { valueField: 'name' },
    });
  });

  it('reorders child blocks on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'overview',
            label: { 'en-US': 'Overview', 'zh-CN': '概览' },
            filter: null,
            blocks: [
              {
                id: 'first_text',
                blockType: 'text',
                title: { 'en-US': 'First text', 'zh-CN': '第一文本' },
                props: { content: 'First child copy' },
              },
              {
                id: 'second_text',
                blockType: 'text',
                title: { 'en-US': 'Second text', 'zh-CN': '第二文本' },
                props: { content: 'Second child copy' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-text-content-0')).toHaveValue('First child copy');
    expect(screen.getByTestId('tab-child-text-content-1')).toHaveValue('Second child copy');

    fireEvent.click(screen.getByTestId('tab-child-move-down-0'));
    expect(serializedTabs()[0].blocks?.map((block) => block.id)).toEqual([
      'second_text',
      'first_text',
    ]);
    expect(screen.getByTestId('tab-child-text-content-0')).toHaveValue('Second child copy');
    expect(screen.getByTestId('tab-child-text-content-1')).toHaveValue('First child copy');

    fireEvent.click(screen.getByTestId('tab-child-move-up-1'));
    expect(serializedTabs()[0].blocks?.map((block) => block.id)).toEqual([
      'first_text',
      'second_text',
    ]);
  });

  it('edits stat child block data settings on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'metrics',
            label: { 'en-US': 'Metrics', 'zh-CN': '指标' },
            filter: null,
            blocks: [
              {
                id: 'metrics_stat',
                blockType: 'stat-card',
                title: { 'en-US': 'Metric', 'zh-CN': '指标' },
                dataSource: 'old_stats',
                props: { valueField: 'oldCount' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-stat-data-source-input-0')).toHaveValue('old_stats');
    expect(screen.getByTestId('tab-child-stat-value-field-input-0')).toHaveValue('oldCount');

    fireEvent.change(screen.getByTestId('tab-child-stat-data-source-input-0'), {
      target: { value: 'nested_stats' },
    });
    fireEvent.change(screen.getByTestId('tab-child-stat-value-field-input-0'), {
      target: { value: 'totalCount' },
    });

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'stat-card',
      dataSource: 'nested_stats',
      props: { valueField: 'totalCount' },
    });
  });

  it('edits stat child block appearance and refresh settings on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'metrics',
            label: { 'en-US': 'Metrics', 'zh-CN': '指标' },
            filter: null,
            blocks: [
              {
                id: 'metrics_stat',
                blockType: 'stat-card',
                title: { 'en-US': 'Metric', 'zh-CN': '指标' },
                dataSource: 'old_stats',
                refreshInterval: 1000,
                props: {
                  valueField: 'oldCount',
                  changeField: 'oldDelta',
                  prefix: '$',
                  suffix: 'items',
                  color: 'blue',
                },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-stat-suffix-input-0')).toHaveValue('items');
    expect(screen.getByTestId('tab-child-stat-prefix-input-0')).toHaveValue('$');
    expect(screen.getByTestId('tab-child-stat-change-field-input-0')).toHaveValue('oldDelta');
    expect(screen.getByTestId('tab-child-stat-color-select-0')).toHaveValue('blue');
    expect(screen.getByTestId('tab-child-stat-refresh-interval-input-0')).toHaveValue(1000);

    fireEvent.change(screen.getByTestId('tab-child-stat-prefix-input-0'), {
      target: { value: 'USD ' },
    });
    fireEvent.change(screen.getByTestId('tab-child-stat-change-field-input-0'), {
      target: { value: 'deltaRate' },
    });
    fireEvent.change(screen.getByTestId('tab-child-stat-suffix-input-0'), {
      target: { value: 'records' },
    });
    fireEvent.change(screen.getByTestId('tab-child-stat-color-select-0'), {
      target: { value: 'green' },
    });
    fireEvent.change(screen.getByTestId('tab-child-stat-refresh-interval-input-0'), {
      target: { value: '1500' },
    });

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'stat-card',
      refreshInterval: 1500,
      props: {
        valueField: 'oldCount',
        changeField: 'deltaRate',
        prefix: 'USD ',
        suffix: 'records',
        color: 'green',
      },
    });
  });

  it('edits chart child block data settings on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'charts',
            label: { 'en-US': 'Charts', 'zh-CN': '图表' },
            filter: null,
            blocks: [
              {
                id: 'charts_chart',
                blockType: 'chart-card',
                title: { 'en-US': 'Chart', 'zh-CN': '图表' },
                dataSource: 'old_chart_ds',
                props: { chartType: 'bar', xField: 'oldCategory', yField: 'oldValue' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-chart-data-source-input-0')).toHaveValue(
      'old_chart_ds',
    );
    expect(screen.getByTestId('tab-child-chart-type-select-0')).toHaveValue('bar');
    expect(screen.getByTestId('tab-child-chart-x-field-input-0')).toHaveValue('oldCategory');
    expect(screen.getByTestId('tab-child-chart-y-field-input-0')).toHaveValue('oldValue');

    fireEvent.change(screen.getByTestId('tab-child-chart-data-source-input-0'), {
      target: { value: 'nested_chart_ds' },
    });
    fireEvent.change(screen.getByTestId('tab-child-chart-type-select-0'), {
      target: { value: 'line' },
    });
    fireEvent.change(screen.getByTestId('tab-child-chart-x-field-input-0'), {
      target: { value: 'category' },
    });
    fireEvent.change(screen.getByTestId('tab-child-chart-y-field-input-0'), {
      target: { value: 'amount' },
    });

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'chart-card',
      dataSource: 'nested_chart_ds',
      props: { chartType: 'line', xField: 'category', yField: 'amount' },
    });
  });

  it('edits chart child block appearance and refresh settings on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'charts',
            label: { 'en-US': 'Charts', 'zh-CN': '图表' },
            filter: null,
            blocks: [
              {
                id: 'charts_chart',
                blockType: 'chart-card',
                title: { 'en-US': 'Chart', 'zh-CN': '图表' },
                dataSource: 'old_chart_ds',
                refreshInterval: 1000,
                props: {
                  chartType: 'bar',
                  xField: 'oldCategory',
                  yField: 'oldValue',
                  smooth: false,
                  showLegend: false,
                  height: 180,
                },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-chart-refresh-interval-input-0')).toHaveValue(1000);
    expect(screen.getByTestId('tab-child-chart-smooth-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    expect(screen.getByTestId('tab-child-chart-legend-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    expect(screen.getByTestId('tab-child-chart-height-input-0')).toHaveValue(180);

    fireEvent.change(screen.getByTestId('tab-child-chart-refresh-interval-input-0'), {
      target: { value: '1500' },
    });
    fireEvent.click(screen.getByTestId('tab-child-chart-smooth-switch-0'));
    fireEvent.click(screen.getByTestId('tab-child-chart-legend-switch-0'));
    fireEvent.change(screen.getByTestId('tab-child-chart-height-input-0'), {
      target: { value: '240' },
    });

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'chart-card',
      refreshInterval: 1500,
      props: {
        chartType: 'bar',
        xField: 'oldCategory',
        yField: 'oldValue',
        smooth: true,
        showLegend: true,
        height: 240,
      },
    });
  });

  it('edits custom child block runtime props on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'custom',
            label: { 'en-US': 'Custom', 'zh-CN': '自定义' },
            filter: null,
            blocks: [
              {
                id: 'custom_child',
                blockType: 'custom',
                title: { 'en-US': 'Custom child', 'zh-CN': '自定义子块' },
                component: 'legacy-runtime-block',
                props: {
                  initialCurrentDataType: 'string',
                  valueField: 'oldPid',
                },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-custom-component-input-0')).toHaveValue(
      'legacy-runtime-block',
    );
    expect(screen.getByTestId('tab-child-custom-value-field-input-0')).toHaveValue('oldPid');
    expect(screen.getByTestId('tab-child-custom-props-json-input-0')).toHaveValue(
      JSON.stringify(
        {
          initialCurrentDataType: 'string',
          valueField: 'oldPid',
        },
        null,
        2,
      ),
    );

    fireEvent.change(screen.getByTestId('tab-child-custom-component-input-0'), {
      target: { value: 'decision-field-impact' },
    });
    fireEvent.change(screen.getByTestId('tab-child-custom-props-json-input-0'), {
      target: {
        value: JSON.stringify(
          {
            initialCurrentDataType: 'number',
            tone: 'critical',
          },
          null,
          2,
        ),
      },
    });
    fireEvent.change(screen.getByTestId('tab-child-custom-value-field-input-0'), {
      target: { value: 'pid' },
    });

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'custom',
      component: 'decision-field-impact',
      props: {
        initialCurrentDataType: 'number',
        tone: 'critical',
        valueField: 'pid',
      },
    });
  });

  it('edits detail section child block layout and behavior on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'details',
            label: { 'en-US': 'Details', 'zh-CN': '详情' },
            filter: null,
            blocks: [
              {
                id: 'details_section',
                blockType: 'detail-section',
                title: { 'en-US': 'Detail child', 'zh-CN': '详情子块' },
                visible: '{{ record.active }}',
                span: 12,
                props: { columns: 2, gutter: 16 },
                collapsible: false,
                defaultCollapsed: false,
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-visible-input-0')).toHaveValue('{{ record.active }}');
    expect(screen.getByTestId('tab-child-span-select-0')).toHaveValue('12');
    expect(screen.getByTestId('tab-child-section-columns-select-0')).toHaveValue('2');
    expect(screen.getByTestId('tab-child-section-gutter-select-0')).toHaveValue('16');
    expect(screen.getByTestId('tab-child-section-collapsible-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );

    fireEvent.change(screen.getByTestId('tab-child-visible-input-0'), {
      target: { value: '{{ record.status == "OPEN" }}' },
    });
    fireEvent.change(screen.getByTestId('tab-child-span-select-0'), {
      target: { value: '6' },
    });
    fireEvent.change(screen.getByTestId('tab-child-section-columns-select-0'), {
      target: { value: '3' },
    });
    fireEvent.change(screen.getByTestId('tab-child-section-gutter-select-0'), {
      target: { value: '24' },
    });
    fireEvent.click(screen.getByTestId('tab-child-section-collapsible-switch-0'));
    fireEvent.click(screen.getByTestId('tab-child-section-default-collapsed-switch-0'));

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'detail-section',
      visible: '{{ record.status == "OPEN" }}',
      span: 6,
      props: { columns: 3, gutter: 24 },
      collapsible: true,
      defaultCollapsed: true,
    });
  });

  it('edits form buttons child block layout on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'actions',
            label: { 'en-US': 'Actions', 'zh-CN': '操作' },
            filter: null,
            blocks: [
              {
                id: 'actions_buttons',
                blockType: 'form-buttons',
                title: { 'en-US': 'Footer actions', 'zh-CN': '底部操作' },
                visible: '{{ canEdit }}',
                span: 12,
                buttons: [],
                props: { align: 'left' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-visible-input-0')).toHaveValue('{{ canEdit }}');
    expect(screen.getByTestId('tab-child-span-select-0')).toHaveValue('12');
    expect(screen.getByTestId('tab-child-button-align-select-0')).toHaveValue('left');

    fireEvent.change(screen.getByTestId('tab-child-visible-input-0'), {
      target: { value: '{{ record.status != "CLOSED" }}' },
    });
    fireEvent.change(screen.getByTestId('tab-child-span-select-0'), {
      target: { value: '8' },
    });
    fireEvent.change(screen.getByTestId('tab-child-button-align-select-0'), {
      target: { value: 'right' },
    });

    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'form-buttons',
      visible: '{{ record.status != "CLOSED" }}',
      span: 8,
      buttons: [],
      props: { align: 'right' },
    });
  });

  it('edits form buttons child block button actions on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'actions',
            label: { 'en-US': 'Actions', 'zh-CN': '操作' },
            filter: null,
            blocks: [
              {
                id: 'actions_buttons',
                blockType: 'form-buttons',
                title: { 'en-US': 'Footer actions', 'zh-CN': '底部操作' },
                buttons: [],
                props: { align: 'left' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    fireEvent.click(screen.getByTestId('tab-child-button-add-0'));

    const addedButton = serializedTabs()[0].blocks?.[0].buttons?.[0];
    expect(addedButton).toMatchObject({
      code: expect.stringMatching(/^action_/),
      label: { 'en-US': 'Action', 'zh-CN': '操作' },
      action: { type: 'command' },
    });

    fireEvent.change(screen.getByTestId('tab-child-button-code-input-0-0'), {
      target: { value: 'submit_review' },
    });
    fireEvent.change(screen.getByTestId('tab-child-button-label-en-input-0-0'), {
      target: { value: 'Submit review' },
    });
    fireEvent.change(screen.getByTestId('tab-child-button-label-zh-input-0-0'), {
      target: { value: '提交审核' },
    });
    fireEvent.change(screen.getByTestId('tab-child-button-action-command-input-0-0'), {
      target: { value: 'pgm:update_page_schema' },
    });
    fireEvent.click(screen.getByTestId('tab-child-button-primary-checkbox-0-0'));
    fireEvent.click(screen.getByTestId('tab-child-button-danger-checkbox-0-0'));

    expect(serializedTabs()[0].blocks?.[0].buttons?.[0]).toMatchObject({
      code: 'submit_review',
      label: { 'en-US': 'Submit review', 'zh-CN': '提交审核' },
      action: { type: 'command', command: 'pgm:update_page_schema' },
      primary: true,
      danger: true,
    });
  });

  it('merges rapid button block edits before the parent rerenders', () => {
    const onChange = vi.fn();
    render(
      <TabFilterEditor
        tabs={
          [
            {
              key: 'actions',
              label: { 'en-US': 'Actions', 'zh-CN': '操作' },
              filter: null,
              blocks: [
                {
                  id: 'actions_buttons',
                  blockType: 'form-buttons',
                  title: { 'en-US': 'Footer actions', 'zh-CN': '底部操作' },
                  buttons: [],
                  props: { align: 'left' },
                },
              ],
            },
          ] as any
        }
        blockId="tabs_block"
        onChange={onChange}
      />,
    );

    fireEvent.change(screen.getByTestId('tab-child-button-align-select-0'), {
      target: { value: 'right' },
    });
    fireEvent.click(screen.getByTestId('tab-child-button-add-0'));

    const latestTabs = onChange.mock.calls.at(-1)?.[0] as TestTab[];
    expect(latestTabs[0].blocks?.[0]).toMatchObject({
      blockType: 'form-buttons',
      props: { align: 'right' },
      buttons: [
        {
          code: expect.stringMatching(/^action_/),
          label: { 'en-US': 'Action', 'zh-CN': '操作' },
          action: { type: 'command' },
        },
      ],
    });
  });

  it('does not treat child button editor pointer-up as a tab child drop', () => {
    const onCommit = vi.fn();
    const onDropLibraryBlockToTab = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'actions',
            label: { 'en-US': 'Actions', 'zh-CN': '操作' },
            filter: null,
            blocks: [
              {
                id: 'actions_buttons',
                blockType: 'form-buttons',
                title: { 'en-US': 'Footer actions', 'zh-CN': '底部操作' },
                buttons: [],
                props: { align: 'left' },
              },
            ],
          },
        ]}
        activeLibraryBlockType="detail-section"
        onDropLibraryBlockToTab={onDropLibraryBlockToTab}
        onCommit={onCommit}
      />,
    );

    fireEvent.pointerUp(screen.getByTestId('tab-child-button-add-0'));
    fireEvent.click(screen.getByTestId('tab-child-button-add-0'));

    expect(onDropLibraryBlockToTab).not.toHaveBeenCalled();
    expect(serializedTabs()[0].blocks?.[0].buttons).toHaveLength(1);
  });

  it('does not treat pointer-up from the child block editor card as a tab child drop', () => {
    const onCommit = vi.fn();
    const onDropLibraryBlockToTab = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'actions',
            label: { 'en-US': 'Actions', 'zh-CN': '操作' },
            filter: null,
            blocks: [
              {
                id: 'actions_buttons',
                blockType: 'form-buttons',
                title: { 'en-US': 'Footer actions', 'zh-CN': '底部操作' },
                buttons: [],
                props: { align: 'left' },
              },
            ],
          },
        ]}
        activeLibraryBlockType="detail-section"
        onDropLibraryBlockToTab={onDropLibraryBlockToTab}
        onCommit={onCommit}
      />,
    );

    fireEvent.pointerUp(screen.getByTestId('tab-child-block-0'));

    expect(onDropLibraryBlockToTab).not.toHaveBeenCalled();
  });
});
