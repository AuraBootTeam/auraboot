import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import Ajv from 'ajv';
import { extractApplicationReleaseRequirements } from './application-release-requirements.mjs';

const ajv = new Ajv({ allErrors: true, strict: true });
const compile = name => ajv.compile(JSON.parse(readFileSync(new URL(`../../distribution/application/${name}.schema.json`, import.meta.url), 'utf8')));
const requirementsSchema = compile('application-release-requirements');
const capabilitiesSchema = compile('deployment-capabilities');
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
export function verifyRegisteredDeploymentCapabilities(manifestBytes, deploymentBytes, expected) {
  const requirements = extractApplicationReleaseRequirements(manifestBytes, expected?.applicationReleaseDigest);
  const bytes = Buffer.from(`${JSON.stringify(requirements, null, 2)}\n`);
  return verifyDeploymentCapabilities(bytes, deploymentBytes, expected);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const registered = process.argv[2] === '--registered';
  const args = process.argv.slice(registered ? 3 : 2);
  if (args.length !== 3) throw new Error('Usage: node deployment-capability-verifier.mjs [--registered] <requirements-or-registered-manifest.json> <capabilities.json> <expected-identities.json>');
  const verify = registered ? verifyRegisteredDeploymentCapabilities : verifyDeploymentCapabilities;
  const result = verify(readFileSync(args[0]), readFileSync(args[1]), JSON.parse(readFileSync(args[2], 'utf8')));
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.capabilitiesSatisfied) process.exitCode = 1;
}
