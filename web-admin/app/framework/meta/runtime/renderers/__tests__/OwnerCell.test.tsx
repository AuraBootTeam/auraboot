import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// Avoid network in unit tests: teams resolve empty, user lookup "fails" → id fallback.
vi.mock('~/shared/services/teamService', () => ({ fetchTeams: vi.fn().mockResolvedValue([]) }));
vi.mock('~/shared/services/http-client', () => ({ get: vi.fn().mockResolvedValue({ code: '1' }) }));

import { OwnerCell } from '../OwnerCell';
import { cellRendererRegistry } from '../CellRendererRegistry';

describe('OwnerCell — polymorphic owner display', () => {
  it('renders the team icon (👥) + id fallback for a team owner', async () => {
    const { container } = render(<OwnerCell ownerType="team" ownerId="team-1" />);
    await waitFor(() => expect(container.querySelector('svg')).toBeTruthy());
    expect(container.textContent).toContain('team-1');
  });

  it('renders the user icon (👤) + id fallback for a user owner', async () => {
    const { container } = render(<OwnerCell ownerType="user" ownerId="user-9" />);
    await waitFor(() => expect(container.querySelector('svg')).toBeTruthy());
    expect(container.textContent).toContain('user-9');
  });

  it('renders "-" when owner is unset', () => {
    const { container } = render(<OwnerCell />);
    expect(container.textContent).toBe('-');
    expect(container.querySelector('svg')).toBeFalsy();
  });
});

describe('owner cell renderer — reads sibling owner_type from the row', () => {
  it('renders via the registry using record.owner_type', async () => {
    const node = cellRendererRegistry.render('owner', {
      value: 'team-1',
      record: { owner_type: 'team', owner_id: 'team-1' },
      column: { field: 'owner_id', valueType: 'owner' },
    });
    const { container } = render(<>{node}</>);
    await waitFor(() => expect(container.querySelector('svg')).toBeTruthy());
    expect(container.textContent).toContain('team-1');
  });
});
