import { createHash, randomBytes } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  writeFileSync,
} from 'node:fs';
import { dirname, resolve } from 'node:path';

const SHA256_IDENTITY = /^sha256:[0-9a-f]{64}$/;
const APPLICATION_CODE = /^[a-z][a-z0-9-]{1,99}$/;
const ULID_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function requireValue(valid, message) {
  if (!valid) throw new Error(message);
}

function sha256(value) {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`;
}

function encodeTime(timestamp) {
  let value = BigInt(timestamp);
  let encoded = '';
  for (let index = 0; index < 10; index += 1) {
    encoded = ULID_ALPHABET[Number(value % 32n)] + encoded;
    value /= 32n;
  }
  return encoded;
}

function encodeRandom(bytes) {
  let value = BigInt(`0x${bytes.toString('hex')}`);
  let encoded = '';
  for (let index = 0; index < 16; index += 1) {
    encoded = ULID_ALPHABET[Number(value % 32n)] + encoded;
    value /= 32n;
  }
  return encoded;
}

export function generateOperationId(now = Date.now(), entropy = randomBytes(10)) {
  requireValue(Number.isSafeInteger(now) && now >= 0, 'operation timestamp must be a positive integer');
  requireValue(Buffer.isBuffer(entropy) && entropy.length === 10, 'operation entropy must contain 10 bytes');
  return `${encodeTime(now)}${encodeRandom(entropy)}`;
}

export function normalizeBaseUrl(value) {
  const url = new URL(value);
  requireValue(['http:', 'https:'].includes(url.protocol), 'release base URL must use HTTP or HTTPS');
  requireValue(!url.username && !url.password, 'release base URL must not contain credentials');
  requireValue(!url.search && !url.hash, 'release base URL must not contain a query or fragment');
  return url.toString().replace(/\/$/, '');
}

function validateRegistration(registration) {
  requireValue(registration && typeof registration === 'object', 'release registration JSON object required');
  requireValue(typeof registration.registrationKey === 'string' && registration.registrationKey.length > 0,
    'release registration key required');
  requireValue(registration.content && typeof registration.content === 'object', 'release registration content required');
  requireValue(SHA256_IDENTITY.test(registration.content.sourceLockIdentity ?? ''),
    'release registration requires an exact source lock identity');
  requireValue(Array.isArray(registration.content.components) && registration.content.components.length > 0,
    'release registration components required');
  return registration;
}

function atomicWriteJson(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}`;
  writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  renameSync(temporary, path);
}

function validateExistingPlan(plan, expected) {
  for (const key of ['applicationCode', 'baseUrl', 'registrationDigest', 'expectedStableVersion']) {
    requireValue(plan[key] === expected[key], `release receipt ${key} differs from the requested release`);
  }
  requireValue(/^[0-9A-HJKMNP-TV-Z]{26}$/.test(plan.operations?.publication ?? ''),
    'release receipt publication operation is invalid');
  requireValue(/^[0-9A-HJKMNP-TV-Z]{26}$/.test(plan.operations?.stable ?? ''),
    'release receipt stable operation is invalid');
  return plan;
}

export function loadOrCreateReleasePlan({
  receiptPath,
  registrationText,
  applicationCode,
  baseUrl,
  expectedStableVersion,
  now = Date.now(),
  entropy = randomBytes,
}) {
  requireValue(APPLICATION_CODE.test(applicationCode ?? ''), 'valid application code required');
  const normalizedUrl = normalizeBaseUrl(baseUrl);
  const normalizedExpectedVersion = expectedStableVersion === undefined ? null : expectedStableVersion;
  requireValue(normalizedExpectedVersion === null
    || (Number.isSafeInteger(normalizedExpectedVersion) && normalizedExpectedVersion > 0),
  'expected stable version must be a positive integer');
  const expected = {
    applicationCode,
    baseUrl: normalizedUrl,
    registrationDigest: sha256(registrationText),
    expectedStableVersion: normalizedExpectedVersion,
  };
  if (existsSync(receiptPath)) {
    return validateExistingPlan(JSON.parse(readFileSync(receiptPath, 'utf8')), expected);
  }
  const plan = {
    schemaVersion: 1,
    ...expected,
    operations: {
      publication: generateOperationId(now, entropy(10)),
      stable: generateOperationId(now + 1, entropy(10)),
    },
    status: 'planned',
    application: null,
    registration: null,
    publication: null,
    stable: null,
  };
  atomicWriteJson(receiptPath, plan);
  return plan;
}

async function requestJson({ fetchImpl, baseUrl, token, path, body, timeoutMs }) {
  const response = await fetchImpl(`${baseUrl}${path}`, {
    method: 'POST',
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMs),
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let envelope;
  try {
    envelope = JSON.parse(text);
  } catch {
    throw new Error(`release API returned non-JSON content with HTTP ${response.status}`);
  }
  if (!response.ok || envelope?.code !== '0') {
    throw new Error(`release API rejected ${path}: HTTP ${response.status}, code=${envelope?.code ?? 'missing'}, message=${envelope?.message ?? 'missing'}`);
  }
  requireValue(envelope.data && typeof envelope.data === 'object', `release API returned no data for ${path}`);
  return envelope.data;
}

function updateReceipt(receiptPath, plan, status, field, data) {
  plan.status = status;
  plan[field] = data;
  atomicWriteJson(receiptPath, plan);
}

export async function publishApplicationRelease({
  applicationCode,
  applicationName,
  baseUrl,
  expectedStableVersion,
  fetchImpl = fetch,
  receiptPath,
  registrationPath,
  timeoutMs = 30_000,
  token,
}) {
  requireValue(typeof token === 'string' && token.trim(), 'release API token required');
  requireValue(Number.isSafeInteger(timeoutMs) && timeoutMs > 0, 'release timeout must be a positive integer');
  const absoluteRegistrationPath = resolve(registrationPath);
  const absoluteReceiptPath = resolve(receiptPath);
  const registrationText = readFileSync(absoluteRegistrationPath, 'utf8');
  const registration = validateRegistration(JSON.parse(registrationText));
  const plan = loadOrCreateReleasePlan({
    receiptPath: absoluteReceiptPath,
    registrationText,
    applicationCode,
    baseUrl,
    expectedStableVersion,
  });
  if (plan.status === 'completed') return plan;

  if (applicationName) {
    const application = await requestJson({
      fetchImpl, baseUrl: plan.baseUrl, token, timeoutMs,
      path: '/api/admin/application-releases',
      body: { code: applicationCode, name: applicationName },
    });
    requireValue(application.code === applicationCode, 'release API returned a different application code');
    updateReceipt(absoluteReceiptPath, plan, 'application_ready', 'application', application);
  }

  const registered = await requestJson({
    fetchImpl, baseUrl: plan.baseUrl, token, timeoutMs,
    path: `/api/admin/application-releases/${encodeURIComponent(applicationCode)}`,
    body: registration,
  });
  requireValue(typeof registered.releaseId === 'string' && registered.releaseId.length === 26,
    'release API returned an invalid release ID');
  requireValue(SHA256_IDENTITY.test(registered.digest ?? ''), 'release API returned an invalid release digest');
  if (plan.registration?.releaseId) {
    requireValue(plan.registration.releaseId === registered.releaseId,
      'idempotent registration returned a different release ID');
  }
  updateReceipt(absoluteReceiptPath, plan, 'registered', 'registration', registered);

  const publication = await requestJson({
    fetchImpl, baseUrl: plan.baseUrl, token, timeoutMs,
    path: `/api/admin/application-releases/${encodeURIComponent(applicationCode)}/${encodeURIComponent(registered.releaseId)}/publication`,
    body: { operationId: plan.operations.publication },
  });
  requireValue(publication.releaseId === registered.releaseId,
    'publication response does not identify the registered release');
  updateReceipt(absoluteReceiptPath, plan, 'published', 'publication', publication);

  const stableBody = { releaseId: registered.releaseId, operationId: plan.operations.stable };
  if (plan.expectedStableVersion !== null) stableBody.expectedVersion = plan.expectedStableVersion;
  const stable = await requestJson({
    fetchImpl, baseUrl: plan.baseUrl, token, timeoutMs,
    path: `/api/admin/application-releases/${encodeURIComponent(applicationCode)}/channels/stable`,
    body: stableBody,
  });
  requireValue(stable.channel === 'stable' && stable.releaseId === registered.releaseId,
    'stable response does not identify the registered release');
  updateReceipt(absoluteReceiptPath, plan, 'completed', 'stable', stable);
  return plan;
}
