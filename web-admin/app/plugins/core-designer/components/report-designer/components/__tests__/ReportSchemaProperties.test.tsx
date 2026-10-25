import React from 'react';
import { afterEach, describe, it, expect } from 'vitest';
import { render, cleanup, fireEvent, screen, within } from '@testing-library/react';
import { BlockPropertyPanel } from '../BlockPropertyPanel';
import { ReportDocumentProvider, useReportDocument } from '../../state/ReportDocumentProvider';
import { createEmptyReport, type ReportBlock } from '../../types';
import { I18nProvider } from '~/contexts/I18nContext';

afterEach(cleanup);
const fixtures: ReportBlock[] = [
  {
    id: 't',
    blockType: 'table',
    dataSource: 'main',
    columns: [{ field: 'amount', label: 'Amount' }],
  },
  {
    id: 'g',
    blockType: 'grouped-table',
    dataSource: 'main',
    groupByField: 'customer',
    columns: [{ field: 'amount' }],
  },
  {
    id: 's',
    blockType: 'stat-card',
    dataSource: 'main',
    valueField: 'amount',
    aggregation: 'sum',
    label: 'Revenue',
  },
  {
    id: 'r',
    blockType: 'rich-text',
    content: 'Initial',
    style: { fontSize: 10, color: '#123456' },
  },
  {
    id: 'x',
    blockType: 'cross-tab',
    dataSource: 'main',
    rowField: 'customer',
    columnField: 'status',
    valueField: 'amount',
    aggregation: 'sum',
  },
  {
    id: 'c',
    blockType: 'chart',
    dataSource: 'main',
    chartType: 'bar',
    categoryField: 'customer',
    valueField: 'amount',
  },
  { id: 'b', blockType: 'barcode', format: 'code128', staticValue: '12345' },
  { id: 'w', blockType: 'watermark', text: 'CONFIDENTIAL' },
];
function Harness({ block }: { block: ReportBlock }) {
  const doc = useReportDocument();
  return (
    <>
      <button
        onClick={() => {
          doc.loadDocument({
            ...createEmptyReport('Test'),
            dataSources: { main: { type: 'static', data: [{ amount: 100 }] } },
            body: [block],
          });
          doc.selectBlock(block.id);
        }}
      >
        Load
      </button>
      <button onClick={doc.undo}>Undo</button>
      <button onClick={doc.redo}>Redo</button>
      <output data-testid="document">{JSON.stringify(doc.report)}</output>
      <BlockPropertyPanel />
    </>
  );
}
function mount(block: ReportBlock) {
  render(
    <I18nProvider initialLocale="en-US" initialData={{ fixture: 'loaded' }}>
      <ReportDocumentProvider>
        <Harness block={block} />
      </ReportDocumentProvider>
    </I18nProvider>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'Load' }));
}
describe('native report schema properties', () => {
  it.each(fixtures)(
    'renders and edits the actual $blockType document with undo and redo',
    (block) => {
      mount(block);
      const key = block.blockType === 'watermark' ? 'text' : 'title';
      const input = within(screen.getByTestId(`schema-config-field-${key}`)).getByRole('textbox');
      fireEvent.change(input, { target: { value: 'Reviewed configuration' } });
      const current = () => JSON.parse(screen.getByTestId('document').textContent!).body[0];
      expect(current()[key]).toBe('Reviewed configuration');
      expect(current().id).toBe(block.id);
      expect(current().blockType).toBe(block.blockType);
      fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
      expect(current()[key]).toBe((block as unknown as Record<string, unknown>)[key]);
      fireEvent.click(screen.getByRole('button', { name: 'Redo' }));
      expect(current()[key]).toBe('Reviewed configuration');
    },
  );
  it('preserves nested styles and sibling column configuration', () => {
    mount(fixtures[3]);
    fireEvent.change(
      within(screen.getByTestId('schema-config-field-style.fontSize')).getByRole('spinbutton'),
      { target: { value: '24' } },
    );
    const report = JSON.parse(screen.getByTestId('document').textContent!);
    expect(report.body[0].style).toEqual({ fontSize: 24, color: '#123456', fontWeight: 'normal' });
    expect(report.body[0].content).toBe('Initial');
  });
});

it.each([['summary',0],['groupSubtotal',1],['grandTotal',1]] as const)('initializes schema-declared %s columns before rendering an enabled summary',(key,index)=>{
 mount(fixtures[index]);const current=()=>JSON.parse(screen.getByTestId('document').textContent!).body[0];
 expect(current()[key]).toBeUndefined();
 fireEvent.click(within(screen.getByTestId(`schema-config-field-${key}.enabled`)).getByRole('switch'));
 expect(current()[key]).toMatchObject({enabled:true,columns:[]});
 expect(within(screen.getByTestId(`schema-config-field-${key}.enabled`)).getByRole('switch')).toHaveAttribute('aria-checked','true');
 fireEvent.click(screen.getByRole('button',{name:'Undo'}));expect(current()[key]).toBeUndefined();
 fireEvent.click(screen.getByRole('button',{name:'Redo'}));expect(current()[key].columns).toEqual([]);
});
