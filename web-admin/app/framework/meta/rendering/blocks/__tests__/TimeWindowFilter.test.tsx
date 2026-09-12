import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { TimeWindowFilter } from '../TimeWindowFilter';

function setup() {
  const manager = new ScopedStateManager({} as any);
  manager.createScope('test');
  const runtime = {
    getStateManager: () => manager,
    getScopeId: () => 'test',
  } as unknown as SchemaRuntime;
  render(
    <TimeWindowFilter
      config={{ stateKey: 'window', defaultDays: 30, maxDays: 366 }}
      runtime={runtime}
    />,
  );
  return () => manager.getStore('test').getState().state.window;
}
describe('atomic query time window', () => {
  it('initializes once and keeps draft edits out of the applied query until valid submission', async () => {
    const read = setup();
    await waitFor(() => expect(read()).toBeDefined());
    const initial = read();
    expect(Date.parse(initial.to) - Date.parse(initial.from)).toBe(30 * 86400000);
    fireEvent.change(screen.getByLabelText('开始时间（含）'), {
      target: { value: '2026-01-01T00:00' },
    });
    fireEvent.change(screen.getByLabelText('结束时间（不含）'), {
      target: { value: '2026-02-01T00:00' },
    });
    expect(read()).toBe(initial);
    fireEvent.click(screen.getByRole('button', { name: '应用时间范围' }));
    expect(read()).toEqual({
      from: new Date('2026-01-01T00:00').toISOString(),
      to: new Date('2026-02-01T00:00').toISOString(),
    });
    fireEvent.change(screen.getByLabelText('开始时间（含）'), {
      target: { value: '2026-03-01T00:00' },
    });
    const valid = read();
    fireEvent.click(screen.getByRole('button', { name: '应用时间范围' }));
    expect(screen.getByRole('alert')).toHaveTextContent('开始时间须早于结束时间');
    expect(read()).toBe(valid);
    fireEvent.click(screen.getByRole('button', { name: '最近 30 天' }));
    expect(Date.parse(read().to) - Date.parse(read().from)).toBe(30 * 86400000);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('rejects an empty input and an oversized window without replacing the previous result', async () => {
    const read = setup();
    await waitFor(() => expect(read()).toBeDefined());
    const initial = read();
    fireEvent.change(screen.getByLabelText('开始时间（含）'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: '应用时间范围' }));
    expect(screen.getByRole('alert')).toHaveTextContent('请选择有效');
    expect(read()).toBe(initial);
    fireEvent.change(screen.getByLabelText('开始时间（含）'), {
      target: { value: '2020-01-01T00:00' },
    });
    fireEvent.change(screen.getByLabelText('结束时间（不含）'), {
      target: { value: '2026-01-01T00:00' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用时间范围' }));
    expect(screen.getByRole('alert')).toHaveTextContent('366');
    expect(read()).toBe(initial);
  });
});
