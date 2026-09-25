import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import Ajv from 'ajv';
import { extractApplicationReleaseRequirements } from './application-release-requirements.mjs';

const ajv = new Ajv({ allErrors: true, strict: true });
const compile = name => ajv.compile(JSON.parse(readFileSync(new URL(`../../distribution/application/${name}.schema.json`, import.meta.url), 'utf8')));
const requirementsSchema = compile('application-release-requirements');
const capabilitiesSchema = compile('deployment-capabilities');
const lockSchema = JSON.parse(readFileSync(new URL('../../distribution/application/application-lock.schema.json', import.meta.url), 'utf8'));
const platformArtifacts = structuredClone(lockSchema.properties.artifacts);
delete platformArtifacts.items.properties.localPath;
const platformSchema = ajv.compile({
  type: 'object', additionalProperties: false,
  required: ['schemaVersion', 'releaseId', 'platform', 'version', 'sourceLockIdentity', 'platformContracts', 'artifacts'],
  definitions: lockSchema.definitions,
  properties: {
    schemaVersion: { const: 1 },
    releaseId: { type: 'string', pattern: '^[0-9A-HJKMNP-TV-Z]{26}$' },
    platform: { type: 'string', pattern: '^[a-z][a-z0-9-]{1,99}$' },
    version: { type: 'string', minLength: 1 },
    sourceLockIdentity: { $ref: '#/definitions/digest' },
    platformContracts: { $ref: 'https://auraboot.com/schemas/deployment-capabilities.schema.json#/properties/platformContracts' },
    artifacts: platformArtifacts,
  },
});
const sha256 = bytes => `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
const identity = capability => JSON.stringify([capability.kind, capability.key, capability.contract]);
function validate(schema, value, label) {
  if (!schema(value)) throw new Error(`${label} schema invalid: ${ajv.errorsText(schema.errors)}`);
}
function unique(values, label) {
  if (new Set(values).size !== values.length) throw new Error(`${label} contains duplicate identities`);
}

/** Pure capability decision, never release admission or permission to switch a tenant binding. */
export function verifyDeploymentCapabilities(requirementsBytes, deploymentBytes, expected) {
  for (const key of ['requirementsDigest', 'deploymentDigest', 'applicationReleaseDigest', 'platformReleaseDigest']) {
    if (!/^sha256:[0-9a-f]{64}$/.test(expected?.[key] ?? '')) throw new Error(`Externally pinned ${key} required`);
  }
  if (!Number.isSafeInteger(expected?.generation) || expected.generation < 1) throw new Error('Externally pinned generation required');
  if (!/^[0-9A-HJKMNP-TV-Z]{26}$/.test(expected?.deploymentId ?? '')) throw new Error('Externally pinned deploymentId required');
  if (sha256(requirementsBytes) !== expected.requirementsDigest) throw new Error('Requirements bytes differ from pinned identity');
  if (sha256(deploymentBytes) !== expected.deploymentDigest) throw new Error('Deployment bytes differ from pinned identity');
  const requirements = JSON.parse(requirementsBytes.toString());
  const deployment = JSON.parse(deploymentBytes.toString());
  validate(requirementsSchema, requirements, 'Requirements');
  validate(capabilitiesSchema, deployment, 'Deployment capabilities');
  unique(requirements.requiredCapabilities.map(identity), 'Requirements');
  unique(deployment.capabilities.map(identity), 'Deployment capabilities');
  unique(deployment.supportedApplications.map(item => item.application), 'Supported applications');
  if (requirements.applicationRelease.digest !== expected.applicationReleaseDigest) throw new Error('Application release digest mismatch');
  if (deployment.platformRelease.digest !== expected.platformReleaseDigest) throw new Error('Platform release digest mismatch');
  if (deployment.deploymentId !== expected.deploymentId || deployment.generation !== expected.generation) throw new Error('Deployment observation identity or generation mismatch');
  const findings = [];
  for (const key of ['runtime', 'pluginApi', 'dslSchema']) {
    if (!deployment.platformContracts[key].includes(requirements.platformContracts[key])) {
      findings.push({ code: 'platform-contract-mismatch', dimension: key, required: requirements.platformContracts[key], observed: deployment.platformContracts[key] });
    }
  }
  const support = deployment.supportedApplications.find(item => item.application === requirements.applicationRelease.application);
  if (!support?.compatibilityEpochs.includes(requirements.applicationRelease.compatibilityEpoch)) findings.push({ code: 'application-epoch-unsupported' });
  const available = new Map(deployment.capabilities.map(item => [identity(item), item]));
  const resolved = [];
  for (const requirement of requirements.requiredCapabilities) {
    const capability = available.get(identity(requirement));
    if (!capability) findings.push({ code: 'required-capability-missing', ...requirement });
    else resolved.push({ ...requirement, providerDigests: [...capability.providerDigests].sort() });
  }
  return {
    schemaVersion: 1,
    applicationRelease: requirements.applicationRelease,
    deploymentId: deployment.deploymentId,
    generation: deployment.generation,
    platformRelease: deployment.platformRelease,
    requirementsDigest: expected.requirementsDigest,
    deploymentDigest: expected.deploymentDigest,
    capabilitiesSatisfied: findings.length === 0,
    findings,
    resolved,
  };
}

/** Registered mode derives the entire declared requirement set before checking capabilities. */
export function verifyRegisteredDeploymentCapabilities(manifestBytes, platformBytes, deploymentBytes, expected) {
  if (!Buffer.isBuffer(platformBytes) || !/^sha256:[0-9a-f]{64}$/.test(expected?.platformReleaseDigest ?? '')
      || sha256(platformBytes) !== expected.platformReleaseDigest) throw new Error('Platform release bytes differ from externally pinned digest');
  const platform = JSON.parse(platformBytes.toString());
  validate(platformSchema, platform, 'Platform release');
  const immutable = value => value.trim() && !/(snapshot|latest|workspace|branch)/i.test(value);
  if (!immutable(platform.version)) throw new Error('Immutable platform version required');
  unique(platform.artifacts.map(item => JSON.stringify([item.type, item.id])), 'Platform artifacts');
  for (const artifact of platform.artifacts) {
    if (!artifact.id.trim() || !immutable(artifact.version) || !artifact.source.repository.trim()
        || !/^(maven|npm|oci|artifact):.+/s.test(artifact.uri)) throw new Error('Invalid platform artifact identity');
  }
  for (const dimension of ['runtime', 'pluginApi']) {
    if (platform.platformContracts[dimension].some(value => !value.trim())) throw new Error('Blank platform contract');
  }
  const requirements = extractApplicationReleaseRequirements(manifestBytes, expected?.applicationReleaseDigest);
  const bytes = Buffer.from(`${JSON.stringify(requirements, null, 2)}\n`);
  const result = verifyDeploymentCapabilities(bytes, deploymentBytes, expected);
  if (result.platformRelease.releaseId !== platform.releaseId) throw new Error('Registered platform release ID mismatch');
  const deployment = JSON.parse(deploymentBytes.toString());
  for (const dimension of ['runtime', 'pluginApi', 'dslSchema']) {
    for (const contract of deployment.platformContracts[dimension]) {
      if (!platform.platformContracts[dimension].includes(contract)) {
        result.findings.push({ code: 'platform-contract-not-registered', dimension, contract });
      }
    }
  }
  // Membership is necessary provenance, not proof that a provider was loaded or trusted.
  const application = JSON.parse(manifestBytes.toString());
  const registeredDigests = new Set([
    ...platform.artifacts.map(artifact => artifact.digest),
    ...application.components.map(component => component.digest),
  ]);
  for (const capability of deployment.capabilities) {
    for (const providerDigest of capability.providerDigests) {
      if (!registeredDigests.has(providerDigest)) {
        result.findings.push({ code: 'provider-artifact-not-registered', kind: capability.kind,
          key: capability.key, contract: capability.contract, providerDigest });
      }
    }
  }
  result.capabilitiesSatisfied = result.findings.length === 0;
  return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const registered = process.argv[2] === '--registered';
  const args = process.argv.slice(registered ? 3 : 2);
  if (args.length !== (registered ? 4 : 3)) throw new Error('Usage: node deployment-capability-verifier.mjs [--registered <application-manifest.json> <platform-manifest.json> | <requirements.json>] <capabilities.json> <expected-identities.json>');
  const expected = JSON.parse(readFileSync(args.at(-1), 'utf8'));
  const result = registered
    ? verifyRegisteredDeploymentCapabilities(readFileSync(args[0]), readFileSync(args[1]), readFileSync(args[2]), expected)
    : verifyDeploymentCapabilities(readFileSync(args[0]), readFileSync(args[1]), expected);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.capabilitiesSatisfied) process.exitCode = 1;
}
