/**
 * Dev-only mock SkillClient — keeps C-1 unblocked before C-2 ships the
 * real EchoSkill backend.
 *
 * Activation: `VITE_AURABOT_USE_MOCK=true` (dev only).
 * Removal: once C-2 is merged, flip the env var to false and delete this
 * file plus the dynamic import in `skillClient.ts::resolveSkillClient`.
 *
 * The mock implements just enough of the SPI to round-trip the
 * `echo` skill so the shell-e2e spec can prove the wire path end to end.
 */

import type { SkillMeta, SkillRequest, SkillResult } from '../types/skill';
import type { SkillClient } from './skillClient';

let traceCounter = 0;
const nextTraceId = () => `mock_trace_${Date.now()}_${++traceCounter}`;

function buildEchoResult(req: SkillRequest): SkillResult {
  const text = typeof req.params.text === 'string' ? req.params.text : '';
  return {
    status: 'SUCCESS',
    skillName: req.skillName,
    traceId: nextTraceId(),
    payload: { text },
    preview: null,
    previewToken: null,
    riskLevel: 'LOW',
    requireTextConfirm: null,
    undoToken: null,
    batchId: null,
    suggestions: [],
    streamUrl: null,
    errors: [],
  };
}

function unsupported(skillName: string): SkillResult {
  return {
    status: 'ERROR',
    skillName,
    traceId: nextTraceId(),
    payload: {},
    preview: null,
    previewToken: null,
    riskLevel: 'LOW',
    requireTextConfirm: null,
    undoToken: null,
    batchId: null,
    suggestions: [],
    streamUrl: null,
    errors: [{ code: 'SKILL_NOT_FOUND', message: `Mock has no skill: ${skillName}` }],
  };
}

const echoMeta: SkillMeta = {
  name: 'echo',
  displayName: 'Echo',
  category: 'debug',
  riskLevel: 'LOW',
  paramsSchema: {
    type: 'object',
    properties: { text: { type: 'string' } },
    required: ['text'],
  },
  requiredPermissions: [],
  supportsUndo: false,
  supportsDryRun: false,
  supportsStreaming: false,
};

export const mockSkillClient: SkillClient = {
  async list() {
    return [echoMeta];
  },
  async dryRun(req) {
    if (req.skillName === 'echo') return buildEchoResult(req);
    return unsupported(req.skillName);
  },
  async execute(req) {
    if (req.skillName === 'echo') return buildEchoResult(req);
    return unsupported(req.skillName);
  },
  async undo() {
    return {
      status: 'ERROR',
      skillName: 'undo',
      traceId: nextTraceId(),
      payload: {},
      preview: null,
      previewToken: null,
      riskLevel: 'LOW',
      requireTextConfirm: null,
      undoToken: null,
      batchId: null,
      suggestions: [],
      streamUrl: null,
      errors: [{ code: 'SKILL_NOT_FOUND', message: 'Mock does not implement undo' }],
    };
  },
  async batchUndo() {
    return {
      status: 'ERROR',
      skillName: 'batch-undo',
      traceId: nextTraceId(),
      payload: {},
      preview: null,
      previewToken: null,
      riskLevel: 'LOW',
      requireTextConfirm: null,
      undoToken: null,
      batchId: null,
      suggestions: [],
      streamUrl: null,
      errors: [{ code: 'SKILL_NOT_FOUND', message: 'Mock does not implement batch-undo' }],
    };
  },
  attachStream() {
    // Mock has no streaming skills today; return a no-op disposer.
    return () => {
      /* no-op */
    };
  },
};

/** Helper used in unit tests to assert the round-trip without any DOM. */
export async function echoRoundTrip(text: string): Promise<string> {
  const result = await mockSkillClient.execute({
    skillName: 'echo',
    params: { text },
    context: {
      route: '/',
      modelCode: null,
      pageId: null,
      selectedElement: null,
      recentOperations: [],
      lastCreatedResources: [],
    },
    idempotencyKey: 'mock-key',
    previewToken: null,
  });
  const echoed = result.payload.text;
  return typeof echoed === 'string' ? echoed : '';
}
