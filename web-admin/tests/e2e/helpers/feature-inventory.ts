import fs from 'fs/promises';
import path from 'path';

export interface FeatureInventoryRow {
  featureId: string;
  pluginOrDomain: string;
  featureSource: string;
  featureType: string;
  featureKey: string;
  sourceRef: string;
  coverageStatus: string;
  notes: string;
}

const DEFAULT_INVENTORY_PATH = path.resolve(
  process.cwd(),
  '../docs/test-reports/2026-03-03-feature-inventory-v1.csv',
);

function parseCsvLine(line: string): string[] {
  const cols: string[] = [];
  let cur = '';
  let inQuotes = false;

  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];

    if (ch === '"') {
      const next = line[i + 1];
      if (inQuotes && next === '"') {
        cur += '"';
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
      continue;
    }

    if (ch === ',' && !inQuotes) {
      cols.push(cur.trim());
      cur = '';
      continue;
    }

    cur += ch;
  }

  cols.push(cur.trim());
  return cols;
}

export async function loadFeatureInventory(csvPath = DEFAULT_INVENTORY_PATH): Promise<FeatureInventoryRow[]> {
  const raw = await fs.readFile(csvPath, 'utf-8');
  const lines = raw
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);

  if (lines.length < 2) {
    return [];
  }

  const rows: FeatureInventoryRow[] = [];
  for (let i = 1; i < lines.length; i += 1) {
    const cols = parseCsvLine(lines[i]);
    if (cols.length < 7) continue;

    rows.push({
      featureId: cols[0] || '',
      pluginOrDomain: cols[1] || '',
      featureSource: cols[2] || '',
      featureType: cols[3] || '',
      featureKey: cols[4] || '',
      sourceRef: cols[5] || '',
      coverageStatus: cols[6] || '',
      notes: cols[7] || '',
    });
  }

  return rows;
}

export function toDynamicRouteModelCode(modelCode: string): string {
  return modelCode.replace(/_/g, '-');
}

export function fromDynamicRouteModelCode(tableName: string): string {
  return tableName.replace(/-/g, '_');
}

export function uniqueStrings(input: string[]): string[] {
  return [...new Set(input.filter(Boolean))];
}
