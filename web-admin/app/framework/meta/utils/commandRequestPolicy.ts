/** Opt-in request contract for command-owned models. Identity survives uncertain retries. */
export interface CommandRequestPolicy {
  idempotent?: boolean;
  versionField?: string;
  payloadFields?: string[];
  inputFieldsOnly?: boolean;
}

export interface CommandRequestIdentity {
  intent: string;
  clientRequestId: string;
}

export function projectCommandPayload(
  payload: Record<string, unknown>,
  policy?: CommandRequestPolicy,
  dialogInputs?: Record<string, unknown>,
): Record<string, unknown> {
  if (policy?.inputFieldsOnly && dialogInputs) payload = dialogInputs;
  if (!Array.isArray(policy?.payloadFields)) return payload;
  return Object.fromEntries(
    policy.payloadFields
      .filter((key) => Object.hasOwn(payload, key))
      .map((key) => [key, payload[key]]),
  );
}

export function commandRequestMetadata(
  policy: CommandRequestPolicy | undefined,
  record: Record<string, unknown> | null | undefined,
  intent: unknown,
  previous?: CommandRequestIdentity,
): { metadata: Record<string, unknown>; identity?: CommandRequestIdentity } {
  const metadata: Record<string, unknown> = {};
  const version = policy?.versionField ? record?.[policy.versionField] : undefined;
  if (typeof version === 'number' && Number.isInteger(version) && version > 0) {
    metadata.expectedVersion = version;
  }
  if (!policy?.idempotent) return { metadata };
  const fingerprint = JSON.stringify(intent);
  const identity =
    previous?.intent === fingerprint
      ? previous
      : {
          intent: fingerprint,
          clientRequestId: crypto.randomUUID(),
        };
  metadata.clientRequestId = identity.clientRequestId;
  return { metadata, identity };
}

/** Dialog commands may declare their own payload contract without broadening sibling actions. */
export function commandPolicyFor(
  fallback: CommandRequestPolicy | undefined,
  overrides: Record<string, CommandRequestPolicy> | undefined,
  command: string,
): CommandRequestPolicy | undefined {
  return overrides?.[command] ?? fallback;
}
