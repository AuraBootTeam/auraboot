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
}: {
  initialTabs: TestTab[];
  onCommit: (tabs: TestTab[]) => void;
}) {
  const [tabs, setTabs] = React.useState<TestTab[]>(initialTabs);

  return (
    <>
      <TabFilterEditor
        tabs={tabs as any}
        blockId="tabs_block"
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
});
