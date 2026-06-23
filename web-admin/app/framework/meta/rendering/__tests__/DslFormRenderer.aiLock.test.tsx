/**
 * DslFormRenderer.aiLock.test.tsx
 *
 * DslFormRenderer is the single mount point for both AI-fill apply seams:
 *   1. the DslFormFillProvider (consumed by the ai-fill-banner block), and
 *   2. the AuraBot form-fill handler (chat AI populating the form).
 * Both must honour fields marked `props.aiLocked` in the loaded schema (D5).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useDslFormFill } from '../DslFormFillContext';

// Capture the form-fill handler that DslFormRenderer registers with AuraBot.
let capturedHandler: ((fields: Record<string, unknown>) => void) | null = null;
vi.mock('~/plugins/core-aurabot/hooks/useAuraBotSafe', () => ({
  useAuraBotSafe: () => ({
    registerFormFillHandler: (h: (f: Record<string, unknown>) => void) => {
      capturedHandler = h;
    },
    unregisterFormFillHandler: () => {
      capturedHandler = null;
    },
  }),
}));

// A fake form page renderer that surfaces the lockedFields the provider exposes,
// so we can assert DslFormRenderer threads the schema lock set into the provider.
function FakeFormPage() {
  const { lockedFields } = useDslFormFill();
  return <div data-testid="locked-codes">{lockedFields.join(',')}</div>;
}

const fakeProfile = {
  name: 'admin',
  pageRenderers: new Map<string, React.ComponentType>([['form', FakeFormPage]]),
  skeletons: new Map(),
};
// profileRegistry + ProfileProvider now live in @auraboot/runtime-kernel; mock
// that module (preserving its other exports via importActual) so DslFormRenderer
// resolves the fake admin profile + a passthrough ProfileProvider.
vi.mock('@auraboot/runtime-kernel', async (importActual) => ({
  ...(await importActual<typeof import('@auraboot/runtime-kernel')>()),
  profileRegistry: { resolve: () => fakeProfile, get: () => fakeProfile, register: () => {} },
  ProfileProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { DslFormRenderer } from '../DslFormRenderer';

function stubForm(overrides: Record<string, unknown> = {}) {
  return {
    loading: false,
    error: null,
    enabled: true,
    schema: {
      kind: 'form',
      blocks: [
        { blockType: 'field', field: 'wd_req_reason', props: { aiLocked: true } },
        { blockType: 'field', field: 'wd_req_type', props: {} },
      ],
    },
    rendererProps: {},
    setFieldValue: vi.fn(),
    ...overrides,
  } as never;
}

describe('DslFormRenderer AI lock wiring', () => {
  beforeEach(() => {
    capturedHandler = null;
  });

  it('threads schema locked field codes into the fill provider', () => {
    render(<DslFormRenderer form={stubForm()} />);
    expect(screen.getByTestId('locked-codes').textContent).toBe('wd_req_reason');
  });

  it('AuraBot form-fill handler skips locked fields and applies the rest', () => {
    const setFieldValue = vi.fn();
    render(<DslFormRenderer form={stubForm({ setFieldValue })} />);

    expect(typeof capturedHandler).toBe('function');
    capturedHandler!({ wd_req_reason: 'family matter', wd_req_type: 'annual' });

    expect(setFieldValue).toHaveBeenCalledTimes(1);
    expect(setFieldValue).toHaveBeenCalledWith('wd_req_type', 'annual');
    expect(setFieldValue).not.toHaveBeenCalledWith('wd_req_reason', 'family matter');
  });
});
