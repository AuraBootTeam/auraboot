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
