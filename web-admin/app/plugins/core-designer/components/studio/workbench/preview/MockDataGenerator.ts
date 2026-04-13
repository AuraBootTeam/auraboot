import type { FieldDataType, PreviewFieldDef } from './types';

/**
 * Mock data generators by field data type.
 * Uses simple random generation without external dependencies.
 *
 * @since 3.6.0
 */

const CHINESE_SURNAMES = ['张', '李', '王', '赵', '刘', '陈', '杨', '黄', '周', '吴'];
const CHINESE_NAMES = ['伟', '芳', '娜', '秀英', '敏', '静', '丽', '强', '磊', '洋'];
const DOMAINS = ['example.com', 'test.cn', 'demo.org', 'mock.io'];
const WORDS = ['数据', '系统', '模块', '配置', '管理', '服务', '平台', '接口', '功能', '测试'];

function randomInt(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function randomElement<T>(arr: T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}

function randomDate(daysBack: number = 365): string {
  const now = Date.now();
  const past = now - randomInt(0, daysBack * 24 * 60 * 60 * 1000);
  return new Date(past).toISOString().split('T')[0];
}

function randomDatetime(daysBack: number = 365): string {
  const now = Date.now();
  const past = now - randomInt(0, daysBack * 24 * 60 * 60 * 1000);
  return new Date(past).toISOString();
}

function randomPhone(): string {
  const prefixes = ['138', '139', '150', '151', '186', '187', '188', '199'];
  return randomElement(prefixes) + String(randomInt(10000000, 99999999));
}

function randomEmail(): string {
  const name = randomElement(CHINESE_SURNAMES).toLowerCase() + randomInt(100, 999);
  return `${name}@${randomElement(DOMAINS)}`;
}

function randomString(): string {
  const count = randomInt(2, 4);
  return Array.from({ length: count }, () => randomElement(WORDS)).join('');
}

function randomText(): string {
  const count = randomInt(3, 8);
  return Array.from({ length: count }, () => randomElement(WORDS)).join('，') + '。';
}

function randomUrl(): string {
  return `https://${randomElement(DOMAINS)}/api/${randomElement(WORDS)}`;
}

function randomChineseName(): string {
  return randomElement(CHINESE_SURNAMES) + randomElement(CHINESE_NAMES);
}

const GENERATORS: Record<FieldDataType, (options?: string[]) => any> = {
  string: () => randomString(),
  text: () => randomText(),
  integer: () => randomInt(1, 1000),
  decimal: () => +(Math.random() * 10000).toFixed(2),
  boolean: () => Math.random() > 0.5,
  date: () => randomDate(),
  datetime: () => randomDatetime(),
  enum: (options) => (options && options.length > 0 ? randomElement(options) : 'option_a'),
  email: () => randomEmail(),
  phone: () => randomPhone(),
  url: () => randomUrl(),
};

/**
 * Infer the best data type from field metadata.
 */
function inferDataType(field: PreviewFieldDef): FieldDataType {
  // Check semantic type first
  const semantic = (field.semanticType ?? '').toLowerCase();
  if (semantic === 'email') return 'email';
  if (semantic === 'phone' || semantic === 'mobile') return 'phone';
  if (semantic === 'url' || semantic === 'link') return 'url';

  // Check field code patterns
  const code = field.code.toLowerCase();
  if (code.includes('email')) return 'email';
  if (code.includes('phone') || code.includes('mobile') || code.includes('tel')) return 'phone';
  if (code.includes('url') || code.includes('link') || code.includes('website')) return 'url';
  if (code.includes('name') || code.includes('title')) return 'string';

  // Check data type
  const dt = (field.dataType ?? 'string').toLowerCase();
  if (dt in GENERATORS) return dt as FieldDataType;

  return 'string';
}

/**
 * Generate a single mock value for a field.
 */
export function generateMockValue(field: PreviewFieldDef): any {
  const dataType = inferDataType(field);

  // Special handling for name-like fields
  const code = field.code.toLowerCase();
  if (code.includes('name') && dataType === 'string') {
    return randomChineseName();
  }

  const options = field.options?.map((o) => o.value);
  return GENERATORS[dataType](options);
}

/**
 * Generate mock data for a list of fields.
 */
export function generateMockData(fields: PreviewFieldDef[]): Record<string, any> {
  const data: Record<string, any> = {};
  for (const field of fields) {
    data[field.code] = generateMockValue(field);
  }
  return data;
}

/**
 * Generate multiple mock records.
 */
export function generateMockRecords(
  fields: PreviewFieldDef[],
  count: number = 5,
): Record<string, any>[] {
  return Array.from({ length: count }, () => generateMockData(fields));
}
