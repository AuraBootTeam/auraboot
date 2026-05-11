/**
 * ResultContractView.test.tsx
 *
 * Pins renderer dispatch for each renderHint variant (table / summary / card /
 * timeline / fallback) and verifies status badge + skillCode + durationMs
 * display. Type shape matches the backend DTO at
 * auraboot/platform/src/main/java/com/auraboot/framework/agent/dto/ResultContract.java.
 */

import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ResultContractView } from '../ResultContractView';
import type { ResultContract } from '../../types/ResultContract';

afterEach(() => {
  document.body.innerHTML = '';
});

const baseContract: ResultContract = {
  outputType: 'structured_result',
  actionability: 'read_only',
  status: 'success',
  skillCode: 'dsl.query',
  durationMs: 142,
};

describe('ResultContractView', () => {
  it('renders status + skillCode + durationMs header', () => {
    render(<ResultContractView contract={baseContract} />);
    expect(screen.getByText('success')).toBeInTheDocument();
    expect(screen.getByText('dsl.query')).toBeInTheDocument();
    expect(screen.getByText('142ms')).toBeInTheDocument();
  });

  it('renders table when renderHint=table', () => {
    const c: ResultContract = {
      ...baseContract,
      renderHint: 'table',
      table: [
        { pid: '01REC1', name: 'Acme', total: 100 },
        { pid: '01REC2', name: 'Globex', total: 250 },
      ],
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByTestId('rc-table')).toBeInTheDocument();
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('Globex')).toBeInTheDocument();
    // Headers
    const headers = screen.getAllByRole('columnheader');
    expect(headers.map((h: HTMLElement) => h.textContent)).toEqual(['pid', 'name', 'total']);
  });

  it('renders protocol metadata for operator inspection', () => {
    const c: ResultContract = {
      ...baseContract,
      outputType: 'structured_result',
      actionability: 'read_only',
      renderHint: 'table',
      table: [
        { pid: '01REC1', name: 'Acme' },
        { pid: '01REC2', name: 'Globex' },
      ],
    };
    render(<ResultContractView contract={c} />);
    const meta = screen.getByTestId('rc-protocol-meta');
    expect(meta).toHaveTextContent('output: structured_result');
    expect(meta).toHaveTextContent('action: read_only');
    expect(meta).toHaveTextContent('render: table');
    expect(meta).toHaveTextContent('rows: 2');
  });

  it('falls back to data.records when table rows are nested there', () => {
    const c: ResultContract = {
      ...baseContract,
      renderHint: 'table',
      data: {
        records: [
          { pid: '01REC1', name: 'Nested Acme', total: 100 },
          { pid: '01REC2', name: 'Nested Globex', total: 250 },
        ],
      },
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByTestId('rc-table')).toBeInTheDocument();
    expect(screen.getByText('Nested Acme')).toBeInTheDocument();
    expect(screen.getByText('Nested Globex')).toBeInTheDocument();
  });

  it('renders summary when renderHint=summary', () => {
    const c: ResultContract = {
      ...baseContract,
      renderHint: 'summary',
      textSummary: 'Found 42 active leads last month.',
      data: { totalLeads: 42, activeLeads: 30 },
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByTestId('rc-summary')).toBeInTheDocument();
    expect(screen.getByText('Found 42 active leads last month.')).toBeInTheDocument();
    expect(screen.getByText('totalLeads')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('renders card when renderHint=card', () => {
    const c: ResultContract = {
      ...baseContract,
      renderHint: 'card',
      textSummary: 'Customer: Acme Corp',
      data: { industry: 'Manufacturing', tier: 'Gold' },
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByTestId('rc-card')).toBeInTheDocument();
    expect(screen.getByText('Customer: Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('Manufacturing')).toBeInTheDocument();
  });

  it('renders timeline when renderHint=timeline', () => {
    const c: ResultContract = {
      ...baseContract,
      renderHint: 'timeline',
      data: {
        events: [
          { at: '2026-04-18T09:00', label: 'Lead created' },
          { at: '2026-04-18T14:30', label: 'First contact' },
        ],
      },
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByTestId('rc-timeline')).toBeInTheDocument();
    expect(screen.getByText('Lead created')).toBeInTheDocument();
    expect(screen.getByText('First contact')).toBeInTheDocument();
  });

  it('falls back to JSON when no renderHint and no textSummary', () => {
    const c: ResultContract = {
      ...baseContract,
      data: { some: 'raw', payload: 123 },
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByTestId('rc-json')).toBeInTheDocument();
  });

  it('renders suggested actions as pills', () => {
    const c: ResultContract = {
      ...baseContract,
      renderHint: 'summary',
      textSummary: 'Done.',
      suggestedActions: [
        { label: 'Create follow-up', skillCode: 'crm.activity.create' },
        { label: 'Export', skillCode: 'dsl.query' },
      ],
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByTestId('rc-suggested-actions')).toBeInTheDocument();
    expect(screen.getByText('Create follow-up')).toBeInTheDocument();
    expect(screen.getByText('Export')).toBeInTheDocument();
  });

  it('failed status uses red styling', () => {
    const c: ResultContract = {
      ...baseContract,
      status: 'failed',
      renderHint: 'summary',
      textSummary: 'Query failed: timeout',
    };
    render(<ResultContractView contract={c} />);
    const statusLabel = screen.getByText('failed');
    expect(statusLabel.className).toContain('text-red-600');
  });

  it('partial_success uses yellow styling', () => {
    const c: ResultContract = {
      ...baseContract,
      status: 'partial_success',
      renderHint: 'table',
      table: [{ a: 1 }],
    };
    render(<ResultContractView contract={c} />);
    const statusLabel = screen.getByText('partial_success');
    expect(statusLabel.className).toContain('text-yellow-600');
  });

  it('truncates table to first 20 rows and shows overflow indicator', () => {
    const rows = Array.from({ length: 25 }, (_, i) => ({ id: i, value: `row-${i}` }));
    const c: ResultContract = {
      ...baseContract,
      renderHint: 'table',
      table: rows,
    };
    render(<ResultContractView contract={c} />);
    expect(screen.getByText('row-0')).toBeInTheDocument();
    expect(screen.getByText('row-19')).toBeInTheDocument();
    expect(screen.queryByText('row-20')).not.toBeInTheDocument();
    expect(screen.getByText(/5 more rows/)).toBeInTheDocument();
  });
});
