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
      variables: { _startUserId: 'u-100' },
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
      variables: { _startUserId: 'u-100' },
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

  it('prefers top-level instance.startUserId over variables._startUserId (Fix A)', () => {
    // Backend ProcessInstanceStatusDTO now projects startUserId at the top
    // level (Fix A). When both the top-level field and the legacy variables
    // entry disagree, the top-level field wins — it mirrors SmartEngine's
    // canonical ProcessInstance.startUserId and is not subject to caller-
    // controlled variable plumbing.
    const i = instance({
      startUserId: 'u-100',
      variables: { _startUserId: 'u-999', startUserId: 'u-888' },
    });
    expect(resolvePermissions(i, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      true,
    );
    expect(resolvePermissions(i, { id: 'u-999', permissions: [] }).canWithdraw).toBe(
      false,
    );
  });

  it('falls back to variables._startUserId when top-level startUserId is absent (legacy backend)', () => {
    // Backwards compatibility window: backends that have not yet adopted Fix A
    // still emit only the variables entry. Layer 2 must keep working.
    const i = instance({ variables: { _startUserId: 'u-100' } });
    expect(i.startUserId).toBeUndefined();
    expect(resolvePermissions(i, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      true,
    );
  });

  it('prefers backend canonical _startUserId over SmartEngine-native startUserId', () => {
    // Both keys present but disagree — _startUserId wins because that is what
    // BpmIntegrationService writes (and AssigneeResolverService reads first).
    const i = instance({
      variables: { _startUserId: 'u-100', startUserId: 'u-999' },
    });
    expect(resolvePermissions(i, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      true,
    );
    expect(resolvePermissions(i, { id: 'u-999', permissions: [] }).canWithdraw).toBe(
      false,
    );
  });

  it('falls back to startUserId when _startUserId is absent (SmartEngine-native start)', () => {
    const i = instance({ variables: { startUserId: 'u-100' } });
    expect(resolvePermissions(i, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      true,
    );
  });

  it('does NOT read initiatorUserId / applicantUserId (those are notification payload keys, never process variables)', () => {
    // Guards against the pre-fix behaviour that probed these keys. Backend
    // never writes them to process variables — only to event-bus notification
    // payloads — so trusting them would have been a dead branch.
    const i1 = instance({ variables: { initiatorUserId: 'u-100' } });
    expect(resolvePermissions(i1, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      false,
    );
    const i2 = instance({ variables: { applicantUserId: 'u-100' } });
    expect(resolvePermissions(i2, { id: 'u-100', permissions: [] }).canWithdraw).toBe(
      false,
    );
  });

  it('treats withdraw reason as blocked when initiator variable is missing', () => {
    const i = instance({ variables: {} });
    const result = resolvePermissions(i, { id: 'u-100', permissions: [] });
    expect(result.canWithdraw).toBe(false);
    expect(result.reasonsBlocked?.withdraw).toBe('user.notInitiator');
  });

  // ---- Fix B: canTerminate cases ----

  it('allows terminate when bpm.admin is held on a running instance', () => {
    const i = instance({
      currentNodes: [node({ assignee: 'u-200' })],
    });
    const result = resolvePermissions(i, {
      id: 'u-admin',
      permissions: [BPM_ADMIN_PERMISSION],
    });
    expect(result.canTerminate).toBe(true);
    expect(result.reasonsBlocked).toBeUndefined();
  });

  it('blocks terminate for non-admin users on a running instance (even assignees)', () => {
    const i = instance({
      currentNodes: [node({ assignee: 'u-200' })],
    });
    const result = resolvePermissions(i, { id: 'u-200', permissions: [] });
    expect(result.canTerminate).toBe(false);
    expect(result.reasonsBlocked?.terminate).toBe('user.notBpmAdmin');
  });

  it('blocks terminate when the instance has ended, with instance.notRunning reason', () => {
    const i = instance({ status: 'approved' });
    const result = resolvePermissions(i, {
      id: 'u-admin',
      permissions: [BPM_ADMIN_PERMISSION],
    });
    expect(result.canTerminate).toBe(false);
    expect(result.reasonsBlocked?.terminate).toBe('instance.notRunning');
  });

  it('blocks terminate for initiator without bpm.admin (OSS conservative; spec 4 reserved)', () => {
    const i = instance({
      startUserId: 'u-100',
      currentNodes: [node({ assignee: 'u-200' })],
    });
    const result = resolvePermissions(i, { id: 'u-100', permissions: [] });
    // Initiator can withdraw but CANNOT terminate in OSS scope.
    expect(result.canWithdraw).toBe(true);
    expect(result.canTerminate).toBe(false);
    expect(result.reasonsBlocked?.terminate).toBe('user.notBpmAdmin');
  });
});
