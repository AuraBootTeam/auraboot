/**
 * Tests for KeyValueEditor — the shared key/value map editor used by the dashboard
 * DataSourceConfig for api params and named-query parameters.
 *
 * Verifies:
 *  - existing map entries seed as editable rows
 *  - adding a row + typing key/value projects into the Record via onChange
 *  - editing an existing value updates that entry
 *  - removing a row drops the entry
 *  - blank-key rows are excluded from the projected Record
 *  - an external value reset (e.g. type/query switch) re-seeds the rows
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { KeyValueEditor } from '../KeyValueEditor';

const PREFIX = 'kv';

describe('KeyValueEditor', () => {
  it('seeds rows from an existing record', () => {
    render(
      <KeyValueEditor value={{ region: 'east', year: '2026' }} onChange={vi.fn()} testIdPrefix={PREFIX} />,
    );
    const keys = screen.getAllByTestId('kv-key') as HTMLInputElement[];
    const values = screen.getAllByTestId('kv-value') as HTMLInputElement[];
    expect(keys.map((k) => k.value)).toEqual(['region', 'year']);
    expect(values.map((v) => v.value)).toEqual(['east', '2026']);
  });

  it('adding a row and typing key/value projects into the record', () => {
    const onChange = vi.fn();
    render(<KeyValueEditor value={{}} onChange={onChange} testIdPrefix={PREFIX} />);

    fireEvent.click(screen.getByTestId('kv-add'));
    fireEvent.change(screen.getByTestId('kv-key'), { target: { value: 'status' } });
    fireEvent.change(screen.getByTestId('kv-value'), { target: { value: 'open' } });

    expect(onChange).toHaveBeenLastCalledWith({ status: 'open' });
  });

  it('editing an existing value updates that entry', () => {
    const onChange = vi.fn();
    render(<KeyValueEditor value={{ status: 'open' }} onChange={onChange} testIdPrefix={PREFIX} />);

    fireEvent.change(screen.getByTestId('kv-value'), { target: { value: 'closed' } });

    expect(onChange).toHaveBeenLastCalledWith({ status: 'closed' });
  });

  it('removing a row drops the entry', () => {
    const onChange = vi.fn();
    render(
      <KeyValueEditor value={{ a: '1', b: '2' }} onChange={onChange} testIdPrefix={PREFIX} />,
    );

    // remove the first row (a)
    fireEvent.click(screen.getAllByTestId('kv-remove')[0]);

    expect(onChange).toHaveBeenLastCalledWith({ b: '2' });
  });

  it('excludes blank-key rows from the projected record', () => {
    const onChange = vi.fn();
    render(<KeyValueEditor value={{}} onChange={onChange} testIdPrefix={PREFIX} />);

    fireEvent.click(screen.getByTestId('kv-add'));
    // value typed but key left blank → not emitted
    fireEvent.change(screen.getByTestId('kv-value'), { target: { value: 'orphan' } });

    expect(onChange).toHaveBeenLastCalledWith({});
  });

  it('re-seeds rows when the external value is reset', () => {
    const { rerender } = render(
      <KeyValueEditor value={{ region: 'east' }} onChange={vi.fn()} testIdPrefix={PREFIX} />,
    );
    expect((screen.getByTestId('kv-key') as HTMLInputElement).value).toBe('region');

    // external reset (e.g. switching data source type) clears the map
    rerender(<KeyValueEditor value={{}} onChange={vi.fn()} testIdPrefix={PREFIX} />);
    expect(screen.queryByTestId('kv-key')).toBeNull();
  });
});
