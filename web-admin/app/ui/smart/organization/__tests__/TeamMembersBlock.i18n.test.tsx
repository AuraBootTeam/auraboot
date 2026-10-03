import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TeamMembersBlock } from '../TeamMembersBlock';
import teamPage from '../../../../../../plugins/org-management/config/pages/ab_team_detail.json';

const f = vi.hoisted(() => ({ locale: 'en-US', fetch: vi.fn(), add: vi.fn(), remove: vi.fn(), post: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: f.locale, t: (key: string) => key }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showSuccessToast: f.success, showErrorToast: f.error }) }));
vi.mock('~/shared/services/teamService', () => ({ fetchTeamMembers: f.fetch, addTeamMember: f.add, removeTeamMember: f.remove }));
vi.mock('~/shared/services/http-client', () => ({ post: f.post }));
const member = { pid: 'row-1', memberPid: 'member-1', userId: 'user-1', userName: 'Aaron', userEmail: 'aaron@example.test', role: 'leader', joinedAt: '2026-06-24T00:00:00Z' };
const candidate = { pid: 'member-2', userId: 'user-2', user: { username: 'Bea', email: 'bea@example.test' } };
const candidateResult = { code: '0', data: { records: [{ pid: 'member-1', userId: 'user-1' }, candidate] } };
const configured = teamPage.blocks.find(b => b.id === 'team_members')!;
const runtime = { getContext: () => ({ record: { pid: 'team-1' }, $page: { recordPid: 'wrong-fallback' } }) };
const legacyConfigured = { ...configured, props: { ...configured.props, title: 'Members' } };
function view(localized = false) { return render(<TeamMembersBlock block={localized ? configured : legacyConfigured} runtime={runtime} />); }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
beforeEach(() => { vi.resetAllMocks(); f.locale = 'en-US'; f.fetch.mockResolvedValue([member]); f.post.mockResolvedValue(candidateResult); f.add.mockResolvedValue({}); f.remove.mockResolvedValue(undefined); vi.spyOn(window, 'confirm').mockReturnValue(true); });

describe('team-member DSL title, localized states and API payloads', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    const english = locale === 'en-US';
    it(`${locale} resolves the imported DSL title, table labels and unchanged record/member identity`, async () => {
      f.locale = locale; view(true); await screen.findByText('Aaron');
      expect(screen.getByRole('heading')).toHaveTextContent(english ? 'Team members (1)' : '\u56e2\u961f\u6210\u5458 (1)');
      expect(screen.getByRole('button', { name: english ? 'Add member' : '\u6dfb\u52a0\u6210\u5458' })).toBeEnabled();
      expect(screen.getByText(english ? 'Leader' : '\u8d1f\u8d23\u4eba')).toBeTruthy();
      expect(screen.getAllByRole('columnheader').map((e: HTMLElement) => e.textContent)).toEqual(english ? ['User', 'Email', 'Role', 'Joined', 'Actions'] : ['\u7528\u6237', '\u90ae\u7bb1', '\u89d2\u8272', '\u52a0\u5165\u65f6\u95f4', '\u64cd\u4f5c']);
      expect(screen.getByTestId('team-members-remove-member-1')).toHaveAttribute('title', english ? 'Remove member' : '\u79fb\u9664\u6210\u5458');
      expect(f.fetch).toHaveBeenCalledExactlyOnceWith('team-1');
    });
    it(`${locale} distinguishes pending, successful empty and missing team states`, async () => {
      f.locale = locale; const pending = deferred<typeof member[]>(); f.fetch.mockReturnValue(pending.promise); const v = view();
      expect(screen.getByRole('status')).toHaveAttribute('aria-label', english ? 'Loading members...' : '\u6b63\u5728\u52a0\u8f7d\u6210\u5458...');
      expect(screen.getByTestId('team-members-add')).toBeDisabled();
      await act(async () => { pending.resolve([]); await pending.promise; });
      expect(screen.getByText(english ? 'No team members' : '\u6682\u65e0\u56e2\u961f\u6210\u5458')).toBeTruthy();
      expect(screen.queryByRole('alert')).toBeNull(); v.unmount();
      render(<TeamMembersBlock runtime={{ getContext: () => ({ record: {} }) }} />);
      expect(screen.getByText(english ? 'No team record found. Members cannot be loaded.' : '\u672a\u627e\u5230\u56e2\u961f\u8bb0\u5f55，\u65e0\u6cd5\u52a0\u8f7d\u6210\u5458。')).toBeTruthy();
      expect(f.fetch).toHaveBeenCalledTimes(1);
    });
    it(`${locale} adds only an available tenant member using memberPid and the member role`, async () => {
      f.locale = locale; view(); await screen.findByText('Aaron'); fireEvent.click(screen.getByTestId('team-members-add'));
      expect(screen.getByRole('dialog', { name: english ? 'Add team member' : '\u6dfb\u52a0\u56e2\u961f\u6210\u5458' })).toBeTruthy();
      const select = await screen.findByTestId('team-members-select') as HTMLSelectElement;
      expect(Array.from(select.options).map(o => o.value)).toEqual(['', 'member-2']);
      expect(select.options[0].textContent).toBe(english ? 'Select a user' : '\u8bf7\u9009\u62e9\u7528\u6237');
      fireEvent.change(select, { target: { value: 'member-2' } }); fireEvent.click(screen.getByTestId('team-members-confirm'));
      await waitFor(() => expect(f.add).toHaveBeenCalledExactlyOnceWith('team-1', { memberPid: 'member-2', role: 'member' }));
      expect(f.success).toHaveBeenCalledWith(english ? 'Member added to the team' : '\u6210\u5458\u5df2\u52a0\u5165\u56e2\u961f');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      expect(f.post).toHaveBeenCalledExactlyOnceWith('/api/tenant/members/search', { status: 'active', pageNum: 1, pageSize: 100 });
    });
    it(`${locale} supports the existing candidate response shapes and distinguishes a successful empty list`, async () => {
      f.locale = locale;
      const withoutEmail = { ...candidate, user: { username: 'Bea' } };
      for (const data of [[withoutEmail], { content: [withoutEmail] }, { records: [] }]) {
        f.post.mockResolvedValueOnce({ code: '0', data }); const v = view(); await screen.findByText('Aaron'); fireEvent.click(screen.getByTestId('team-members-add'));
        if (Array.isArray(data) || 'content' in data) {
          const select = await screen.findByTestId('team-members-select') as HTMLSelectElement;
          expect(Array.from(select.options).map(o => o.value)).toEqual(['', 'member-2']);
          expect(select.options[1].textContent).toBe(english ? 'Bea (No email)' : 'Bea (\u65e0\u90ae\u7bb1)');
        } else {
          expect(await screen.findByText(english ? 'No available members to add.' : '\u6682\u65e0\u53ef\u52a0\u5165\u7684\u6210\u5458\u3002')).toBeTruthy();
          expect(screen.getByTestId('team-members-confirm')).toBeDisabled();
        }
        expect(screen.queryByRole('alert')).toBeNull(); expect(f.add).not.toHaveBeenCalled(); v.unmount();
      }
      expect(f.post).toHaveBeenCalledTimes(3);
    });
    it(`${locale} preserves selection after denied add and localizes the non-Error fallback`, async () => {
      f.locale = locale; f.add.mockRejectedValue('denied'); view(); await screen.findByText('Aaron'); fireEvent.click(screen.getByTestId('team-members-add'));
      const select = await screen.findByTestId('team-members-select'); fireEvent.change(select, { target: { value: 'member-2' } }); fireEvent.click(screen.getByTestId('team-members-confirm'));
      await waitFor(() => expect(f.error).toHaveBeenCalledWith(english ? 'Unable to add member' : '\u6dfb\u52a0\u6210\u5458\u5931\u8d25'));
      expect(screen.getByRole('dialog')).toBeTruthy(); expect(select).toHaveValue('member-2'); expect(f.success).not.toHaveBeenCalled(); expect(f.fetch).toHaveBeenCalledTimes(1);
    });
    it(`${locale} localizes confirmation and cancel/denied remove never produce successful side effects`, async () => {
      f.locale = locale; view(); await screen.findByText('Aaron'); vi.mocked(window.confirm).mockReturnValue(false);
      fireEvent.click(screen.getByTestId('team-members-remove-member-1'));
      expect(window.confirm).toHaveBeenLastCalledWith(english ? 'Remove Aaron from the team?' : '\u786e\u8ba4\u5c06 Aaron \u79fb\u51fa\u56e2\u961f？'); expect(f.remove).not.toHaveBeenCalled();
      vi.mocked(window.confirm).mockReturnValue(true); f.remove.mockRejectedValue('denied'); fireEvent.click(screen.getByTestId('team-members-remove-member-1'));
      await waitFor(() => expect(f.error).toHaveBeenCalledWith(english ? 'Unable to remove member' : '\u79fb\u9664\u6210\u5458\u5931\u8d25'));
      expect(f.remove).toHaveBeenCalledExactlyOnceWith('team-1', 'member-1'); expect(f.success).not.toHaveBeenCalled(); expect(screen.getByText('Aaron')).toBeTruthy(); expect(f.fetch).toHaveBeenCalledTimes(1);
    });
    it(`${locale} main-list failure is visible and one explicit retry recovers without reporting empty success`, async () => {
      f.locale = locale; f.fetch.mockRejectedValueOnce(new Error('network unavailable')); view();
      expect(await screen.findByTestId('team-members-load-error')).toHaveTextContent(english ? 'Unable to load team members' : '\u56e2\u961f\u6210\u5458\u52a0\u8f7d\u5931\u8d25');
      expect(screen.getByTestId('team-members-add')).toBeDisabled(); expect(screen.queryByText(english ? 'No team members' : '\u6682\u65e0\u56e2\u961f\u6210\u5458')).toBeNull(); expect(f.fetch).toHaveBeenCalledTimes(1);
      fireEvent.click(screen.getByTestId('team-members-retry')); await screen.findByText('Aaron'); expect(screen.queryByRole('alert')).toBeNull(); expect(f.fetch).toHaveBeenCalledTimes(2);
    });
    for (const failure of ['denied', 'missing-data', 'invalid-shape', 'network']) {
      it(`${locale} candidate ${failure} is a disabled failure state and explicit retry restores valid choices`, async () => {
        f.locale = locale;
        if (failure === 'network') f.post.mockRejectedValueOnce(new Error('offline'));
        else f.post.mockResolvedValueOnce(failure === 'denied' ? { code: '403', message: 'Denied' } : failure === 'missing-data' ? { code: '0' } : { code: '0', data: {} });
        view(); await screen.findByText('Aaron'); fireEvent.click(screen.getByTestId('team-members-add'));
        expect(await screen.findByTestId('team-members-candidate-error')).toHaveTextContent(english ? 'Unable to load available members' : '\u53ef\u9009\u6210\u5458\u52a0\u8f7d\u5931\u8d25');
        expect(screen.getByTestId('team-members-confirm')).toBeDisabled(); expect(screen.queryByText(english ? 'No available members to add.' : '\u6682\u65e0\u53ef\u52a0\u5165\u7684\u6210\u5458。')).toBeNull(); expect(f.add).not.toHaveBeenCalled(); expect(f.post).toHaveBeenCalledTimes(1);
        fireEvent.click(screen.getByTestId('team-members-candidate-retry')); expect(await screen.findByTestId('team-members-select')).toBeTruthy(); expect(f.post).toHaveBeenCalledTimes(2); expect(screen.queryByTestId('team-members-candidate-error')).toBeNull();
      });
    }
  }
  it('changes locale without losing selected member or refetching either list', async () => {
    const v = view(true); await screen.findByText('Aaron'); fireEvent.click(screen.getByTestId('team-members-add')); const select = await screen.findByTestId('team-members-select'); fireEvent.change(select, { target: { value: 'member-2' } });
    f.locale = 'zh-CN'; v.rerender(<TeamMembersBlock block={configured} runtime={runtime} />);
    expect(screen.getByRole('heading', { name: '\u6dfb\u52a0\u56e2\u961f\u6210\u5458' })).toBeTruthy(); expect(screen.getByRole('heading', { name: '\u56e2\u961f\u6210\u5458 (1)' })).toBeTruthy(); expect(select).toHaveValue('member-2'); expect(f.post).toHaveBeenCalledTimes(1); expect(f.fetch).toHaveBeenCalledTimes(1);
  });
  it('a delayed result for the old team cannot replace the new team members', async () => {
    const old = deferred<typeof member[]>(); f.fetch.mockReturnValueOnce(old.promise).mockResolvedValueOnce([{ ...member, userName: 'Current team' }]); const v = view();
    v.rerender(<TeamMembersBlock block={legacyConfigured} runtime={{ getContext: () => ({ record: { pid: 'team-2' } }) }} />); await screen.findByText('Current team');
    await act(async () => { old.resolve([{ ...member, userName: 'Old team' }]); await old.promise; });
    expect(screen.getByText('Current team')).toBeTruthy(); expect(screen.queryByText('Old team')).toBeNull(); expect(f.fetch.mock.calls.map(args => args[0])).toEqual(['team-1', 'team-2']);
  });
});
