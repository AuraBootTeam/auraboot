import { createHash } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
// Keep this consumer runnable from an archived checkout without node_modules.
const digest = bytes => `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
const text = pattern => value => typeof value === 'string' && pattern.test(value);
const sha = text(/^sha256:[0-9a-f]{64}$/);
const integer = (minimum, maximum = Number.MAX_SAFE_INTEGER) => value => Number.isSafeInteger(value) && value >= minimum && value <= maximum;
const oneOf = (...values) => value => values.includes(value);
const object = fields => value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === Object.keys(fields).length
  && Object.entries(fields).every(([key, check]) => Object.hasOwn(value, key) && check(value[key]));
const timestamp = text(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
const context = {
  deploymentId: text(/^[0-9A-HJKMNP-TV-Z]{26}$/), generation: integer(1),
  action: oneOf('info', 'validate', 'migrate'), payloadSha256: text(/^[0-9a-f]{64}$/),
  database: object({ host: text(/^[a-zA-Z0-9][a-zA-Z0-9.-]*$/), port: integer(1, 65535),
    name: text(/^[a-zA-Z_][a-zA-Z0-9_]*$/) }),
};
const expectedSchema = object({ ...context, startedDigest: sha, resultDigest: sha });
const startedSchema = object({ schemaVersion: oneOf(1), kind: oneOf('migration-execution'),
  ...context, state: oneOf('started'), startedAt: timestamp });
const resultSchema = object({ schemaVersion: oneOf(1), kind: oneOf('migration-execution-result'),
  startedDigest: sha, state: oneOf('succeeded', 'failed'), exitCode: integer(0),
  failureKind: oneOf(null, 'engine-launch-failed', 'engine-signaled', 'engine-exit-nonzero'), completedAt: timestamp });
function need(condition, message) { if (!condition) throw new Error(message); }
function validate(schema, value, label) { need(schema(value), `${label} invalid`); }
function parse(bytes, schema, label) {
  need(Buffer.isBuffer(bytes) && bytes.length > 0 && bytes.length <= 16384, `${label} must be bytes (maximum 16 KiB)`);
  const value = JSON.parse(bytes.toString('utf8')); validate(schema, value, label); return value;
}
function time(value) {
  const parsed = Date.parse(value);
  need(Number.isFinite(parsed) && new Date(parsed).toISOString() === value, 'Invalid execution timestamp');
  return parsed;
}

/** Checks pinned execution evidence, not its publisher, image provenance, or schema compatibility. */
export function verifyMigrationExecution(startedBytes, resultBytes, expected) {
  validate(expectedSchema, expected, 'Externally pinned migration context');
  const started = parse(startedBytes, startedSchema, 'Migration start');
  const result = parse(resultBytes, resultSchema, 'Migration result');
  need(digest(startedBytes) === expected.startedDigest && digest(resultBytes) === expected.resultDigest,
    'Migration evidence differs from externally pinned digests');
  need(result.startedDigest === expected.startedDigest, 'Migration result belongs to another execution');
  for (const key of ['deploymentId', 'generation', 'action', 'payloadSha256']) {
    need(started[key] === expected[key], `Migration ${key} differs from target context`);
  }
  for (const key of ['host', 'port', 'name']) {
    need(started.database[key] === expected.database[key], `Migration database ${key} differs from target context`);
  }
  // These are producer wall-clock timestamps, not a freshness or trusted-clock attestation.
  time(started.startedAt); time(result.completedAt);
  const succeeded = result.state === 'succeeded';
  need(succeeded ? result.exitCode === 0 && result.failureKind === null
    : result.exitCode > 0 && result.failureKind !== null, 'Inconsistent migration execution outcome');
  return { schemaVersion: 1, ...structuredClone(expected), executionSucceeded: succeeded,
    exitCode: result.exitCode, failureKind: result.failureKind,
    startedAt: started.startedAt, completedAt: result.completedAt };
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  const args = process.argv.slice(2);
  if (args.length !== 3) throw new Error('Usage: node migration-execution-verifier.mjs <started.json> <result.json> <expected-context.json>');
  const result = verifyMigrationExecution(readFileSync(args[0]), readFileSync(args[1]), JSON.parse(readFileSync(args[2], 'utf8')));
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.executionSucceeded) process.exitCode = 1;
}
