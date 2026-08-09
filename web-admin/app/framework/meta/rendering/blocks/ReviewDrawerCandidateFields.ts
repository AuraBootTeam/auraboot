type CandidateField = Record<string, any>;

export type ProfiledFieldGroup = {
  key: string;
  label: any;
  fields: CandidateField[];
};

const defaultOtherExcludeKeys = new Set([
  'code',
  'score',
  'materialName',
  'material_name',
  'spec',
  'specModel',
  'brand',
  'mpn',
  'model_text',
  'modelText',
  'model_value',
  'modelValue',
  'package',
  'packageCode',
  'normalized_package',
]);

function parseJsonValue(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function readPathDeep(source: unknown, path?: string): unknown {
  if (!path) return source;
  return path.split('.').reduce<unknown>((current, part) => {
    const parsed = parseJsonValue(current);
    if (!parsed || typeof parsed !== 'object') return undefined;
    return (parsed as Record<string, unknown>)[part];
  }, source);
}

function isEmptyValue(value: unknown): boolean {
  return value === undefined || value === null || value === '' || value === '-';
}

function normalizeCategory(value: unknown): string {
  return String(value ?? '')
    .trim()
    .toLowerCase()
    .replace(/[\s-]+/g, '_');
}

function fieldKey(field: CandidateField): string {
  return String(field.key || field.field || field.valueField || field.label || '');
}

function attributeKeyFromPath(path: unknown): string | null {
  if (typeof path !== 'string') return null;
  const marker = 'attributes.';
  const markerIndex = path.indexOf(marker);
  if (markerIndex >= 0) {
    return path.slice(markerIndex + marker.length).split('.')[0] || null;
  }
  if (!path.includes('.')) return path;
  return null;
}

function consumedAttributeKeys(fields: CandidateField[]): Set<string> {
  const keys = new Set<string>();
  for (const field of fields) {
    const paths = [
      field.field,
      field.valueField,
      ...(Array.isArray(field.fallbackFields) ? field.fallbackFields : []),
    ];
    for (const path of paths) {
      const key = attributeKeyFromPath(path);
      if (key) keys.add(key);
    }
  }
  return keys;
}

function formatOtherValue(value: unknown): string {
  if (Array.isArray(value)) return value.map(formatOtherValue).join(', ');
  if (value && typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function resolveProfileKey(config: any, candidate: any, referenceRecord?: any): string {
  const profileConfig = config?.fieldProfiles || {};
  const candidates = [
    readPathDeep(referenceRecord, profileConfig.categoryField),
    ...(Array.isArray(profileConfig.categoryFallbackFields)
      ? profileConfig.categoryFallbackFields.map((field: string) =>
          readPathDeep(referenceRecord, field),
        )
      : []),
    readPathDeep(candidate, profileConfig.categoryField),
    ...(Array.isArray(profileConfig.categoryFallbackFields)
      ? profileConfig.categoryFallbackFields.map((field: string) => readPathDeep(candidate, field))
      : []),
    readPathDeep(
      candidate,
      profileConfig.candidateCategoryField || 'bom_me_candidate_snapshot_json.category',
    ),
    ...(Array.isArray(profileConfig.candidateCategoryFallbackFields)
      ? profileConfig.candidateCategoryFallbackFields.map((field: string) =>
          readPathDeep(candidate, field),
        )
      : []),
  ];
  const profiles = profileConfig.profiles || {};
  const aliases = profileConfig.aliases || {};
  for (const candidateCategory of candidates) {
    const normalized = normalizeCategory(candidateCategory);
    if (!normalized) continue;
    const alias = aliases[normalized] || normalized;
    if (profiles[alias]) return alias;
  }
  return '';
}

function buildOtherField(
  config: any,
  candidate: any,
  fields: CandidateField[],
): CandidateField | null {
  const profileConfig = config?.fieldProfiles || {};
  const otherConfig = profileConfig.otherField;
  if (!otherConfig || otherConfig.enabled === false) return null;
  const attributes = readPathDeep(
    candidate,
    profileConfig.attributeSourceField || 'bom_me_candidate_snapshot_json.attributes',
  );
  const parsedAttributes = parseJsonValue(attributes);
  if (
    !parsedAttributes ||
    typeof parsedAttributes !== 'object' ||
    Array.isArray(parsedAttributes)
  ) {
    return null;
  }
  const consumed = consumedAttributeKeys(fields);
  const excluded = new Set<string>([
    ...defaultOtherExcludeKeys,
    ...(Array.isArray(otherConfig.excludeKeys) ? otherConfig.excludeKeys : []),
  ]);
  const values = Object.entries(parsedAttributes as Record<string, unknown>)
    .filter(([key, value]) => !consumed.has(key) && !excluded.has(key) && !isEmptyValue(value))
    .map(([key, value]) => `${key}: ${formatOtherValue(value)}`);
  if (values.length === 0 && !otherConfig.showWhenEmpty) return null;
  return {
    ...otherConfig,
    value: values.length > 0 ? values.join('；') : otherConfig.emptyText || '-',
  };
}

export function resolveCandidateFieldColumns({
  item,
  candidate,
  referenceRecord,
}: {
  item: any;
  candidate: any;
  referenceRecord?: any;
}): CandidateField[] {
  return resolveProfiledFieldColumns({
    item: {
      ...item,
      fieldProfiles: item?.fieldProfiles,
    },
    record: candidate,
    referenceRecord,
  });
}

export function resolveProfiledFieldColumns({
  item,
  record,
  referenceRecord,
}: {
  item: any;
  record: any;
  referenceRecord?: any;
}): CandidateField[] {
  return resolveProfiledFieldGroups({ item, record, referenceRecord }).flatMap(
    (group) => group.fields,
  );
}

export function resolveProfiledFieldGroups({
  item,
  record,
  referenceRecord,
}: {
  item: any;
  record: any;
  referenceRecord?: any;
}): ProfiledFieldGroup[] {
  const baseFields = Array.isArray(item?.fieldColumns)
    ? item.fieldColumns
    : item?.detailFields || [];
  const profileConfig = item?.fieldProfiles || {};
  const profileKey = resolveProfileKey(item, record, referenceRecord || record);
  const profileFields = Array.isArray(profileConfig.profiles?.[profileKey])
    ? profileConfig.profiles[profileKey]
    : [];
  const profileManagedFieldKeys = new Set(
    Object.values(profileConfig.profiles || {})
      .flatMap((fields: any) => (Array.isArray(fields) ? fields : []))
      .map(fieldKey),
  );
  const commonFields = baseFields.filter(
    (field: CandidateField) => !profileManagedFieldKeys.has(fieldKey(field)),
  );
  const fields = [...commonFields, ...profileFields];
  const otherField = buildOtherField(item, record, fields);
  const groups: ProfiledFieldGroup[] = [];
  if (commonFields.length > 0) {
    groups.push({
      key: 'common',
      label: profileConfig.commonTitle || { 'zh-CN': '通用身份', en: 'Common Identity' },
      fields: commonFields,
    });
  }
  if (profileFields.length > 0) {
    groups.push({
      key: 'profile',
      label: profileConfig.profileTitle || { 'zh-CN': '类别属性', en: 'Category Attributes' },
      fields: profileFields,
    });
  }
  if (otherField) {
    groups.push({
      key: 'other',
      label: profileConfig.otherTitle ||
        otherField.label || { 'zh-CN': '其他已抽取属性', en: 'Other Extracted Attributes' },
      fields: [otherField],
    });
  }
  return groups;
}

// ---- Candidate comparison coloring (moved out of ReviewDrawerBlockRenderer so
// the pure mapping is node-unit-testable; the renderer imports these). The
// review card colors each attribute cell from the writer's comparison payload:
// evidence_json.groups.<comparisonGroup>.comparisons[] matched by comparisonKey,
// mapped matched->emerald / mismatch·missing->amber / none->neutral. 2026-07-06
// B regression: v2/L0 writers omitted the payload -> colorless cards.

function comparablePackageValue(raw: unknown): string {
  if (raw === undefined || raw === null) return '';
  let value = String(raw).trim().toUpperCase().replace(/\s+/g, '');
  if (!value) return '';
  const slash = value.indexOf('/');
  if (slash > 0) value = value.slice(0, slash);
  const paren = value.indexOf('(');
  if (paren > 0) value = value.slice(0, paren);
  value = value.replace(/^[._-]+|[._-]+$/g, '');
  if (/^[A-Z]+\d{4}$/.test(value)) return value.slice(-4);
  if (/^[CRL]\d{4}.*/.test(value)) return value.slice(1, 5);
  return value.replace(/[-_]/g, '');
}

function packageValuesEquivalent(source: unknown, candidate: unknown): boolean {
  const left = comparablePackageValue(source);
  const right = comparablePackageValue(candidate);
  return Boolean(left && right && left === right);
}

export function normalizeComparisonRecord(
  comparison: Record<string, unknown>,
): Record<string, unknown> {
  const key = String(comparison.key || comparison.label || '');
  if (
    key === 'package' &&
    String(comparison.status || '') === 'mismatch' &&
    packageValuesEquivalent(comparison.sourceValue, comparison.candidateValue)
  ) {
    return { ...comparison, status: 'matched', reason: 'matched' };
  }
  return comparison;
}

export function comparisonStatusForField(candidate: any, field: any): unknown {
  const comparisonKey = field.comparisonKey || field.evidenceKey;
  if (!comparisonKey) return undefined;
  const evidence = parseJsonValue(
    readPathDeep(candidate, field.comparisonSourceField || 'bom_me_evidence_json'),
  );
  const groupKey = field.comparisonGroup;
  const groupComparisons = groupKey
    ? readPathDeep(evidence, `groups.${groupKey}.comparisons`)
    : undefined;
  const comparisons = Array.isArray(groupComparisons)
    ? groupComparisons
    : Array.isArray(readPathDeep(evidence, 'comparisons'))
      ? (readPathDeep(evidence, 'comparisons') as unknown[])
      : [];
  const match = (comparisons as any[]).find((comparison: any) => {
    return comparison?.key === comparisonKey || comparison?.label === comparisonKey;
  });
  return match ? normalizeComparisonRecord(match).status : undefined;
}

export function comparisonStatusFieldClass(status: unknown): string {
  switch (String(status || '')) {
    case 'matched':
      return 'rounded-md border border-emerald-200 bg-emerald-50/70 px-2 py-1';
    case 'mismatch':
    case 'missing':
    case 'missing_source':
    case 'missing_candidate':
    case 'missing_both':
      return 'rounded-md border border-amber-200 bg-amber-50/80 px-2 py-1';
    default:
      return '';
  }
}
