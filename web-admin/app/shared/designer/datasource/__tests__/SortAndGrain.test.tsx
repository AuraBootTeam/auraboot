/**
 * Tests for the aggregate ordering + time-grain controls (G1/G2).
 *
 *  1. parseGrainDimension / isDateField — pure helpers behind the grain picker
 *  2. TimeGrainPicker — emits `field__grain`, hides when the model has no date field
 *  3. SortEditor — adds/edits/removes sort rows over dimensions + metric aliases
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { SortEditor } from '../SortEditor';
import { TimeGrainPicker, isDateField, parseGrainDimension } from '../TimeGrainPicker';
import type { FieldOption, SortCondition } from '../types';

describe('parseGrainDimension', () => {
  it('splits a grain-suffixed dimension', () => {
    expect(parseGrainDimension('created_at__month')).toEqual({ field: 'created_at', grain: 'month' });
  });

  it('returns a null grain for a plain dimension', () => {
    expect(parseGrainDimension('crm_opp_stage')).toEqual({ field: 'crm_opp_stage', grain: null });
  });
});

describe('isDateField', () => {
  it('recognises date/datetime/timestamp fields, case-insensitively', () => {
    expect(isDateField({ code: 'a', name: 'A', fieldType: 'date' })).toBe(true);
    expect(isDateField({ code: 'b', name: 'B', fieldType: 'DateTime' })).toBe(true);
    expect(isDateField({ code: 'c', name: 'C', fieldType: 'string' })).toBe(false);
    expect(isDateField({ code: 'd', name: 'D', fieldType: undefined as unknown as string })).toBe(false);
  });
});

const DATE_FIELDS: FieldOption[] = [
  { code: 'created_at', name: '创建时间', fieldType: 'datetime' },
  { code: 'close_date', name: '成交日期', fieldType: 'date' },
];

describe('TimeGrainPicker', () => {
  it('renders nothing when the model has no date field', () => {
    const { container } = render(
      <TimeGrainPicker dateFields={[]} field="" grain="month" onChange={vi.fn()} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('emits `field__grain` when a field and grain are chosen', () => {
    const onChange = vi.fn();
    render(<TimeGrainPicker dateFields={DATE_FIELDS} field="" grain="month" onChange={onChange} />);

    // Choosing a field defaults the grain to month.
    fireEvent.change(screen.getByTestId('grain-field'), { target: { value: 'created_at' } });
    expect(onChange).toHaveBeenCalledWith('created_at', 'month');
  });

  it('clears the field back to "no bucketing"', () => {
    const onChange = vi.fn();
    render(
      <TimeGrainPicker
        dateFields={DATE_FIELDS}
        field="created_at"
        grain="month"
        onChange={onChange}
      />,
    );
    fireEvent.change(screen.getByTestId('grain-field'), { target: { value: '' } });
    expect(onChange).toHaveBeenCalledWith('', '');
  });
});

describe('SortEditor', () => {
  const options = [
    { value: 'crm_opp_stage', label: 'crm_opp_stage' },
    { value: 'total', label: 'total (指标)' },
  ];

  it('prompts to pick fields first when there are no options', () => {
    render(<SortEditor value={[]} onChange={vi.fn()} options={[]} />);
    expect(screen.getByText('先选择维度或指标')).toBeInTheDocument();
  });

  it('adds a sort row defaulting to descending on the first option', () => {
    const onChange = vi.fn();
    render(<SortEditor value={[]} onChange={onChange} options={options} />);
    fireEvent.click(screen.getByTestId('sort-add'));
    expect(onChange).toHaveBeenCalledWith([{ field: 'crm_opp_stage', order: 'desc' }]);
  });

  it('changes the sort field and direction', () => {
    const onChange = vi.fn();
    const value: SortCondition[] = [{ field: 'crm_opp_stage', order: 'desc' }];
    render(<SortEditor value={value} onChange={onChange} options={options} />);

    fireEvent.change(screen.getByTestId('sort-field'), { target: { value: 'total' } });
    expect(onChange).toHaveBeenCalledWith([{ field: 'total', order: 'desc' }]);

    fireEvent.change(screen.getByTestId('sort-direction'), { target: { value: 'asc' } });
    expect(onChange).toHaveBeenCalledWith([{ field: 'crm_opp_stage', order: 'asc' }]);
  });

  it('removes a sort row', () => {
    const onChange = vi.fn();
    render(
      <SortEditor
        value={[{ field: 'total', order: 'desc' }]}
        onChange={onChange}
        options={options}
      />,
    );
    fireEvent.click(screen.getByLabelText('移除排序'));
    expect(onChange).toHaveBeenCalledWith([]);
  });
});
