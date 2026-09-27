import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { verifyRegisteredDeploymentCapabilities } from './deployment-capability-verifier.mjs';

const hash = bytes => `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
const digestPattern = /^sha256:[0-9a-f]{64}$/;
const idPattern = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const domain = 'auraboot.deployment-capabilities.attestation.v1\n';
function exact(value, keys, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) {
    throw new Error(`Invalid ${label} fields`);
  }
}
function protectedFields(value) {
  exact(value, ['schemaVersion', 'keyId', 'deploymentDigest', 'deploymentId', 'generation', 'issuedAt', 'expiresAt'], 'attestation');
  if (value.schemaVersion !== 1 || typeof value.keyId !== 'string' || !/^[a-zA-Z0-9._-]{1,128}$/.test(value.keyId)
      || !digestPattern.test(value.deploymentDigest) || !idPattern.test(value.deploymentId)
      || !Number.isSafeInteger(value.generation) || value.generation < 1
      || !Number.isSafeInteger(value.issuedAt) || value.issuedAt < 0
      || !Number.isSafeInteger(value.expiresAt) || value.expiresAt <= value.issuedAt) {
    throw new Error('Invalid attestation identity or validity interval');
  }
  return { schemaVersion: 1, keyId: value.keyId, deploymentDigest: value.deploymentDigest,
    deploymentId: value.deploymentId, generation: value.generation, issuedAt: value.issuedAt, expiresAt: value.expiresAt };
}
const signedBytes = value => Buffer.from(domain + JSON.stringify(protectedFields(value)));

/** Sign an observation supplied by the caller; this does not perform runtime observation. */
export function signDeploymentObservation(deploymentBytes, metadata, privateKeyPem) {
  const deployment = JSON.parse(deploymentBytes.toString());
  const fields = protectedFields({ schemaVersion: 1, keyId: metadata.keyId, deploymentDigest: hash(deploymentBytes),
    deploymentId: deployment.deploymentId, generation: deployment.generation,
    issuedAt: metadata.issuedAt, expiresAt: metadata.expiresAt });
  const key = createPrivateKey(privateKeyPem);
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('Ed25519 observation signing key required');
  return { ...fields, signature: sign(null, signedBytes(fields), key).toString('base64') };
}

/** Verify source authorization before using the existing registered capability decision. */
export function verifyAttestedDeploymentCapabilities(applicationBytes, platformBytes, deploymentBytes, envelopeBytes, policyBytes, expected) {
  if (!digestPattern.test(expected?.trustPolicyDigest ?? '') || hash(policyBytes) !== expected.trustPolicyDigest) {
    throw new Error('Trust policy bytes differ from externally pinned digest');
  }
  if (!Number.isSafeInteger(expected.now) || expected.now < 0) throw new Error('Trusted verifier time required');
  const policy = JSON.parse(policyBytes.toString());
  exact(policy, ['schemaVersion', 'keys'], 'trust policy');
  if (policy.schemaVersion !== 1 || !Array.isArray(policy.keys) || !policy.keys.length) throw new Error('Nonempty versioned trust policy required');
  const keyIds = new Set();
  for (const entry of policy.keys) {
    exact(entry, ['keyId', 'publicKey', 'deploymentIds', 'platformReleaseDigests', 'revoked', 'maxAgeSeconds'], 'trust key');
    if (typeof entry.keyId !== 'string' || !/^[a-zA-Z0-9._-]{1,128}$/.test(entry.keyId) || keyIds.has(entry.keyId)
        || typeof entry.publicKey !== 'string' || typeof entry.revoked !== 'boolean'
        || !Number.isSafeInteger(entry.maxAgeSeconds) || entry.maxAgeSeconds < 1
        || !Array.isArray(entry.deploymentIds) || !entry.deploymentIds.length || entry.deploymentIds.some(id => typeof id !== 'string' || !idPattern.test(id))
        || !Array.isArray(entry.platformReleaseDigests) || !entry.platformReleaseDigests.length
        || entry.platformReleaseDigests.some(digest => typeof digest !== 'string' || !digestPattern.test(digest))
        || new Set(entry.deploymentIds).size !== entry.deploymentIds.length
        || new Set(entry.platformReleaseDigests).size !== entry.platformReleaseDigests.length) throw new Error('Invalid or duplicate trust key');
    keyIds.add(entry.keyId);
  }
  const envelope = JSON.parse(envelopeBytes.toString());
  exact(envelope, ['schemaVersion', 'keyId', 'deploymentDigest', 'deploymentId', 'generation', 'issuedAt', 'expiresAt', 'signature'], 'signed envelope');
  const { signature, ...rawFields } = envelope;
  const fields = protectedFields(rawFields);
  if (typeof signature !== 'string' || !/^[A-Za-z0-9+/]{86}==$/.test(signature)) throw new Error('Canonical Ed25519 signature required');
  const signatureBytes = Buffer.from(signature, 'base64');
  if (signatureBytes.length !== 64 || signatureBytes.toString('base64') !== signature) throw new Error('Canonical Ed25519 signature required');
  const authorization = policy.keys.find(key => key.keyId === fields.keyId);
  if (!authorization || authorization.revoked || !authorization.deploymentIds.includes(expected.deploymentId)
      || !authorization.platformReleaseDigests.includes(expected.platformReleaseDigest)) throw new Error('Observation signer is not authorized');
  if (fields.issuedAt > expected.now || fields.expiresAt <= expected.now
      || fields.expiresAt - fields.issuedAt > authorization.maxAgeSeconds) throw new Error('Observation validity interval rejected');
  if (fields.deploymentDigest !== hash(deploymentBytes) || fields.deploymentDigest !== expected.deploymentDigest
      || fields.deploymentId !== expected.deploymentId || fields.generation !== expected.generation) throw new Error('Signed observation identity mismatch');
  const publicKey = createPublicKey(authorization.publicKey);
  if (publicKey.asymmetricKeyType !== 'ed25519' || !verify(null, signedBytes(fields), publicKey, signatureBytes)) throw new Error('Invalid observation signature');
  const result = verifyRegisteredDeploymentCapabilities(applicationBytes, platformBytes, deploymentBytes, expected);
  return { ...result, observationAttestation: { keyId: fields.keyId, issuedAt: fields.issuedAt,
    expiresAt: fields.expiresAt, verifiedAt: expected.now, trustPolicyDigest: expected.trustPolicyDigest } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const targetContext = process.argv[2] === '--target-context';
  const args = process.argv.slice(targetContext ? 3 : 2);
  if (args.length !== 6) throw new Error('Usage: node deployment-observation-attestation.mjs [--target-context] <application.json> <platform.json> <deployment.json> <attestation.json> <trust-policy.json> <expected.json>');
  const expected = JSON.parse(readFileSync(args[5], 'utf8'));
  if (targetContext) {
    const deploymentId = process.env.AURA_DEPLOYMENT_ID;
    const generationText = process.env.AURA_DEPLOYMENT_GENERATION;
    if (!idPattern.test(deploymentId ?? '') || !/^[1-9][0-9]*$/.test(generationText ?? '')
        || !Number.isSafeInteger(Number(generationText))) throw new Error('Trusted target deployment context required');
    expected.deploymentId = deploymentId;
    expected.generation = Number(generationText);
  }
  const inputs = args.slice(0, 5).map(path => readFileSync(path));
  // A persisted expected-identity file must never freeze the verifier clock.
  const result = verifyAttestedDeploymentCapabilities(...inputs, { ...expected, now: Math.floor(Date.now() / 1000) });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.capabilitiesSatisfied) process.exitCode = 1;
}
