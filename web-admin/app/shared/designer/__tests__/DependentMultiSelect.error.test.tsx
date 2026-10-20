/**
 * Tests for DependentMultiSelect error-state surfacing.
 *
 * Verifies that when the underlying resource service rejects, the component
 * renders a localized error message + retry affordance instead of silently
 * showing an empty dropdown (§8 / §10).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import React from 'react';

// ---------------------------------------------------------------------------
// Mock external dependencies before importing the component under test.
// ---------------------------------------------------------------------------

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: string, fallback?: string) => fallback ?? key,
}));

const { fetchFieldOptionsMock, fetchDictOptionsMock, fetchFieldDictOptionsMock, useFlowStoreMock } = vi.hoisted(() => ({
  fetchFieldOptionsMock: vi.fn(),
  fetchDictOptionsMock: vi.fn(),
  fetchFieldDictOptionsMock: vi.fn(),
  useFlowStoreMock: vi.fn(),
}));

vi.mock('~/shared/services/resourceSelectService', () => ({
  fetchFieldOptions: fetchFieldOptionsMock,
  fetchDictOptions: fetchDictOptionsMock,
  fetchFieldDictOptions: fetchFieldDictOptionsMock,
}));

vi.mock('~/plugins/core-designer/components/flow-designer-sdk/store', () => ({
  useFlowStore: useFlowStoreMock,
}));

import { DependentMultiSelect } from '../DependentMultiSelect';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeAdapter(value: unknown = [], setValue: (v: unknown) => void = vi.fn()) {
  return { value, setValue, error: undefined, required: false, disabled: false };
}

const NODE_WITH_MODEL = {
  id: 'node-1',
  data: { config: { modelCode: 'order' } },
};

beforeEach(() => {
  fetchFieldOptionsMock.mockReset();
  fetchDictOptionsMock.mockReset();
  fetchFieldDictOptionsMock.mockReset();
  useFlowStoreMock.mockReturnValue({ nodes: [NODE_WITH_MODEL], selectedNodeId: 'node-1' });
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('DependentMultiSelect — error state', () => {
  it('renders error message when fetchFieldOptions rejects', async () => {
    fetchFieldOptionsMock.mockRejectedValue(new Error('Network error'));

    render(
      <DependentMultiSelect
        adapter={makeAdapter()}
        label="Watch Fields"
        optionSource="fields"
      />,
    );

    await waitFor(() =>
      expect(screen.getByText('Failed to load options')).toBeInTheDocument(),
    );
    // Must NOT render a silent empty dropdown
    expect(screen.queryByPlaceholderText(/Select/)).not.toBeInTheDocument();
  });

  it('renders a retry button when an error occurs', async () => {
    fetchFieldOptionsMock.mockRejectedValue(new Error('Network error'));

    render(
      <DependentMultiSelect
        adapter={makeAdapter()}
        label="Watch Fields"
        optionSource="fields"
      />,
    );

    await waitFor(() => expect(screen.getByText('Failed to load options')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('clears the error and retries when retry button is clicked', async () => {
    // First fetch: rejects → error state
    fetchFieldOptionsMock.mockRejectedValueOnce(new Error('Network error'));

    render(
      <DependentMultiSelect
        adapter={makeAdapter()}
        label="Watch Fields"
        optionSource="fields"
      />,
    );

    // Wait for the error banner to appear
    const errorMsg = await screen.findByText('Failed to load options');
    expect(errorMsg).toBeInTheDocument();
    const retryBtn = screen.getByRole('button', { name: 'Retry' });
    expect(retryBtn).toBeInTheDocument();

    // Set up second fetch to succeed before clicking retry
    fetchFieldOptionsMock.mockResolvedValueOnce([
      { label: 'Status', value: 'status', description: 'string' },
    ]);

    await act(async () => {
      fireEvent.click(retryBtn);
    });

    // After successful retry the error banner should be gone and input visible
    await waitFor(() =>
      expect(screen.queryByText('Failed to load options')).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });

  it('renders error message when fetchDictOptions rejects', async () => {
    fetchDictOptionsMock.mockRejectedValue(new Error('Network error'));

    render(
      <DependentMultiSelect
        adapter={makeAdapter()}
        label="From States"
        optionSource="dict"
        dictCode="approval_state"
      />,
    );

    await waitFor(() =>
      expect(screen.getByText('Failed to load options')).toBeInTheDocument(),
    );
  });

  it('renders dropdown when options load successfully', async () => {
    fetchFieldOptionsMock.mockResolvedValue([
      { label: 'Name', value: 'name', description: 'string' },
      { label: 'Status', value: 'status', description: 'enum' },
    ]);

    render(
      <DependentMultiSelect
        adapter={makeAdapter()}
        label="Watch Fields"
        optionSource="fields"
      />,
    );

    await waitFor(() =>
      expect(screen.queryByText('Failed to load options')).not.toBeInTheDocument(),
    );
    // The tag input should be present
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });
});

describe('DependentMultiSelect model dictionary cascade', () => {
  it('resolves model and state field and prevents stale options after a model switch', async () => {
    let resolveOld!: (options: unknown[]) => void;
    fetchFieldDictOptionsMock.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce([{label: 'Approved', value: 'approved'}]);
    const setValue = vi.fn();
    const adapter = makeAdapter(['in_review'], setValue);
    useFlowStoreMock.mockReturnValue({nodes: [{id: 'node-1', data: {config: {modelCode: 'task', stateField: 'status'}}}], selectedNodeId: 'node-1'});
    const view = render(<DependentMultiSelect adapter={adapter} dependsOnKey="stateField" optionSource="dict" />);
    await waitFor(() => expect(fetchFieldDictOptionsMock).toHaveBeenCalledWith('task', 'status'));
    useFlowStoreMock.mockReturnValue({nodes: [{id: 'node-1', data: {config: {modelCode: 'approval', stateField: 'status'}}}], selectedNodeId: 'node-1'});
    view.rerender(<DependentMultiSelect adapter={adapter} dependsOnKey="stateField" optionSource="dict" />);
    await waitFor(() => expect(fetchFieldDictOptionsMock).toHaveBeenCalledWith('approval', 'status'));
    fireEvent.focus(screen.getByRole('textbox'));
    await screen.findByRole('button', {name: 'Approved'});
    await act(async () => resolveOld([{label: 'Stale task state', value: 'old'}]));
    expect(screen.queryByText('Stale task state')).not.toBeInTheDocument();
    expect(screen.getByRole('button', {name: 'Approved'})).toBeInTheDocument();
    expect(setValue).toHaveBeenCalledWith([]);
    expect(fetchDictOptionsMock).not.toHaveBeenCalled();
  });
});
