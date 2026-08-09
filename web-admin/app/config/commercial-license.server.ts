import { createHash, createPublicKey, verify } from 'node:crypto';
import { lstat, readFile } from 'node:fs/promises';

export const OFFLINE_SIGNATURE_ENFORCEMENT = 'offline-signature';

const LICENSE_FIELDS = new Set([
  'licenseVersion',
  'customer',
  'edition',
  'release',
  'platformVersion',
  'orderRef',
  'issuedAt',
  'upgradeUntil',
  'supportUntil',
  'features',
  'excludedFeatures',
  'runtimeEnforcement',
]);
const SIGNATURE_FIELDS = new Set([
  'signatureVersion',
  'algorithm',
  'keyId',
  'signedArtifact',
  'signedArtifactSha256',
  'signature',
]);

export interface CommercialLicenseEnvironment {
  AURABOOT_COMMERCIAL_LICENSE_PATH?: string;
  AURABOOT_COMMERCIAL_LICENSE_SIGNATURE_PATH?: string;
  AURABOOT_COMMERCIAL_LICENSE_PUBLIC_KEY_PATH?: string;
  AURABOOT_COMMERCIAL_LICENSE_KEY_ID?: string;
  AURABOOT_COMMERCIAL_LICENSE_CUSTOMER?: string;
  AURABOOT_VERSION?: string;
  APP_VERSION?: string;
}

export interface CommercialLicenseExpectations {
  edition: string;
  orderReference: string;
  requiredFeatures: string[];
}

interface SignatureEnvelope {
  signatureVersion: number;
  algorithm: string;
  keyId: string;
  signedArtifact: string;
  signedArtifactSha256: string;
  signature: string;
}

function object(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be a JSON object.`);
  }
  return value as Record<string, unknown>;
}

function rejectUnknownFields(
  document: Record<string, unknown>,
  allowed: Set<string>,
  label: string,
): void {
  const unknown = Object.keys(document).filter((field) => !allowed.has(field));
  if (unknown.length > 0) {
    throw new Error(`${label} contains unsupported fields: ${unknown.join(', ')}.`);
  }
}

function text(document: Record<string, unknown>, field: string, maxLength = 2048): string {
  const value = document[field];
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`Commercial License field "${field}" must be a non-empty string.`);
  }
  const normalized = value.trim();
  if (normalized.length > maxLength) {
    throw new Error(`Commercial License field "${field}" must be at most ${maxLength} characters.`);
  }
  return normalized;
}

function textArray(document: Record<string, unknown>, field: string): string[] {
  const value = document[field];
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error(`Commercial License field "${field}" must be a non-empty string array.`);
  }
  const normalized = value.map((entry) => {
    if (typeof entry !== 'string' || !entry.trim()) {
      throw new Error(`Commercial License field "${field}" must contain non-empty strings.`);
    }
    return entry.trim();
  });
  if (new Set(normalized).size !== normalized.length) {
    throw new Error(`Commercial License field "${field}" must not contain duplicates.`);
  }
  return normalized;
}

function timestamp(value: string, field: string): number {
  const parsed = Date.parse(value);
  if (!/^\d{4}-\d{2}-\d{2}T/.test(value) || !Number.isFinite(parsed)) {
    throw new Error(`Commercial License field "${field}" must be an ISO-8601 timestamp.`);
  }
  return parsed;
}

function requiredEnvironment(
  environment: CommercialLicenseEnvironment,
  field: keyof CommercialLicenseEnvironment,
): string {
  const value = environment[field]?.trim();
  if (!value) throw new Error(`${field} is required for offline commercial License verification.`);
  return value;
}

async function regularFile(path: string, label: string): Promise<Buffer> {
  let metadata;
  try {
    metadata = await lstat(path);
  } catch (error) {
    throw new Error(`Unable to read ${label}: ${path}`, { cause: error });
  }
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size === 0) {
    throw new Error(`${label} must be a non-empty regular file: ${path}`);
  }
  return readFile(path);
}

function parseJson(bytes: Buffer, label: string, path: string): Record<string, unknown> {
  try {
    return object(JSON.parse(bytes.toString('utf8')), label);
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`${label} is not valid JSON: ${path}`, { cause: error });
    }
    throw error;
  }
}

function signatureEnvelope(document: Record<string, unknown>): SignatureEnvelope {
  rejectUnknownFields(document, SIGNATURE_FIELDS, 'Commercial License signature');
  if (document.signatureVersion !== 1) {
    throw new Error('Commercial License signatureVersion must be 1.');
  }
  const algorithm = text(document, 'algorithm', 20);
  const keyId = text(document, 'keyId', 80);
  const signedArtifact = text(document, 'signedArtifact', 120);
  const signedArtifactSha256 = text(document, 'signedArtifactSha256', 64);
  const signature = text(document, 'signature', 4096);
  if (algorithm !== 'RS256')
    throw new Error('Commercial License signature algorithm must be RS256.');
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(keyId)) {
    throw new Error('Commercial License signature keyId is invalid.');
  }
  if (signedArtifact !== 'license/license.json') {
    throw new Error('Commercial License signature signedArtifact is invalid.');
  }
  if (!/^[0-9a-f]{64}$/.test(signedArtifactSha256)) {
    throw new Error('Commercial License signature SHA-256 digest is invalid.');
  }
  if (!/^[A-Za-z0-9_-]+$/.test(signature)) {
    throw new Error('Commercial License signature must be unpadded base64url.');
  }
  return {
    signatureVersion: 1,
    algorithm,
    keyId,
    signedArtifact,
    signedArtifactSha256,
    signature,
  };
}

export async function verifyDeploymentCommercialLicense(
  environment: CommercialLicenseEnvironment,
  expectations: CommercialLicenseExpectations,
): Promise<void> {
  const licensePath = requiredEnvironment(environment, 'AURABOOT_COMMERCIAL_LICENSE_PATH');
  const signaturePath = requiredEnvironment(
    environment,
    'AURABOOT_COMMERCIAL_LICENSE_SIGNATURE_PATH',
  );
  const publicKeyPath = requiredEnvironment(
    environment,
    'AURABOOT_COMMERCIAL_LICENSE_PUBLIC_KEY_PATH',
  );
  const expectedKeyId = requiredEnvironment(environment, 'AURABOOT_COMMERCIAL_LICENSE_KEY_ID');
  const expectedCustomer = requiredEnvironment(environment, 'AURABOOT_COMMERCIAL_LICENSE_CUSTOMER');
  const expectedVersion = environment.AURABOOT_VERSION?.trim() || environment.APP_VERSION?.trim();
  if (!expectedVersion) {
    throw new Error(
      'AURABOOT_VERSION or APP_VERSION is required for offline License verification.',
    );
  }

  const [licenseBytes, signatureBytes, publicKeyBytes] = await Promise.all([
    regularFile(licensePath, 'Commercial License'),
    regularFile(signaturePath, 'Commercial License signature'),
    regularFile(publicKeyPath, 'Commercial License public key'),
  ]);
  const envelope = signatureEnvelope(
    parseJson(signatureBytes, 'Commercial License signature', signaturePath),
  );
  if (envelope.keyId !== expectedKeyId) {
    throw new Error('Commercial License signature keyId does not match the trusted key.');
  }
  const digest = createHash('sha256').update(licenseBytes).digest('hex');
  if (digest !== envelope.signedArtifactSha256) {
    throw new Error('Commercial License SHA-256 digest does not match.');
  }
  const publicKey = createPublicKey(publicKeyBytes);
  if (publicKey.asymmetricKeyType !== 'rsa') {
    throw new Error('Commercial License public key must be RSA.');
  }
  if (
    !verify('RSA-SHA256', licenseBytes, publicKey, Buffer.from(envelope.signature, 'base64url'))
  ) {
    throw new Error('Commercial License signature verification failed.');
  }

  const license = parseJson(licenseBytes, 'Commercial License', licensePath);
  rejectUnknownFields(license, LICENSE_FIELDS, 'Commercial License');
  if (license.licenseVersion !== 2) {
    throw new Error('Signed Commercial License licenseVersion must be 2.');
  }
  const customer = text(license, 'customer', 80);
  const edition = text(license, 'edition', 40).toLowerCase();
  text(license, 'release', 80);
  const platformVersion = text(license, 'platformVersion', 80);
  const orderReference = text(license, 'orderRef', 120);
  const issuedAt = timestamp(text(license, 'issuedAt', 40), 'issuedAt');
  const upgradeUntil = timestamp(text(license, 'upgradeUntil', 40), 'upgradeUntil');
  const supportUntil = timestamp(text(license, 'supportUntil', 40), 'supportUntil');
  if (issuedAt > Date.now()) throw new Error('Commercial License is not valid before issuedAt.');
  if (upgradeUntil < issuedAt || supportUntil < issuedAt) {
    throw new Error('Commercial License support periods must not precede issuedAt.');
  }
  const features = textArray(license, 'features');
  const excludedFeatures = new Set(textArray(license, 'excludedFeatures'));
  const contradictory = features.filter((feature) => excludedFeatures.has(feature));
  if (contradictory.length > 0) {
    throw new Error(`Commercial License both includes and excludes: ${contradictory.join(', ')}.`);
  }
  if (license.runtimeEnforcement !== OFFLINE_SIGNATURE_ENFORCEMENT) {
    throw new Error('Signed Commercial License runtimeEnforcement must be offline-signature.');
  }

  const matches = [
    ['customer', customer, expectedCustomer],
    ['edition', edition, expectations.edition.trim().toLowerCase()],
    ['orderRef', orderReference, expectations.orderReference],
    ['platformVersion', platformVersion, expectedVersion],
  ];
  for (const [field, actual, expected] of matches) {
    if (actual !== expected) {
      throw new Error(`Commercial License ${field} does not match the deployment.`);
    }
  }
  for (const feature of expectations.requiredFeatures) {
    if (!features.includes(feature) || excludedFeatures.has(feature)) {
      throw new Error(`Commercial License does not grant required feature: ${feature}.`);
    }
  }
}
