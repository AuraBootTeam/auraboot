/**
 * BpmPermissionService.test.ts
 *
 * Unit tests for the three-layer BPM operations permission derivation:
 *   1. running instance + initiator match → canWithdraw.
 *   2. running instance + assignee match  → canApprove / canReject / canCc.
 *   3. bpm.admin override unlocks every action on a running instance.
 *   4. terminal instance disables everything, even with bpm.admin.
 *   5. anonymous user (no pid) blocks every action.
 *   6. comma-separated assignee lists are honoured.
 *
 * The service is pure, so tests construct {@link BpmInstanceForRecord} literals
 * directly without touching the network.
 */

import { describe, it, expect } from 'vitest';

import {
  resolvePermissions,
  BPM_ADMIN_PERMISSION,
  type CurrentUserForPermission,
} from '../BpmPermissionService';
import type {
  BpmInstanceForRecord,
  BpmNodeStatus,
} from '../bpmWorkbenchService';

function node(partial: Partial<BpmNodeStatus> = {}): BpmNodeStatus {
  return {
    nodeId: partial.nodeId ?? 'nodeA',
    type: partial.type ?? 'userTask',
    name: partial.name ?? null,
    status: partial.status ?? 'running',
    assignee: partial.assignee ?? null,
    completedAt: partial.completedAt ?? null,
    completedBy: partial.completedBy ?? null,
  };
}

function instance(overrides: Partial<BpmInstanceForRecord> = {}): BpmInstanceForRecord {
  return {
    instanceId: 'pi-001',
    processDefinitionId: 'pd-alpha',
    status: 'running',
    currentNodes: [],
    completedNodes: [],
    variables: {},
    ...overrides,
  };
}

const anonymousUser: CurrentUserForPermission = { id: null, permissions: [] };

describe('BpmPermissionService.resolvePermissions', () => {
  it('allows withdraw when running instance initiator matches current user', () => {
    const i = instance({
      variables: { startUserId: 'u-100' },
      currentNodes: [node({ nodeId: 'approver', assignee: 'u-200' })],
    });
    const result = resolvePermissions(i, { id: 'u-100', permissions: [] });

    expect(result.canWithdraw).toBe(true);
    expect(result.canApprove).toBe(false);
    expect(result.canReject).toBe(false);
    expect(result.canCc).toBe(false);
    expect(result.reasonsBlocked?.approve).toBe('user.notAssignee');
    expect(result.reasonsBlocked?.cc).toBe('user.notAssignee');
    expect(result.reasonsBlocked?.withdraw).toBeUndefined();
  });

  it('allows approve / reject / cc when current user is an active assignee', () => {
    const i = instance({
      variables: { startUserId: 'u-100' },
      currentNodes: [node({ nodeId: 'approver', assignee: 'u-200' })],
    });
    const result = resolvePermissions(i, { id: 'u-200', permissions: [] });

    expect(result.canApprove).toBe(true);
    expect(result.canReject).toBe(true);
    expect(result.canCc).toBe(true);
    expect(result.canWithdraw).toBe(false);
    expect(result.reasonsBlocked?.withdraw).toBe('user.notInitiator');
  });

  it('honours comma-separated multi-assignee candidate lists', () => {
    const i = instance({
      currentNodes: [node({ assignee: 'u-300,u-400 , u-500' })],
    });
    const result = resolvePermissions(i, { id: 'u-400', permissions: [] });
    expect(result.canApprove).toBe(true);
    expect(result.canReject).toBe(true);
    expect(result.canCc).toBe(true);
  });

  it('grants every action when bpm.admin is held on a running instance', () => {
    const i = instance({
      variables: {},
      currentNodes: [node({ assignee: 'u-999' })],
    });
    const result = resolvePermissions(i, {
      id: 'u-200',
      permissions: [BPM_ADMIN_PERMISSION],
    });

    expect(result.canApprove).toBe(true);
    expect(result.canReject).toBe(true);
    expect(result.canWithdraw).toBe(true);
    expect(result.canCc).toBe(true);
    expect(result.reasonsBlocked).toBeUndefined();
  });

  it('blocks every action when the instance has ended, even for bpm.admin', () => {
    const i = instance({ status: 'approved' });
    const result = resolvePermissions(i, {
      id: 'u-200',
      permissions: [BPM_ADMIN_PERMISSION],
    });

    expect(result.canApprove).toBe(false);
    expect(result.canReject).toBe(false);
    expect(result.canWithdraw).toBe(false);
    expect(result.canCc).toBe(false);
    expect(result.reasonsBlocked?.approve).toBe('instance.notRunning');
    expect(result.reasonsBlocked?.withdraw).toBe('instance.notRunning');
  });

  it('blocks every action when the user is anonymous', () => {
    const i = instance({
      currentNodes: [node({ assignee: 'u-200' })],
    });
    const result = resolvePermissions(i, anonymousUser);

    expect(result.canApprove).toBe(false);
    expect(result.canReject).toBe(false);
    expect(result.canWithdraw).toBe(false);
    expect(result.canCc).toBe(false);
    expect(result.reasonsBlocked?.approve).toBe('user.anonymous');
    expect(result.reasonsBlocked?.withdraw).toBe('user.anonymous');
  });

  it('falls back to initiatorUserId / applicantUserId when startUserId is absent', () => {
    const i1 = instance({ variables: { initiatorUserId: 'u-100' } });
    expect(resolvePermissions(i1, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      true,
    );

    const i2 = instance({ variables: { applicantUserId: 'u-100' } });
    expect(resolvePermissions(i2, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      true,
    );
  });

  it('treats withdraw reason as blocked when initiator variable is missing', () => {
    const i = instance({ variables: {} });
    const result = resolvePermissions(i, { id: 'u-100', permissions: [] });
    expect(result.canWithdraw).toBe(false);
    expect(result.reasonsBlocked?.withdraw).toBe('user.notInitiator');
  });
});
