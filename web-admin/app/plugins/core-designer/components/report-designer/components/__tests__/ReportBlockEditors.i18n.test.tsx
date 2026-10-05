import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider } from '~/contexts/I18nContext';
import { ReportDocumentProvider, useReportDocument } from '../../state/ReportDocumentProvider';
import { ChartBlockEditor } from '../ChartBlockEditor';
import { CrossTabBlockEditor } from '../CrossTabBlockEditor';
import { StatCardBlockEditor } from '../StatCardBlockEditor';
import { GroupedTableBlockEditor } from '../GroupedTableBlockEditor';
import { BarcodeBlockEditor } from '../BarcodeBlockEditor';
import { WatermarkBlockEditor } from '../WatermarkBlockEditor';
import { BandEditor } from '../BandEditor';
import { ParametersBar } from '../ParametersBar';
import { ReportBandBlock } from '../../blocks/ReportBandBlock';
import { ReportChartBlock } from '../../blocks/ReportChartBlock';
import { ReportRichTextBlock } from '../../blocks/ReportRichTextBlock';
import { ReportGroupedTableBlock } from '../../blocks/ReportGroupedTableBlock';

afterEach(cleanup);

function localized(locale: string, child: React.ReactNode) {
  return (
    <I18nProvider initialLocale={locale} initialData={{ fixture: 'loaded' }}>
      <ReportDocumentProvider>{child}</ReportDocumentProvider>
    </I18nProvider>
  );
}

function CurrentTitle() {
  return <output>{useReportDocument().report?.title}</output>;
}

for (const locale of ['zh-CN', 'en-US']) {
  describe(`report editors with real ${locale} context`, () => {
    const zh = locale === 'zh-CN';
    it('localizes band controls and preserves the date element identifier', () => {
      const change = vi.fn();
      const band = { height: 20, elements: [{ type: 'text' as const, content: 'User Header' }] };
      render(localized(locale, <BandEditor band={band} onChange={change} />));
      expect(screen.getByLabelText(zh ? '高度（毫米）' : 'Height (mm)')).toBeTruthy();
      expect(screen.getByPlaceholderText(zh ? '文本内容' : 'Text content')).toHaveValue(
        'User Header',
      );
      fireEvent.click(screen.getByRole('button', { name: zh ? '+ 日期' : '+ Date' }));
      expect(change).toHaveBeenCalledExactlyOnceWith({
        ...band,
        elements: [...band.elements, { type: 'date', align: 'left' }],
      });
    });
    it('localizes runtime parameter chrome without changing payload keys or caller labels', () => {
      const change = vi.fn(),
        apply = vi.fn();
      render(
        localized(
          locale,
          <ParametersBar
            parameters={[
              {
                name: 'region',
                label: 'Region',
                type: 'select',
                options: [{ value: 'north', label: 'User North' }],
              },
              { name: 'period', label: 'Period', type: 'date-range' },
            ]}
            values={{ region: 'north' }}
            onChange={change}
            onApply={apply}
            disabled
          />,
        ),
      );
      expect(screen.getByRole('option', { name: zh ? '全部' : 'All' })).toHaveValue('');
      expect(screen.getByRole('option', { name: 'User North' })).toHaveValue('north');
      fireEvent.change(screen.getByLabelText(zh ? 'Period 开始' : 'Period start'), {
        target: { value: '2026-10-01' },
      });
      expect(change).toHaveBeenCalledExactlyOnceWith({
        region: 'north',
        period_start: '2026-10-01',
      });
      expect(screen.getByRole('button', { name: zh ? '应用' : 'Apply' })).toBeDisabled();
      expect(apply).not.toHaveBeenCalled();
    });
    it('localizes design instructions while retaining user content', () => {
      render(
        localized(
          locale,
          <>
            <ReportBandBlock
              band={{
                height: 20,
                elements: [{ type: 'text', content: 'Custom Header' }, { type: 'page-number' }],
              }}
              mode="design"
              position="header"
            />
            <ReportRichTextBlock
              block={{ id: 'r', blockType: 'rich-text', content: '' }}
              mode="design"
            />
            <ReportChartBlock
              block={{
                id: 'c',
                blockType: 'chart',
                dataSource: '',
                chartType: 'bar',
                categoryField: '',
                valueField: '',
              }}
              mode="design"
            />
            <ReportGroupedTableBlock
              block={{
                id: 'g',
                blockType: 'grouped-table',
                dataSource: '',
                groupByField: '',
                columns: [],
              }}
              mode="design"
            />
          </>,
        ),
      );
      for (const label of [
        zh ? '页眉' : 'Header',
        zh ? '第 1 页' : 'Page 1',
        zh ? '点击添加文本内容' : 'Click to add text content',
        zh ? '请配置分类和数值字段' : 'Configure category and value fields',
        zh ? '请在属性面板中选择分组字段' : 'Select a group-by field in the property panel',
        'Custom Header',
      ])
        expect(screen.getByText(label, { exact: true })).toBeTruthy();
    });
    it('localizes grouped table samples and missing groups while preserving user values', () => {
      const block = {
        id: 'g', blockType: 'grouped-table' as const, dataSource: 'orders',
        title: 'User Report', groupByField: 'owner',
        columns: [{ field: 'value', label: 'User Value' }],
      };
      const view = render(localized(locale, <ReportGroupedTableBlock block={block} mode="design" />));
      expect(screen.getByText(zh ? 'owner: 分组 A' : 'owner: Group A')).toBeVisible();
      expect(screen.getByText(zh ? 'owner: 分组 B' : 'owner: Group B')).toBeVisible();
      expect(screen.getAllByText(zh ? '示例' : 'Sample')).toHaveLength(3);
      view.unmount();
      render(localized(locale, <ReportGroupedTableBlock block={block} mode="runtime"
        data={[{ value: 'User Entry' }, { owner: 'Group A', value: 'User Sample' }]} />));
      expect(screen.getByText(zh ? 'owner: 其它 (1)' : 'owner: Other (1)')).toBeVisible();
      expect(screen.getByText('owner: Group A (1)')).toBeVisible();
      expect(screen.getByText('User Entry')).toBeVisible();
      expect(screen.getByText('User Sample')).toBeVisible();
      expect(screen.getByText('User Value')).toBeVisible();
    });
    it('initializes the report title in the active language', () => {
      render(localized(locale, <CurrentTitle />));
      expect(screen.getByRole('status').textContent).toBe(zh ? '未命名报表' : 'Untitled Report');
    });
    it('localizes chart controls and writes the chart type identifier', () => {
      const change = vi.fn();
      render(
        localized(
          locale,
          <ChartBlockEditor
            block={{
              id: 'c',
              blockType: 'chart',
              dataSource: '',
              chartType: 'bar',
              categoryField: '',
              valueField: '',
            }}
            dataSources={{}}
            onChange={change}
          />,
        ),
      );
      expect(screen.getByPlaceholderText(zh ? '图表标题' : 'Chart Title')).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: zh ? '饼图' : 'Pie' }));
      expect(change).toHaveBeenCalledExactlyOnceWith({ chartType: 'pie' });
    });
    it('localizes cross-tab fields and writes the row field unchanged', () => {
      const change = vi.fn();
      render(
        localized(
          locale,
          <CrossTabBlockEditor
            block={{
              id: 'c',
              blockType: 'cross-tab',
              dataSource: '',
              rowField: '',
              columnField: '',
              valueField: '',
              aggregation: 'sum',
            }}
            dataSources={{}}
            onChange={change}
          />,
        ),
      );
      fireEvent.change(
        screen.getByPlaceholderText(zh ? '用于行分组的字段' : 'Field for row grouping'),
        { target: { value: 'region_code' } },
      );
      expect(change).toHaveBeenCalledExactlyOnceWith({ rowField: 'region_code' });
    });
    it('localizes stat-card aggregation without translating its option value', () => {
      const change = vi.fn();
      render(
        localized(
          locale,
          <StatCardBlockEditor
            block={{
              id: 's',
              blockType: 'stat-card',
              dataSource: '',
              valueField: '',
              aggregation: 'sum',
              label: '',
            }}
            dataSources={{}}
            onChange={change}
          />,
        ),
      );
      const option = screen.getByRole('option', { name: zh ? '平均值' : 'AVG' });
      expect((option as HTMLOptionElement).value).toBe('avg');
      fireEvent.change(option.parentElement!, { target: { value: 'avg' } });
      expect(change).toHaveBeenCalledExactlyOnceWith({ aggregation: 'avg' });
    });
    it('localizes grouped-table fields and preserves the configured grouping key', () => {
      const change = vi.fn();
      render(
        localized(
          locale,
          <GroupedTableBlockEditor
            block={{
              id: 'g',
              blockType: 'grouped-table',
              dataSource: '',
              groupByField: '',
              columns: [],
            }}
            dataSources={{}}
            onChange={change}
          />,
        ),
      );
      fireEvent.change(
        screen.getByPlaceholderText(zh ? '用于分组的字段' : 'Field name to group by'),
        { target: { value: 'customer_id' } },
      );
      expect(change).toHaveBeenCalledExactlyOnceWith({ groupByField: 'customer_id' });
    });
    it('localizes barcode inputs and preserves barcode format identifiers', () => {
      const change = vi.fn();
      render(
        localized(
          locale,
          <BarcodeBlockEditor
            block={{ id: 'b', blockType: 'barcode', format: 'code128' }}
            dataSources={{}}
            onChange={change}
          />,
        ),
      );
      expect(screen.getByPlaceholderText(zh ? '条码标题' : 'Barcode Title')).toBeTruthy();
      const option = screen.getByRole('option', { name: 'EAN-13' });
      fireEvent.change(option.parentElement!, { target: { value: 'ean13' } });
      expect(change).toHaveBeenCalledExactlyOnceWith({ format: 'ean13' });
    });
    it('localizes watermark controls and preserves user text', () => {
      const change = vi.fn();
      render(
        localized(
          locale,
          <WatermarkBlockEditor
            block={{ id: 'w', blockType: 'watermark', text: '' }}
            onChange={change}
          />,
        ),
      );
      fireEvent.change(screen.getByPlaceholderText(zh ? '例如：机密' : 'e.g. CONFIDENTIAL'), {
        target: { value: 'Internal Draft' },
      });
      expect(change).toHaveBeenCalledExactlyOnceWith({ text: 'Internal Draft' });
      expect(
        screen.getByRole('checkbox', { name: zh ? '重复水印' : 'Repeat pattern' }),
      ).toBeTruthy();
    });
  });
}
