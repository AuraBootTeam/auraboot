/**
 * BomColumnMapper - Fuzzy column matching engine for BOM Excel/CSV import.
 *
 * Maps source spreadsheet column headers to BOM line target fields using
 * alias tables, case-insensitive comparison, and Levenshtein similarity.
 *
 * @since 3.9.0
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ColumnMapping {
  /** Original column header from the uploaded file */
  sourceColumn: string;
  /** BOM line field code (empty string if not mapped) */
  targetField: string;
  /** Match confidence: 1.0 = exact alias, 0.6-0.9 = fuzzy, 0.0 = unmapped */
  confidence: number;
}

export interface BomTargetField {
  code: string;
  label: string;
  required: boolean;
}

// ---------------------------------------------------------------------------
// Target field definitions (mirrors pe_bom_line field schema)
// ---------------------------------------------------------------------------

export const BOM_LINE_FIELDS: BomTargetField[] = [
  { code: 'product_code', label: 'Part Number / Material Code', required: true },
  { code: 'quantity', label: 'Quantity', required: true },
  { code: 'unit', label: 'Unit', required: false },
  { code: 'reference', label: 'Reference Designator', required: false },
  { code: 'loss_rate', label: 'Loss Rate (%)', required: false },
  { code: 'description', label: 'Description', required: false },
  { code: 'manufacturer', label: 'Manufacturer', required: false },
  { code: 'package_info', label: 'Package / Footprint', required: false },
  { code: 'value', label: 'Value / Parameter', required: false },
  { code: 'remark', label: 'Remark', required: false },
];

/**
 * Maps logical field codes to actual pe_bom_line field codes used by
 * the Command Engine API when creating BOM lines.
 */
export const FIELD_CODE_TO_API: Record<string, string> = {
  product_code: 'pe_bom_line_material_id',
  quantity: 'pe_bom_line_qty',
  unit: 'pe_bom_line_unit',
  reference: 'pe_bom_line_ref_designator',
  loss_rate: 'pe_bom_line_loss_rate',
  remark: 'pe_bom_line_remark',
  // description, manufacturer, package_info, value are kept for preview
  // but not directly mapped to pe_bom_line fields (they belong to product).
};

// ---------------------------------------------------------------------------
// Alias table: target field code -> list of common header names
// ---------------------------------------------------------------------------

export const COLUMN_ALIASES: Record<string, string[]> = {
  product_code: [
    'Part Number',
    'PN',
    'P/N',
    'Part No',
    'Part No.',
    'Component',
    'Item',
    'Item Code',
    'Item Number',
    'Material',
    'Material Code',
    'Material No',
    'mpn',
    'Mfr Part',
    'Mfr Part Number',
    // Chinese
    '零件号',
    '物料编码',
    '物料号',
    '料号',
    '元器件编码',
    '组件',
    '物料',
    '编码',
  ],
  quantity: [
    'Quantity',
    'Qty',
    'qty',
    'Count',
    'Amount',
    'Qty Per',
    'Usage',
    'Qty/Board',
    // Chinese
    '数量',
    '用量',
    '每板用量',
    '使用数量',
  ],
  unit: [
    'Unit',
    'uom',
    'Unit of Measure',
    'Units',
    // Chinese
    '单位',
    '计量单位',
  ],
  reference: [
    'Reference',
    'Ref',
    'Ref Des',
    'RefDes',
    'Ref Designator',
    'Reference Designator',
    'Designator',
    'Designators',
    'Location',
    'Placement',
    // Chinese
    '位号',
    '参考标记',
    '安装位置',
  ],
  loss_rate: [
    'Loss Rate',
    'Loss',
    'Attrition',
    'Wastage',
    'Scrap Rate',
    'Waste Rate',
    'Loss %',
    'Attrition Rate',
    // Chinese
    '损耗率',
    '损耗',
    '废品率',
  ],
  description: [
    'Description',
    'Desc',
    'Part Description',
    'Component Description',
    'Specification',
    'Spec',
    'Details',
    // Chinese
    '描述',
    '规格',
    '规格描述',
    '说明',
    '零件描述',
  ],
  manufacturer: [
    'Manufacturer',
    'mfr',
    'Mfg',
    'Mfg Name',
    'Vendor',
    'Supplier',
    'Brand',
    'Maker',
    // Chinese
    '制造商',
    '厂商',
    '供应商',
    '品牌',
  ],
  package_info: [
    'Package',
    'Footprint',
    'pkg',
    'Package Type',
    'Size',
    'Case Size',
    'Form Factor',
    'Land Pattern',
    // Chinese
    '封装',
    '封装类型',
    '尺寸',
  ],
  value: [
    'Value',
    'Val',
    'Component Value',
    'Nominal Value',
    'Rating',
    'Parameter',
    // Chinese
    '值',
    '参数值',
    '标称值',
    '元件值',
  ],
  remark: [
    'Remark',
    'Remarks',
    'Note',
    'Notes',
    'Comment',
    'Comments',
    'Memo',
    'Annotation',
    // Chinese
    '备注',
    '注释',
    '说明',
  ],
};

// ---------------------------------------------------------------------------
// Matching engine
// ---------------------------------------------------------------------------

/**
 * Normalize a string for comparison: lowercase, trim, remove punctuation.
 */
function normalize(s: string): string {
  return s
    .toLowerCase()
    .trim()
    .replace(/[_\-./\\()（）]/g, ' ')
    .replace(/\s+/g, ' ');
}

/**
 * Simple Levenshtein distance (sufficient for short header strings).
 */
function levenshtein(a: string, b: string): number {
  const m = a.length;
  const n = b.length;
  if (m === 0) return n;
  if (n === 0) return m;

  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 0; i <= m; i++) dp[i][0] = i;
  for (let j = 0; j <= n; j++) dp[0][j] = j;

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      dp[i][j] = Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost);
    }
  }
  return dp[m][n];
}

/**
 * Compute similarity between two strings (0..1).
 */
function similarity(a: string, b: string): number {
  const na = normalize(a);
  const nb = normalize(b);
  if (na === nb) return 1.0;
  if (na.includes(nb) || nb.includes(na)) return 0.9;
  const maxLen = Math.max(na.length, nb.length);
  if (maxLen === 0) return 0;
  const dist = levenshtein(na, nb);
  return Math.max(0, 1 - dist / maxLen);
}

/**
 * Score a source column header against a target field using the alias table.
 * Returns a confidence score from 0 to 1.
 */
function scoreMatch(header: string, fieldCode: string): number {
  const aliases = COLUMN_ALIASES[fieldCode] ?? [];
  let best = 0;

  for (const alias of aliases) {
    const sim = similarity(header, alias);
    if (sim > best) best = sim;
    if (best >= 1.0) break; // Perfect match, stop early
  }

  return best;
}

/**
 * Auto-map an array of source column headers to BOM line target fields.
 *
 * Algorithm:
 * 1. Compute similarity scores for all (header, field) pairs.
 * 2. Greedily assign the highest-confidence match first.
 * 3. Each target field can only be assigned once.
 * 4. Unmatched headers get targetField = '' with confidence = 0.
 *
 * @param headers - Column headers from the uploaded file
 * @param threshold - Minimum confidence to consider a match (default 0.5)
 */
export function autoMapColumns(headers: string[], threshold = 0.5): ColumnMapping[] {
  const fieldCodes = BOM_LINE_FIELDS.map((f) => f.code);

  // Build score matrix
  const scores: { header: string; field: string; score: number }[] = [];
  for (const header of headers) {
    for (const field of fieldCodes) {
      const score = scoreMatch(header, field);
      if (score >= threshold) {
        scores.push({ header, field, score });
      }
    }
  }

  // Sort descending by score for greedy assignment
  scores.sort((a, b) => b.score - a.score);

  const assignedHeaders = new Set<string>();
  const assignedFields = new Set<string>();
  const mappingMap = new Map<string, ColumnMapping>();

  for (const { header, field, score } of scores) {
    if (assignedHeaders.has(header) || assignedFields.has(field)) continue;
    assignedHeaders.add(header);
    assignedFields.add(field);
    mappingMap.set(header, {
      sourceColumn: header,
      targetField: field,
      confidence: Math.round(score * 100) / 100,
    });
  }

  // Build final array preserving original header order
  return headers.map(
    (h) =>
      mappingMap.get(h) ?? {
        sourceColumn: h,
        targetField: '',
        confidence: 0,
      },
  );
}

/**
 * Check whether all required fields have been mapped.
 */
export function getMissingRequiredFields(mappings: ColumnMapping[]): BomTargetField[] {
  const mappedFields = new Set(mappings.map((m) => m.targetField).filter(Boolean));
  return BOM_LINE_FIELDS.filter((f) => f.required && !mappedFields.has(f.code));
}
