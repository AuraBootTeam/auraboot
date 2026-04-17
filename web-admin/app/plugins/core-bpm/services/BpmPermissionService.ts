/**
 * BpmPermissionService - Task 13 of the OSS BPM closure spec.
 *
 * Computes whether the current user may invoke each of the four BpmPanel
 * operations (approve / reject / withdraw / cc) against a given process
 * instance. The result drives the disabled state of the buttons in
 * {@link BpmOperationsSection} so that the UI never surfaces an action that
 * will unambiguously fail at the backend.
 *
 * Three-layer derivation (see plan § "BpmPermissionService"):
 *
 *   Layer 1 — Action-level required permissions.
 *     Node-level {@code aura.requiredPermissions} from BPMN extensions is
 *     defined in {@code BpmExtensionKeys} but is NOT currently projected into
 *     {@link BpmInstanceForRecord}. Rather than invent a field that isn't on
 *     the wire, Layer 1 is a no-op today: the backend still enforces per-node
 *     permissions on the {@code /approve|/reject|/withdraw|/cc} handlers, so
 *     the UI only provides "best-effort" gating via Layers 2 and 3. When the
 *     backend starts to emit a {@code requiredPermissions} array on the DTO,
 *     extend this layer to block and record a reason.
 *
 *   Layer 2 — Identity-derived.
 *     - {@code canWithdraw}: instance is running AND currentUser is the
 *       process initiator, resolved from variables (backend canonical key
 *       {@code _startUserId} per {@code ApprovalChainExecutor}, with
 *       {@code startUserId} as the SmartEngine-native fallback).
 *     - {@code canApprove} / {@code canReject}: instance is running AND
 *       currentUser id matches at least one current-node assignee.
 *     - {@code canCc}: same as approve/reject (assignees are always allowed to
 *       CC; CcPolicy further restricts server-side).
 *
 *   Layer 3 — IAM override.
 *     - The {@code bpm.admin} permission bypasses Layer 2 entirely - operator
 *       intervention is always permitted on a running instance. Terminal
 *       instances (non-running) still have every operation disabled, because
 *       none of the four endpoints make sense once the process ends.
 *
 * The function is pure and synchronous. It never throws on missing fields -
 * blank / absent assignee / initiator simply evaluates to "not allowed" with
 * an accompanying {@code reasonsBlocked} entry so the UI can surface a helpful
 * tooltip.
 *
 * @since BPM closure spec 1 (Task 13)
 */

import type { BpmInstanceForRecord } from './bpmWorkbenchService';

/** Non-running instance statuses - Operations section disables every action. */
const RUNNING_STATUS = 'running';

/**
 * Variable keys that may carry the process initiator's user id.
 *
 * Used as a *fallback* once Fix A (backend DTO {@code startUserId} projection)
 * is in place — preferred lookup is {@code instance.startUserId} which mirrors
 * SmartEngine's {@code ProcessInstance.startUserId} and is always populated.
 *
 * Variable-key fallback ordered by backend preference: {@code _startUserId} is
 * the canonical key written by {@code ApprovalChainExecutor}
 * ({@code platform/.../chain/ApprovalChainExecutor.java:90}) and consumed first
 * by {@code AssigneeResolverService} (lines 123-125). {@code startUserId} is
 * the legacy fallback written by {@code BpmIntegrationService} for SmartEngine-
 * native starts. We intentionally do NOT probe {@code initiatorUserId} /
 * {@code applicantUserId} — those are notification-payload keys on the event
 * bus, never written to process variables, so probing them was dead code.
 *
 * The variable-key fallback may be removed once every deployed backend emits
 * the top-level {@code startUserId} field (Fix A rolled out everywhere).
 */
const INITIATOR_VARIABLE_KEYS = ['_startUserId', 'startUserId'] as const;

/** IAM permission code that unconditionally unlocks every BPM operation. */
export const BPM_ADMIN_PERMISSION = 'bpm.admin';

/** Reason keys surfaced through {@link BpmPermissionResult.reasonsBlocked}. */
export type BpmPermissionReason =
  | 'instance.notRunning'
  | 'user.anonymous'
  | 'user.notInitiator'
  | 'user.notAssignee';

/** Actions keyed by {@link BpmPermissionResult.reasonsBlocked}. */
export type BpmPermissionAction = 'approve' | 'reject' | 'withdraw' | 'cc';

/** Result of {@link resolvePermissions}. */
export interface BpmPermissionResult {
  canApprove: boolean;
  canReject: boolean;
  canWithdraw: boolean;
  canCc: boolean;
  /**
   * Per-action reason code explaining why the action is blocked. Only
   * populated for actions where {@code canXxx === false}. Keys are i18n key
   * suffixes ({@code bpm.permission.blocked.*}); callers decide how to render.
   */
  reasonsBlocked?: Partial<Record<BpmPermissionAction, BpmPermissionReason>>;
}

/** Current user shape consumed by {@link resolvePermissions}. */
export interface CurrentUserForPermission {
  /** User pid; blank / undefined means the viewer is unauthenticated. */
  id: string | null | undefined;
  /** IAM permission codes attached to the current user. */
  permissions: readonly string[];
}

/**
 * Extract the process-initiator user id from the instance.
 *
 * Resolution order:
 *   1. {@code instance.startUserId} — the canonical, top-level field emitted by
 *      {@code ProcessInstanceStatusDTO} (Fix A). This mirrors SmartEngine
 *      {@code ProcessInstance.startUserId} verbatim and is always populated for
 *      newly-started instances regardless of how the caller passed variables.
 *   2. {@code variables._startUserId} / {@code variables.startUserId} — legacy
 *      fallback for backends that have not yet rolled out Fix A. Removable
 *      once every deployment emits the top-level field.
 *
 * Any non-blank string representation wins; numbers/booleans are coerced via
 * {@code String()} and then validated.
 */
function resolveInitiatorId(instance: BpmInstanceForRecord): string | null {
  // Layer 2 preferred: backend-projected top-level field.
  if (instance.startUserId !== undefined && instance.startUserId !== null) {
    const text = String(instance.startUserId).trim();
    if (text.length > 0) return text;
  }
  // Backward-compatibility fallback: probe process variables.
  const variables = instance.variables;
  if (!variables) return null;
  for (const key of INITIATOR_VARIABLE_KEYS) {
    const raw = variables[key];
    if (raw === undefined || raw === null) continue;
    const text = String(raw).trim();
    if (text.length > 0) return text;
  }
  return null;
}

/**
 * Compute whether the current user is a declared assignee on any currently
 * active node of the instance. Compares user id against
 * {@code NodeStatus.assignee} as a raw string; assignees emitted as comma- or
 * semicolon-separated lists are split so any component match counts. Blank /
 * unassigned nodes never match.
 */
function isCurrentUserAssignee(
  instance: BpmInstanceForRecord,
  currentUserId: string,
): boolean {
  const id = currentUserId.trim();
  if (id.length === 0) return false;
  for (const node of instance.currentNodes) {
    const assignee = node.assignee;
    if (!assignee) continue;
    // Split on comma / semicolon so multi-assignee candidate lists all count.
    const candidates = assignee
      .split(/[,;]/)
      .map((token) => token.trim())
      .filter((token) => token.length > 0);
    if (candidates.includes(id)) return true;
  }
  return false;
}

/**
 * Compute per-action permissions for a BPM instance + current user pair.
 *
 * @see the file-level comment for the three-layer derivation.
 */
export function resolvePermissions(
  instance: BpmInstanceForRecord,
  currentUser: CurrentUserForPermission,
): BpmPermissionResult {
  const reasonsBlocked: Partial<Record<BpmPermissionAction, BpmPermissionReason>> = {};

  // Terminal instances short-circuit every action. Layer 3 does NOT override
  // this - an admin cannot approve or withdraw a process that has already
  // ended; the endpoints would reject it too.
  const isRunning = instance.status === RUNNING_STATUS;
  if (!isRunning) {
    reasonsBlocked.approve = 'instance.notRunning';
    reasonsBlocked.reject = 'instance.notRunning';
    reasonsBlocked.withdraw = 'instance.notRunning';
    reasonsBlocked.cc = 'instance.notRunning';
    return {
      canApprove: false,
      canReject: false,
      canWithdraw: false,
      canCc: false,
      reasonsBlocked,
    };
  }

  const userId = currentUser.id ? String(currentUser.id).trim() : '';
  const anonymous = userId.length === 0;
  const hasBpmAdmin = currentUser.permissions.includes(BPM_ADMIN_PERMISSION);

  // Layer 3 short-circuit: bpm.admin unlocks everything on a running instance.
  if (hasBpmAdmin) {
    return {
      canApprove: true,
      canReject: true,
      canWithdraw: true,
      canCc: true,
    };
  }

  if (anonymous) {
    reasonsBlocked.approve = 'user.anonymous';
    reasonsBlocked.reject = 'user.anonymous';
    reasonsBlocked.withdraw = 'user.anonymous';
    reasonsBlocked.cc = 'user.anonymous';
    return {
      canApprove: false,
      canReject: false,
      canWithdraw: false,
      canCc: false,
      reasonsBlocked,
    };
  }

  // Layer 2 — identity-derived.
  const initiatorId = resolveInitiatorId(instance);
  const isInitiator = initiatorId !== null && initiatorId === userId;
  const isAssignee = isCurrentUserAssignee(instance, userId);

  const canApprove = isAssignee;
  const canReject = isAssignee;
  const canWithdraw = isInitiator;
  const canCc = isAssignee;

  if (!canApprove) reasonsBlocked.approve = 'user.notAssignee';
  if (!canReject) reasonsBlocked.reject = 'user.notAssignee';
  if (!canWithdraw) reasonsBlocked.withdraw = 'user.notInitiator';
  if (!canCc) reasonsBlocked.cc = 'user.notAssignee';

  const result: BpmPermissionResult = {
    canApprove,
    canReject,
    canWithdraw,
    canCc,
  };
  if (Object.keys(reasonsBlocked).length > 0) {
    result.reasonsBlocked = reasonsBlocked;
  }
  return result;
}
