import { describe, it, expect } from 'vitest';
import { commandRequestMetadata, projectCommandPayload, commandPolicyFor } from '../commandRequestPolicy';

describe('command-owned request policy', () => {
  it('retains the identity of an uncertain request and changes it with the intent', () => {
    const policy = { idempotent: true, versionField: 'row_version' };
    const first = commandRequestMetadata(policy, { row_version: 3 }, { title: 'Draft' });
    const retry = commandRequestMetadata(
      policy,
      { row_version: 3 },
      { title: 'Draft' },
      first.identity,
    );
    expect(retry.metadata.clientRequestId).toBe(first.metadata.clientRequestId);
    expect(retry.metadata.expectedVersion).toBe(3);
    const edit = commandRequestMetadata(
      policy,
      { row_version: 3 },
      { title: 'Changed' },
      first.identity,
    );
    expect(edit.metadata.clientRequestId).not.toBe(first.metadata.clientRequestId);
  });
  it('projects only declared inputs, preserving explicit clearing and false', () => {
    expect(
      projectCommandPayload(
        { title: '', pinned: false, status: 'published', row_version: 3 },
        { payloadFields: ['title', 'pinned'] },
      ),
    ).toEqual({ title: '', pinned: false });
    expect(projectCommandPayload({ status: 'draft' }, { payloadFields: [] })).toEqual({});
  });
  it('leaves existing pages unchanged and never invents a version', () => {
    expect(commandRequestMetadata(undefined, { row_version: 8 }, {})).toEqual({ metadata: {} });
    expect(
      commandRequestMetadata({ versionField: 'row_version' }, { row_version: '8' }, {}).metadata,
    ).toEqual({});
    const payload = { name: 'Existing' };
    expect(projectCommandPayload(payload)).toBe(payload);
  });
});

it('scopes dialog payload fields to their command without widening publish or delete', () => {
  const fallback = { idempotent: true, payloadFields: [] };
  const overrides = { 'announcement:update_announcement': { idempotent: true, payloadFields: ['ack_required'] } };
  const raw = { pid: 'notice', status: 'draft', ack_required: false };
  expect(projectCommandPayload(raw, commandPolicyFor(fallback, overrides, 'announcement:update_announcement')))
    .toEqual({ ack_required: false });
  expect(projectCommandPayload(raw, commandPolicyFor(fallback, overrides, 'announcement:publish'))).toEqual({});
  expect(commandPolicyFor(undefined, undefined, 'other:action')).toBeUndefined();
});

it('does not resend hidden record selections when a dialog owns the input contract', () => {
  const record = { audience_kind: 'selected', audience_users: '["old"]', status: 'draft' };
  const policy = { inputFieldsOnly: true, payloadFields: ['audience_kind', 'audience_users'] };
  expect(projectCommandPayload(record, policy, { audience_kind: 'all' })).toEqual({ audience_kind: 'all' });
  expect(projectCommandPayload(record, policy, { audience_kind: 'selected', audience_users: [] }))
    .toEqual({ audience_kind: 'selected', audience_users: [] });
});
