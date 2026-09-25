// Synthetic declarations and ephemeral keys shared by attestation tests only.
import { createHash, generateKeyPairSync } from 'node:crypto';
import { signDeploymentObservation } from './deployment-observation-attestation.mjs';
import { extractApplicationReleaseRequirements } from './application-release-requirements.mjs';
export const bytes = value => Buffer.from(JSON.stringify(value));
export const hash = value => `sha256:${createHash('sha256').update(value).digest('hex')}`;
export const id = '01ARZ3NDEKTSV4RRFFQ69G5FAV';
export const digest = `sha256:${'a'.repeat(64)}`;
const requirement = { kind: 'handler', key: 'enroll', contract: 'v1' };
export function fixture() {
  const keys = generateKeyPairSync('ed25519');
  const privateKey = keys.privateKey.export({ type: 'pkcs8', format: 'pem' });
  const publicKey = keys.publicKey.export({ type: 'spki', format: 'pem' });
  const application = bytes({ schemaVersion: 1, releaseId: id, releaseSequence: 1, application: 'aura-edu', compatibilityEpoch: 1,
    sourceLockIdentity: digest, platformCompatibility: { runtime: 'v1', pluginApi: 'v1', dslSchema: 1 },
    components: [{ key: 'education', type: 'definition', version: '1.0', digest,
      compatibilityContract: { schemaVersion: 1, requiredCapabilities: [requirement] } }] });
  const platform = bytes({ schemaVersion: 1, releaseId: id, platform: 'auraboot', version: '1.0', sourceLockIdentity: digest,
    platformContracts: { runtime: ['v1'], pluginApi: ['v1'], dslSchema: [1] }, artifacts: [{ type: 'runtime', id: 'core', version: '1.0', uri: 'artifact:core', digest, source: { repository: 'core', commit: 'a'.repeat(40) } }] });
  const deployment = { schemaVersion: 1, deploymentId: id, generation: 1, platformRelease: { releaseId: id, digest: hash(platform) },
    platformContracts: { runtime: ['v1'], pluginApi: ['v1'], dslSchema: [1] }, supportedApplications: [{ application: 'aura-edu', compatibilityEpochs: [1] }],
    capabilities: [{ ...requirement, providerDigests: [digest] }] };
  const policy = { schemaVersion: 1, keys: [{ keyId: 'observer-1', publicKey, deploymentIds: [id], platformReleaseDigests: [hash(platform)], revoked: false, maxAgeSeconds: 60 }] };
  const expected = { requirementsDigest: hash(Buffer.from(`${JSON.stringify(extractApplicationReleaseRequirements(application, hash(application)), null, 2)}\n`)),
    applicationReleaseDigest: hash(application), platformReleaseDigest: hash(platform), deploymentId: id, generation: 1, now: 120 };
  const metadata = { keyId: 'observer-1', issuedAt: 100, expiresAt: 160 };
  function inputs() {
    const dep = bytes(deployment), pol = bytes(policy);
    return [application, platform, dep, bytes(signDeploymentObservation(dep, metadata, privateKey)), pol,
      { ...expected, deploymentDigest: hash(dep), trustPolicyDigest: hash(pol) }];
  }
  return { deployment, policy, expected, metadata, inputs };
}
