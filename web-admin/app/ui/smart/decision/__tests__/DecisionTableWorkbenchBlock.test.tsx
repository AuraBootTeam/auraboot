import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DecisionTableWorkbenchBlock } from '../DecisionTableWorkbenchBlock';

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => http,
}));

const analysis = {
  valid: true,
  errors: [],
  warnings: [
    {
      code: 'DMN_CONTINUOUS_DOMAIN',
      severity: 'WARNING',
      ruleIds: [],
      inputCombination: { input: 'amount' },
      message: "Input 'amount' is decimal without allowedValues",
    },
  ],
  metrics: {
    ruleCount: 2,
    gapCount: 0,
    overlapCount: 0,
    conflictCount: 0,
    unreachableRuleCount: 0,
    finiteCombinationCount: 0,
    finiteDomainComplete: false,
  },
};

function mockWorkbenchApi() {
  http.get.mockImplementation((endpoint: string) => {
    if (endpoint === '/decision/definitions/visual_table') {
      return Promise.resolve({ data: null });
    }
    if (endpoint === '/decision/definitions/visual_table/versions') {
      return Promise.resolve({
        data: [
          {
            pid: 'version-1',
            decisionCode: 'visual_table',
            version: 1,
            versionTag: 'draft-1',
            status: 'VALIDATED',
          },
        ],
      });
    }
    return Promise.resolve({ data: {} });
  });
  http.post.mockImplementation((endpoint: string) => {
    if (endpoint === '/decision/definitions') {
      return Promise.resolve({ data: { decisionCode: 'visual_table' } });
    }
    if (endpoint === '/decision/definitions/visual_table/versions') {
      return Promise.resolve({
        data: {
          pid: 'version-1',
          decisionCode: 'visual_table',
          version: 1,
          versionTag: 'draft-1',
          status: 'DRAFT',
        },
      });
    }
    if (endpoint === '/decision/versions/version-1/validate') {
      return Promise.resolve({ data: { valid: true, errors: [], warnings: [], fieldRefs: [] } });
    }
    if (endpoint === '/decision/tables/analyze') {
      return Promise.resolve({ data: analysis });
    }
    if (endpoint === '/decision/test-run') {
      return Promise.resolve({
        data: {
          status: 'MATCHED',
          matched: true,
          outputs: { route: 'director' },
          matchedRules: [{ ruleId: 'high-value' }],
        },
      });
    }
    return Promise.resolve({ data: {} });
  });
  http.delete.mockResolvedValue({ data: {} });
}

function renderWorkbench() {
  return render(
    <MemoryRouter initialEntries={['/p/decisionops_tables?decisionCode=visual_table']}>
      <DecisionTableWorkbenchBlock
        block={{
          props: {
            mode: 'workbench',
            initialDecisionCode: 'visual_table',
            initialDecisionName: 'Visual Table',
            initialVersionTag: 'draft-1',
          },
        }}
      />
    </MemoryRouter>,
  );
}

describe('DecisionTableWorkbenchBlock', () => {
  beforeEach(() => {
    http.get.mockReset();
    http.post.mockReset();
    http.delete.mockReset();
    mockWorkbenchApi();
  });

  it('creates a definition if needed, saves a platform decision-table draft, and validates it', async () => {
    renderWorkbench();

    fireEvent.click(screen.getByTestId('dtw-save-draft'));

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/definitions/visual_table', undefined),
    );
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/definitions', expect.objectContaining({
        decisionCode: 'visual_table',
        decisionName: 'Visual Table',
        scopeType: 'GOVERNANCE',
        ownerModule: 'decision',
      })),
    );
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith(
        '/decision/definitions/visual_table/versions',
        expect.objectContaining({
          kind: 'DECISION_TABLE',
          runtimeAdapter: 'PLATFORM_DECISION_TABLE',
          versionTag: 'draft-1',
          contentJson: expect.objectContaining({ hitPolicy: 'FIRST' }),
        }),
      ),
    );
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/versions/version-1/validate', undefined),
    );
    expect(await screen.findByTestId('dtw-workflow-message')).toHaveTextContent('草稿已保存并校验通过');
    expect(screen.getByTestId('dtw-version-card')).toHaveTextContent('VALIDATED');
  });

  it('runs backend analysis and test-run through the platform decision-table adapter', async () => {
    renderWorkbench();

    fireEvent.click(screen.getByTestId('dt-analyze'));

    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/tables/analyze', expect.objectContaining({
        decisionCode: 'visual_table',
        model: expect.objectContaining({ hitPolicy: 'FIRST' }),
      })),
    );
    expect(await screen.findByTestId('dt-analysis-panel')).toHaveTextContent('DMN_CONTINUOUS_DOMAIN');

    fireEvent.click(screen.getByTestId('dtw-test-run'));

    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith('/decision/test-run', expect.objectContaining({
        kind: 'DECISION_TABLE',
        runtimeAdapter: 'PLATFORM_DECISION_TABLE',
        contentJson: expect.objectContaining({ hitPolicy: 'FIRST' }),
        context: expect.objectContaining({
          record: expect.objectContaining({
            data: expect.objectContaining({ amount: 20000 }),
          }),
        }),
      })),
    );
    expect(await screen.findByTestId('dtw-test-result')).toHaveTextContent('命中');
    expect(screen.getByTestId('dtw-test-result')).not.toHaveTextContent('MATCHED');
    expect(screen.getByTestId('dtw-test-result')).not.toHaveTextContent('matched=true');
    expect(screen.getByTestId('dtw-test-result')).toHaveTextContent('director');
  });

  it('accepts local FEEL built-in literals before backend analysis', () => {
    renderWorkbench();

    fireEvent.change(screen.getByLabelText('feel-0-amount'), {
      target: { value: 'date(2026, 06, 10)' },
    });

    expect(screen.queryByTestId('dtw-local-diagnostics')).toBeNull();
  });

  it('shows local unsupported FEEL diagnostics for non-whitelisted expressions', () => {
    renderWorkbench();

    fireEvent.change(screen.getByLabelText('feel-0-amount'), {
      target: { value: 'if amount > 10 then 1 else 0' },
    });

    expect(screen.getByTestId('dtw-local-diagnostics')).toHaveTextContent('DMN_UNSUPPORTED_FEEL');
  });
});
