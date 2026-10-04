import React, { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BlockSettingsEditor } from '../BlockSettingsEditor';
import type { BlockType, DslBlock } from '~/plugins/core-designer/components/studio/domain/dsl/types';

const fixture = vi.hoisted(() => ({ locale: 'en-US' }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: fixture.locale }) }));
afterEach(cleanup);
beforeEach(() => { fixture.locale = 'en-US'; });

const groupNames = { basic: ['Basic', '\u57fa\u672c'], layout: ['Layout', '\u5e03\u5c40'], data: ['Data', '\u6570\u636e'], appearance: ['Appearance', '\u5916\u89c2'], behavior: ['Behavior', '\u884c\u4e3a'] } as const;
type Group = keyof typeof groupNames;
const blocks: Array<{ type: BlockType; groups: Group[]; fields: Partial<Record<Group, string[]>> }> = [
  { type: 'filters', groups: ['basic', 'layout', 'behavior'], fields: { layout: ['block-span', 'block-columns'], behavior: ['filter-default-expanded', 'filter-search-on-enter'] } },
  ...(['form-section', 'detail-section'] as const).map(type => ({ type, groups: ['basic', 'layout', 'behavior'] as Group[], fields: { basic: ['block-title'], layout: ['block-span', 'block-columns', 'block-gutter'], behavior: ['section-collapsible', 'section-default-collapsed'] } })),
  { type: 'table', groups: ['basic', 'layout', 'data', 'appearance', 'behavior'], fields: { layout: ['block-span'], data: ['data-source', 'selection-bind', 'row-key'], appearance: ['table-bordered', 'table-striped', 'table-show-index', 'table-size'], behavior: ['table-pagination', 'table-page-size', 'table-row-selection', 'table-selection-type', 'table-sortable', 'table-exportable'] } },
  { type: 'stat-card', groups: ['basic', 'data', 'appearance'], fields: { basic: ['block-title'], data: ['stat-data-source', 'stat-value-field', 'stat-change-field', 'stat-refresh-interval'], appearance: ['stat-prefix', 'stat-suffix', 'stat-color'] } },
  { type: 'chart-card', groups: ['basic', 'data', 'appearance'], fields: { basic: ['block-title'], data: ['chart-data-source', 'chart-type', 'chart-x-field', 'chart-y-field', 'chart-refresh-interval'], appearance: ['chart-smooth', 'chart-legend', 'chart-height'] } },
  { type: 'custom', groups: ['basic', 'data', 'behavior'], fields: { basic: ['block-title', 'custom-component'], data: ['custom-value-field', 'custom-props-json'] } },
  { type: 'text', groups: ['basic', 'appearance'], fields: { basic: ['text-content'], appearance: ['text-size', 'text-color', 'text-bold'] } },
  ...(['toolbar', 'form-buttons'] as const).map(type => ({ type, groups: ['basic', 'layout'] as Group[], fields: { layout: ['block-span', 'button-align'] } })),
  { type: 'selection-info', groups: ['basic'], fields: {} },
];
const labels: Record<string, [string, string]> = {
  'block-title': ['Title', '\u6807\u9898'], 'text-content': ['Content', '\u5185\u5bb9'], 'custom-component': ['Component', '\u7ec4\u4ef6'],
  'block-span': ['Column span', '\u6805\u683c\u5bbd\u5ea6'], 'block-columns': ['Form columns', '\u8868\u5355\u5217\u6570'], 'block-gutter': ['Spacing', '\u95f4\u8ddd'], 'button-align': ['Button alignment', '\u6309\u94ae\u5bf9\u9f50'],
  'data-source': ['Data source', '\u6570\u636e\u6e90'], 'selection-bind': ['Selection binding', '\u9009\u62e9\u7ed1\u5b9a'], 'row-key': ['Row key field', '\u884c\u952e\u5b57\u6bb5'],
  'stat-data-source': ['Data source', '\u6570\u636e\u6e90'], 'stat-value-field': ['Value field', '\u6570\u503c\u5b57\u6bb5'], 'stat-change-field': ['Change rate field', '\u53d8\u5316\u7387\u5b57\u6bb5'], 'stat-refresh-interval': ['Refresh interval (ms)', '\u5237\u65b0\u95f4\u9694(ms)'],
  'chart-data-source': ['Data source', '\u6570\u636e\u6e90'], 'chart-type': ['Chart type', '\u56fe\u8868\u7c7b\u578b'], 'chart-x-field': ['X-axis field', 'X\u8f74\u5b57\u6bb5'], 'chart-y-field': ['Y-axis field', 'Y\u8f74\u5b57\u6bb5'], 'chart-refresh-interval': ['Refresh interval (ms)', '\u5237\u65b0\u95f4\u9694(ms)'],
  'custom-value-field': ['Value field', '\u503c\u5b57\u6bb5'], 'custom-props-json': ['Props JSON', '\u5c5e\u6027 JSON'],
  'table-bordered': ['Show borders', '\u663e\u793a\u8fb9\u6846'], 'table-striped': ['Striped rows', '\u6591\u9a6c\u7eb9'], 'table-show-index': ['Show row numbers', '\u663e\u793a\u5e8f\u53f7'], 'table-size': ['Table size', '\u8868\u683c\u5c3a\u5bf8'],
  'stat-prefix': ['Prefix', '\u524d\u7f00'], 'stat-suffix': ['Suffix', '\u540e\u7f00'], 'stat-color': ['Theme color', '\u4e3b\u9898\u8272'],
  'chart-smooth': ['Smooth curve', '\u5e73\u6ed1\u66f2\u7ebf'], 'chart-legend': ['Show legend', '\u663e\u793a\u56fe\u4f8b'], 'chart-height': ['Chart height', '\u56fe\u8868\u9ad8\u5ea6'],
  'text-size': ['Text size', '\u6587\u5b57\u5927\u5c0f'], 'text-color': ['Text color', '\u6587\u5b57\u989c\u8272'], 'text-bold': ['Bold', '\u52a0\u7c97'],
  'section-collapsible': ['Collapsible', '\u53ef\u6298\u53e0'], 'section-default-collapsed': ['Collapsed by default', '\u9ed8\u8ba4\u6536\u8d77'],
  'filter-default-expanded': ['Expand advanced filters', '\u5c55\u5f00\u9ad8\u7ea7\u7b5b\u9009'], 'filter-search-on-enter': ['Search on Enter', '\u56de\u8f66\u641c\u7d22'],
  'table-pagination': ['Enable pagination', '\u542f\u7528\u5206\u9875'], 'table-page-size': ['Rows per page', '\u6bcf\u9875\u6761\u6570'], 'table-row-selection': ['Selectable rows', '\u884c\u53ef\u9009\u62e9'], 'table-selection-type': ['Selection mode', '\u9009\u62e9\u6a21\u5f0f'], 'table-sortable': ['Sortable', '\u53ef\u6392\u5e8f'], 'table-exportable': ['Exportable', '\u53ef\u5bfc\u51fa'],
};
const optionContracts: Record<string, Array<[string, string, string]>> = {
  "block-gutter-select": [
    [
      "8",
      "Compact (8px)",
      "\u7d27\u51d1 (8px)"
    ],
    [
      "16",
      "Standard (16px)",
      "\u6807\u51c6 (16px)"
    ],
    [
      "24",
      "Relaxed (24px)",
      "\u5bbd\u677e (24px)"
    ],
    [
      "32",
      "Extra wide (32px)",
      "\u8d85\u5bbd (32px)"
    ]
  ],
  "button-align-select": [
    [
      "left",
      "Left",
      "\u5de6\u5bf9\u9f50"
    ],
    [
      "center",
      "Center",
      "\u5c45\u4e2d"
    ],
    [
      "right",
      "Right",
      "\u53f3\u5bf9\u9f50"
    ]
  ],
  "table-size-select": [
    [
      "small",
      "Compact",
      "\u7d27\u51d1"
    ],
    [
      "middle",
      "Standard",
      "\u6807\u51c6"
    ],
    [
      "large",
      "Relaxed",
      "\u5bbd\u677e"
    ]
  ],
  "stat-color-select": [
    [
      "blue",
      "Blue",
      "\u84dd\u8272"
    ],
    [
      "green",
      "Green",
      "\u7eff\u8272"
    ],
    [
      "orange",
      "Orange",
      "\u6a59\u8272"
    ],
    [
      "red",
      "Red",
      "\u7ea2\u8272"
    ],
    [
      "purple",
      "Purple",
      "\u7d2b\u8272"
    ]
  ],
  "text-size-select": [
    [
      "xs",
      "Extra small",
      "\u8d85\u5c0f"
    ],
    [
      "sm",
      "Small",
      "\u5c0f"
    ],
    [
      "base",
      "Standard",
      "\u6807\u51c6"
    ],
    [
      "lg",
      "Large",
      "\u5927"
    ],
    [
      "xl",
      "Extra large",
      "\u8d85\u5927"
    ]
  ],
  "text-color-select": [
    [
      "default",
      "Default",
      "\u9ed8\u8ba4"
    ],
    [
      "secondary",
      "Secondary",
      "\u6b21\u8981"
    ],
    [
      "success",
      "Success",
      "\u6210\u529f"
    ],
    [
      "warning",
      "Warning",
      "\u8b66\u544a"
    ],
    [
      "danger",
      "Danger",
      "\u5371\u9669"
    ]
  ]
};
function block(type: BlockType): DslBlock { return { id: 'fixture-block', blockType: type, collapsible: true, props: { retained: 'unchanged', rowSelection: true } }; }
function tab(group: Group) { fireEvent.click(screen.getByTestId(`property-group-${group}`)); }

describe('block settings localization and retained DSL semantics', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    for (const spec of blocks) {
      it(`${locale} renders available groups and property labels/options for ${spec.type}, including readonly`, () => {
        fixture.locale = locale;
        const b = block(spec.type); const change = vi.fn();
        const view = render(<BlockSettingsEditor block={b} onChange={change} />);
        const allGroups = Object.keys(groupNames) as Group[];
        for (const g of allGroups) {
          const button = screen.queryByTestId(`property-group-${g}`);
          if (spec.groups.length === 1 || !spec.groups.includes(g)) { expect(button).toBeNull(); continue; }
          expect(button).toHaveTextContent(groupNames[g][locale === 'en-US' ? 0 : 1]);
        }
        for (const g of spec.groups) {
          if (spec.groups.length > 1) tab(g);
          for (const id of spec.fields[g] ?? []) expect(screen.getByTestId(id)).toHaveTextContent(labels[id][locale === 'en-US' ? 0 : 1]);
          if (g === 'layout') {
            const span = screen.getByTestId('block-span-select') as HTMLSelectElement;
            expect(Array.from(span.options).map(o => o.value)).toEqual(['', '1', '2', '3', '4', '6', '8', '12']);
            expect(span.options[7].textContent).toBe(locale === 'en-US' ? '12 columns' : '12 \u5217');
          }
          if (g === 'data' && spec.type === 'chart-card') {
            const select = screen.getByTestId('chart-type-select') as HTMLSelectElement;
            expect(Array.from(select.options).map(o => o.value)).toEqual(['bar', 'line', 'pie', 'area']);
            expect(Array.from(select.options).map(o => o.textContent)).toEqual(locale === 'en-US' ? ['Bar', 'Line', 'Pie', 'Area'] : ['\u67f1\u72b6\u56fe', '\u6298\u7ebf\u56fe', '\u997c\u56fe', '\u9762\u79ef\u56fe']);
          }
          if (g === 'behavior' && spec.type === 'table') {
            expect(screen.getByRole('option', { name: locale === 'en-US' ? '50 rows' : '50 \u6761' })).toHaveValue('50');
            expect(screen.getByRole('option', { name: locale === 'en-US' ? 'Single' : '\u5355\u9009' })).toHaveValue('radio');
          }
          for (const [id, expected] of Object.entries(optionContracts)) {
            const select = screen.queryByTestId(id) as HTMLSelectElement | null;
            if (!select) continue;
            expect(Array.from(select.options).map(o => [o.value, o.textContent])).toEqual(expected.map(([value, en, zh]) => [value, locale === 'en-US' ? en : zh]));
          }
          view.rerender(<BlockSettingsEditor block={b} onChange={change} readonly />);
          const controls = view.container.querySelectorAll('input,select,textarea,button[role="switch"]') as NodeListOf<HTMLElement>;
          for (const control of controls) expect(control).toBeDisabled();
          view.rerender(<BlockSettingsEditor block={b} onChange={change} />);
        }
        expect(change).not.toHaveBeenCalled();
      });
    }
    it(`${locale} keeps numeric/enum/boolean/selection writes and unrelated props unchanged`, () => {
      fixture.locale = locale; const changes = vi.fn();
      function Controlled() { const [b, setBlock] = useState(block('table')); return <><BlockSettingsEditor block={b} onChange={updates => { changes(updates); setBlock(old => ({ ...old, ...updates })); }} /><output data-testid="observed">{JSON.stringify(b)}</output></>; }
      render(<Controlled />); tab('layout');
      fireEvent.change(screen.getByTestId('block-span-select'), { target: { value: '6' } });
      expect(changes).toHaveBeenLastCalledWith({ span: 6 });
      tab('data'); fireEvent.change(screen.getByTestId('selection-bind-input'), { target: { value: 'pickedRows' } });
      expect(changes).toHaveBeenLastCalledWith({ selection: { bind: 'pickedRows' } });
      tab('appearance'); fireEvent.change(screen.getByTestId('table-size-select'), { target: { value: 'small' } });
      expect(changes).toHaveBeenLastCalledWith({ props: { retained: 'unchanged', rowSelection: true, size: 'small' } });
      fireEvent.click(screen.getByTestId('table-striped-switch'));
      expect(changes).toHaveBeenLastCalledWith({ props: { retained: 'unchanged', rowSelection: true, size: 'small', striped: true } });
      tab('behavior'); fireEvent.change(screen.getByTestId('table-page-size-select'), { target: { value: '50' } });
      fireEvent.change(screen.getByTestId('table-selection-type-select'), { target: { value: 'radio' } });
      const result = JSON.parse(screen.getByTestId('observed').textContent!);
      expect(result).toEqual({ ...block('table'), span: 6, selection: { bind: 'pickedRows' }, props: { retained: 'unchanged', rowSelection: true, size: 'small', striped: true, pageSize: 50, selectionType: 'radio' } });
      expect(changes).toHaveBeenCalledTimes(6);
    });
    it(`${locale} preserves data-source references and top-level refresh intervals for stat and chart cards`, () => {
      fixture.locale = locale;
      for (const type of ['stat-card', 'chart-card'] as const) {
        const prefix = type === 'stat-card' ? 'stat' : 'chart'; const change = vi.fn();
        const b = { ...block(type), dataSource: 'oldSource', props: { retained: 'unchanged', refreshInterval: 2000 } };
        const view = render(<BlockSettingsEditor block={b} onChange={change} />); tab('data');
        const interval = screen.getByTestId(`${prefix}-refresh-interval-input`);
        expect(interval).toHaveValue(2000);
        fireEvent.change(interval, { target: { value: '5000' } });
        expect(change).toHaveBeenLastCalledWith({ refreshInterval: 5000 });
        fireEvent.change(interval, { target: { value: '' } });
        expect(change).toHaveBeenLastCalledWith({ refreshInterval: undefined });
        fireEvent.change(screen.getByTestId(`${prefix}-data-source-input`), { target: { value: 'newSource' } });
        expect(change).toHaveBeenLastCalledWith({ dataSource: 'newSource' });
        expect(b.props).toEqual({ retained: 'unchanged', refreshInterval: 2000 });
        expect(change).toHaveBeenCalledTimes(3); view.unmount();
      }
    });
    it(`${locale} localizes JSON object/syntax failures and never emits invalid props`, () => {
      fixture.locale = locale; const change = vi.fn(); render(<BlockSettingsEditor block={block('custom')} onChange={change} />); tab('data');
      const input = screen.getByTestId('custom-props-json-input');
      for (const value of ['null', '[]', '5', '"text"']) {
        fireEvent.change(input, { target: { value } });
        expect(screen.getByTestId('custom-props-json-error')).toHaveTextContent(locale === 'en-US' ? 'Props JSON must be an object' : '\u5c5e\u6027 JSON \u5fc5\u987b\u662f\u5bf9\u8c61');
      }
      fireEvent.change(input, { target: { value: '{ invalid' } });
      expect(screen.getByTestId('custom-props-json-error')).toHaveTextContent(locale === 'en-US' ? 'Invalid JSON' : 'JSON \u683c\u5f0f\u65e0\u6548');
      expect(change).not.toHaveBeenCalled();
      fireEvent.change(input, { target: { value: '{"initialCurrentDataType":"decimal","retained":true}' } });
      expect(change).toHaveBeenCalledExactlyOnceWith({ props: { initialCurrentDataType: 'decimal', retained: true } });
      expect(screen.queryByTestId('custom-props-json-error')).toBeNull();
    });
  }
  it('updates visible option names and existing error feedback when locale changes without losing draft or values', () => {
    const b = block('custom'); const change = vi.fn(); const view = render(<BlockSettingsEditor block={b} onChange={change} />); tab('data');
    fireEvent.change(screen.getByTestId('custom-props-json-input'), { target: { value: '[1,2]' } });
    fixture.locale = 'zh-CN'; view.rerender(<BlockSettingsEditor block={b} onChange={change} />);
    expect(screen.getByTestId('custom-props-json-error')).toHaveTextContent('\u5c5e\u6027 JSON \u5fc5\u987b\u662f\u5bf9\u8c61');
    expect(screen.getByTestId('custom-props-json-input')).toHaveValue('[1,2]');
    expect(screen.getByTestId('custom-value-field-input')).toHaveValue('');
    expect(change).not.toHaveBeenCalled();
    view.unmount(); fixture.locale = 'en-US'; const chart = block('chart-card');
    const chartView = render(<BlockSettingsEditor block={chart} onChange={change} />); tab('data');
    fireEvent.change(screen.getByTestId('chart-type-select'), { target: { value: 'pie' } });
    expect(change).toHaveBeenLastCalledWith({ props: { ...chart.props, chartType: 'pie' } });
    const updated = { ...chart, props: { ...chart.props, chartType: 'pie' } };
    fixture.locale = 'zh-CN'; chartView.rerender(<BlockSettingsEditor block={updated} onChange={change} />);
    expect(screen.getByRole('option', { name: '\u997c\u56fe' })).toHaveValue('pie');
    expect(screen.getByTestId('chart-type-select')).toHaveValue('pie');
    expect(change).toHaveBeenCalledTimes(1);
  });
});
