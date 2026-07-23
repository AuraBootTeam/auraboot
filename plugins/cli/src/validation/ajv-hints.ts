import type { ErrorObject } from 'ajv';

/**
 * Turn a raw AJV schema error into an agent-actionable hint — the `expected`
 * value and an imperative `agentInstruction` an AI agent can apply directly
 * (see toAgentErrorReport). Covers the schema mistakes agents make most often:
 * wrong enum value, unknown property (typo), missing required property, wrong type.
 */

export interface AjvHint {
  expected?: string;
  agentInstruction?: string;
}

function at(err: ErrorObject): string {
  return err.instancePath ? err.instancePath : 'the value';
}

export function ajvHint(err: ErrorObject): AjvHint {
  const params = (err.params ?? {}) as Record<string, unknown>;

  switch (err.keyword) {
    case 'enum': {
      const allowed = params.allowedValues;
      if (Array.isArray(allowed) && allowed.length > 0) {
        const list = allowed.map((v) => String(v)).join(', ');
        return { expected: list, agentInstruction: `Set ${at(err)} to one of: ${list}.` };
      }
      return {};
    }
    case 'additionalProperties': {
      const prop = params.additionalProperty;
      if (typeof prop === 'string' && prop) {
        return {
          agentInstruction: `Remove the unknown property "${prop}" at ${at(err)} (check for a typo against the allowed fields).`,
        };
      }
      return {};
    }
    case 'required': {
      const prop = params.missingProperty;
      if (typeof prop === 'string' && prop) {
        return { expected: prop, agentInstruction: `Add the required property "${prop}" at ${at(err)}.` };
      }
      return {};
    }
    case 'type': {
      const t = params.type;
      if (t) {
        const type = Array.isArray(t) ? t.join(' | ') : String(t);
        return { expected: type, agentInstruction: `Change ${at(err)} to type ${type}.` };
      }
      return {};
    }
    default:
      return {};
  }
}
