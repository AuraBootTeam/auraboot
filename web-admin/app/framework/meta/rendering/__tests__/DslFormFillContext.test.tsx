/**
 * DslFormFillContext.test.tsx
 *
 * The AI fill apply seam must honour AI-locked fields (D5): applyFields skips
 * any field code in the provider's lockedFields set and applies the rest, and
 * consumers (e.g. the ai-fill-banner) can read lockedFields to forward them to
 * the backend.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import { DslFormFillProvider, useDslFormFill, type DslFormFillApi } from '../DslFormFillContext';

function Harness({ onReady }: { onReady: (api: DslFormFillApi) => void }) {
  const api = useDslFormFill();
  onReady(api);
  return null;
}

describe('DslFormFillProvider lock awareness', () => {
  it('applyFields skips locked field codes and applies the rest', () => {
    const setFieldValue = vi.fn();
    let api!: DslFormFillApi;
    render(
      <DslFormFillProvider setFieldValue={setFieldValue} lockedFields={['reason']}>
        <Harness onReady={(a) => (api = a)} />
      </DslFormFillProvider>,
    );

    api.applyFields({ reason: 'family matter', type: 'annual' });

    expect(setFieldValue).toHaveBeenCalledTimes(1);
    expect(setFieldValue).toHaveBeenCalledWith('type', 'annual');
    expect(setFieldValue).not.toHaveBeenCalledWith('reason', 'family matter');
  });

  it('exposes the locked field codes to consumers', () => {
    let api!: DslFormFillApi;
    render(
      <DslFormFillProvider setFieldValue={vi.fn()} lockedFields={['a', 'b']}>
        <Harness onReady={(x) => (api = x)} />
      </DslFormFillProvider>,
    );
    expect(api.lockedFields).toEqual(['a', 'b']);
  });

  it('defaults lockedFields to an empty array and applies all values when none locked', () => {
    const setFieldValue = vi.fn();
    let api!: DslFormFillApi;
    render(
      <DslFormFillProvider setFieldValue={setFieldValue}>
        <Harness onReady={(x) => (api = x)} />
      </DslFormFillProvider>,
    );
    expect(api.lockedFields).toEqual([]);

    api.applyFields({ a: 1 });
    expect(setFieldValue).toHaveBeenCalledWith('a', 1);
  });
});
