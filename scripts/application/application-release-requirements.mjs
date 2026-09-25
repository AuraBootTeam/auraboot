import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import Ajv from 'ajv';

const ajv = new Ajv({ allErrors: true, strict: true });
const validate = ajv.compile(JSON.parse(readFileSync(new URL('../../distribution/application/application-release-requirements.schema.json', import.meta.url), 'utf8')));
const hash = bytes => `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
const identity = item => JSON.stringify([item.kind, item.key, item.contract]);

/** Extracts registered declarations; does not attest resource completeness or compatibility. */
export function extractApplicationReleaseRequirements(bytes, expectedDigest) {
  if (!/^sha256:[0-9a-f]{64}$/.test(expectedDigest ?? '') || hash(bytes) !== expectedDigest) {
    throw new Error('Application release bytes differ from externally pinned digest');
  }
  const release = JSON.parse(bytes.toString());
  if (release?.schemaVersion !== 1 || !Number.isSafeInteger(release.releaseSequence) || release.releaseSequence < 1
      || !/^sha256:[0-9a-f]{64}$/.test(release.sourceLockIdentity ?? '')
      || !Array.isArray(release.components) || !release.components.length) throw new Error('Versioned registered application release required');
  const result = {
    schemaVersion: 1,
    applicationRelease: { releaseId: release.releaseId, digest: expectedDigest, application: release.application, compatibilityEpoch: release.compatibilityEpoch },
    platformContracts: release.platformCompatibility,
    requiredCapabilities: [],
  };
  const keys = new Set(), capabilities = new Map();
  for (const component of release.components) {
    if (!component || typeof component.key !== 'string' || !component.key.trim() || keys.has(component.key)
        || !['definition', 'backend_plugin', 'frontend_contribution', 'asset', 'migration'].includes(component.type)
        || typeof component.version !== 'string' || !component.version.trim() || /(snapshot|latest|workspace|branch)/i.test(component.version)
        || !/^sha256:[0-9a-f]{64}$/.test(component.digest ?? '')) throw new Error('Invalid or duplicate registered component');
    keys.add(component.key);
    const contract = component.compatibilityContract;
    if (!contract || contract.schemaVersion !== 1 || !Array.isArray(contract.requiredCapabilities)
        || Object.keys(contract).some(key => !['schemaVersion', 'requiredCapabilities'].includes(key))) {
      throw new Error(`Explicit versioned capability requirements required for component ${component.key}`);
    }
    const local = new Set();
    for (const requirement of contract.requiredCapabilities) {
      if (!validate({ ...result, requiredCapabilities: [requirement] })) throw new Error(`Invalid component requirement: ${ajv.errorsText(validate.errors)}`);
      const key = identity(requirement);
      if (local.has(key)) throw new Error(`Duplicate requirement in component ${component.key}`);
      local.add(key);
      capabilities.set(key, { kind: requirement.kind, key: requirement.key, contract: requirement.contract });
    }
  }
  result.requiredCapabilities = [...capabilities.entries()].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([, item]) => item);
  if (!validate(result)) throw new Error(`Invalid release requirements: ${ajv.errorsText(validate.errors)}`);
  result.platformContracts = { runtime: release.platformCompatibility.runtime, pluginApi: release.platformCompatibility.pluginApi, dslSchema: release.platformCompatibility.dslSchema };
  return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 4) throw new Error('Usage: node application-release-requirements.mjs <registered-manifest.json> <externally-pinned-sha256>');
  process.stdout.write(`${JSON.stringify(extractApplicationReleaseRequirements(readFileSync(process.argv[2]), process.argv[3]), null, 2)}\n`);
}
