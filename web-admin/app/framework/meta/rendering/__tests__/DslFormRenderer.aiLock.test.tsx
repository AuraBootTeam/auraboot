/** The SDK wrapper must not register a handler against its separate draft state. */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

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
  return <div data-testid="actual-page">Page owns its form state</div>;
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

describe('DslFormRenderer form state ownership', () => {
  beforeEach(() => {
    capturedHandler = null;
  });
  it('does not register a fill handler against the disconnected SDK values', () => {
    const setFieldValue = vi.fn();
    render(<DslFormRenderer form={stubForm({ setFieldValue })} />);
    expect(screen.getByTestId('actual-page')).toBeVisible();
    expect(capturedHandler).toBeNull();
    expect(setFieldValue).not.toHaveBeenCalled();
  });
});
