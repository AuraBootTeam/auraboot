import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('~/shared/services/teamService', () => ({
  fetchTeams: vi.fn().mockResolvedValue([
    { pid: 'team-1', name: '销售一组' },
    { pid: 'team-2', name: '销售二组' },
  ]),
}));
// UserSelect is reused for the user mode; stub it so this suite stays focused on
// OwnerSelect's mode-switching and the team picker (UserSelect has its own tests).
vi.mock('../UserSelect', () => ({
  UserSelect: (props: any) => <div data-testid="stub-userselect">{props.value || 'user-picker'}</div>,
}));

import { OwnerSelect } from '../OwnerSelect';

describe('OwnerSelect — mode driven by owner_type', () => {
  it('prompts to pick a type when owner_type is empty', () => {
    render(<OwnerSelect name="owner_id" context={{ record: {} }} onChange={() => {}} />);
    expect(screen.getByTestId('owner-select-pick-type-first-owner_id')).toBeInTheDocument();
  });

  it('renders the user picker when owner_type=user', () => {
    render(
      <OwnerSelect name="owner_id" context={{ record: { owner_type: 'user' } }} onChange={() => {}} />,
    );
    expect(screen.getByTestId('stub-userselect')).toBeInTheDocument();
  });

  it('renders the ab_team dropdown and emits the team pid on select', async () => {
    const onChange = vi.fn();
    render(
      <OwnerSelect
        name="owner_id"
        context={{ record: { owner_type: 'team' } }}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByTestId('owner-select-team-trigger-owner_id'));
    await waitFor(() =>
      expect(screen.getByTestId('owner-select-team-option-owner_id-team-1')).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId('owner-select-team-option-owner_id-team-1'));
    expect(onChange).toHaveBeenCalledWith('team-1');
  });
});
