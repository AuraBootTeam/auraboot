import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SkillGovernancePanel } from '../SkillGovernancePanel';
const mock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get: mock.get, post: mock.post }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US' }) }));
const view = () => ({
  draftHash: 'reviewed-hash',
  managed: true,
  readOnly: false,
  legacyStatus: 'draft',
  publication: { versionPid: 'version-2', version: '2' },
  validationErrors: [],
  changes: { prompt_template: { published: 'Previous guidance', draft: 'New guidance' } },
  versions: [
    { pid: 'version-1', version: '1', operation: 'PUBLISH', operationByDisplayName: 'Editor' },
  ],
  trials: [],
});
const props = {
  pid: 'owned-skill',
  dirty: false,
  canPublish: true,
  revision: 0,
  onManaged: vi.fn(),
  onPublished: vi.fn(),
};
beforeEach(() => {
  mock.get.mockReset();
  mock.post.mockReset();
  mock.get.mockResolvedValue({ code: '0', data: view() });
});
describe('Skill publication decisions', () => {
  it('requires review, sends the reviewed draft and publication identities once, and requires renewed consent', async () => {
    mock.post.mockResolvedValue({
      code: '0',
      data: { ...view(), publication: { versionPid: 'version-3', version: '3' } },
    });
    render(<SkillGovernancePanel {...props} />);
    const publish = await screen.findByRole('button', { name: 'Publish reviewed draft' });
    expect(publish).toBeDisabled();
    expect(screen.getByText('Previous guidance')).toBeInTheDocument();
    fireEvent.click(
      screen.getByLabelText(
        'I reviewed the draft changes and validation, and authorize publication for future runs.',
      ),
    );
    fireEvent.click(publish);
    await waitFor(() =>
      expect(mock.post).toHaveBeenCalledExactlyOnceWith(
        '/api/agent/skill-definitions/owned-skill/publish',
        {
          expectedDraftHash: 'reviewed-hash',
          expectedPublishedVersion: 'version-2',
          description: 'Publish reviewed definition',
        },
      ),
    );
    expect(
      await screen.findByText('Active version changed. Unpublished edits are retained.'),
    ).toBeInTheDocument();
    expect(publish).toBeDisabled();
  });
  it('only restores an explicitly fetched and reviewed version', async () => {
    mock.get.mockImplementation(async (path: string) => ({
      code: '0',
      data: path.includes('/versions/')
        ? {
            pid: 'version-1',
            version: '1',
            schemaSnapshot: { prompt_template: 'Original guidance' },
          }
        : view(),
    }));
    mock.post.mockResolvedValue({ code: '0', data: view() });
    render(<SkillGovernancePanel {...props} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Review this version' }));
    expect(await screen.findByText('Original guidance')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Activate reviewed version' })).toBeDisabled();
    fireEvent.click(
      screen.getByLabelText(
        'I reviewed the historical definition and will activate it while retaining the draft.',
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Activate reviewed version' }));
    await waitFor(() =>
      expect(mock.post).toHaveBeenCalledExactlyOnceWith(
        '/api/agent/skill-definitions/owned-skill/versions/version-1/activate',
        {
          expectedDraftHash: 'reviewed-hash',
          expectedPublishedVersion: 'version-2',
          description: 'Restore version 1',
        },
      ),
    );
  });
  it('keeps dirty drafts, unavailable tools and viewers from publishing', async () => {
    const { rerender } = render(<SkillGovernancePanel {...props} dirty />);
    expect(await screen.findByRole('button', { name: 'Publish reviewed draft' })).toBeDisabled();
    rerender(<SkillGovernancePanel {...props} canPublish={false} />);
    expect(screen.getByRole('checkbox')).toBeDisabled();
    mock.get.mockResolvedValue({
      code: '0',
      data: { ...view(), validationErrors: ['Tool unavailable'] },
    });
    rerender(<SkillGovernancePanel {...props} revision={1} />);
    expect(await screen.findByText('Tool unavailable')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: 'Publish reviewed draft' })).toBeDisabled();
    expect(mock.post).not.toHaveBeenCalled();
  });
  it('shows a rejected stale decision and never retries automatically', async () => {
    mock.post.mockResolvedValue({ code: '409', message: 'Skill changed; reload and review' });
    render(<SkillGovernancePanel {...props} />);
    await screen.findByRole('button', { name: 'Publish reviewed draft' });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Publish reviewed draft' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Skill changed; reload and review');
    expect(mock.post).toHaveBeenCalledTimes(1);
  });
});
