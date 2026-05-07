/**
 * DSL list-page i18n resource completeness guard.
 *
 * The OSS frontend probes specific i18n keys (`common.sort`, `common.fields`,
 * `common.add_filter`, `common.my_records`, `common.created_today`,
 * `common.modified_this_week`, `common.search`, `common.filter`,
 * `common.created_at`, `common.create`, `common.detail`, `common.submit`)
 * when rendering DSL list pages. If any of these go missing from
 * `platform/src/main/resources/i18n.zh-CN.yaml`, users will see English
 * tokens (e.g. "Sort / Fields / Created Today") leaking into the page.
 *
 * This test reads the yaml directly (line-based) so a future yaml refactor
 * cannot silently drop these keys without a failing test.
 */
import { describe, expect, it } from 'vitest';
import * as fs from 'node:fs';
import * as path from 'node:path';

const REPO_ROOT = path.resolve(__dirname, '../../../../../../../..');
const ZH_CN_YAML = path.resolve(
  REPO_ROOT,
  'platform/src/main/resources/i18n.zh-CN.yaml',
);

const REQUIRED_COMMON_KEYS = [
  // Toolbar literals (#3)
  'sort',
  'fields',
  'filter',
  'search',
  'add_filter',
  'my_records',
  'created_today',
  'modified_this_week',
  // System audit field column headers (#2)
  'created_at',
  'updated_at',
  'creator',
  'modifier',
  // Row-action / form button bare-string labels (#4)
  'create',
  'edit',
  'delete',
  'view',
  'detail',
  'submit',
  'cancel',
];

/**
 * Extract `common:` block lines (between `^common:` and the next top-level
 * key starting at column 0 that's not a comment/blank). Returns a
 * Record<string, string> for direct child keys with scalar values.
 */
function readCommonBlock(yamlPath: string): Record<string, string> {
  const raw = fs.readFileSync(yamlPath, 'utf-8');
  const lines = raw.split('\n');
  const result: Record<string, string> = {};
  let inCommon = false;
  for (const line of lines) {
    if (/^common:\s*$/.test(line)) {
      inCommon = true;
      continue;
    }
    if (inCommon) {
      // Top-level key (column 0, alpha) ends the common block.
      if (/^[A-Za-z_][\w-]*:/.test(line) && !/^common:/.test(line)) {
        inCommon = false;
        continue;
      }
      // Direct child: exactly two-space indent + `key: value`.
      const m = line.match(/^ {2}([a-z_][\w-]*):\s*(.+?)\s*$/);
      if (m && !m[2].startsWith('#')) {
        result[m[1]] = m[2];
      }
    }
  }
  return result;
}

describe('DSL list page i18n zh-CN yaml resource', () => {
  it('platform yaml file exists', () => {
    expect(fs.existsSync(ZH_CN_YAML)).toBe(true);
  });

  it('common: top-level block is present and non-empty', () => {
    const common = readCommonBlock(ZH_CN_YAML);
    expect(Object.keys(common).length).toBeGreaterThan(10);
  });

  it.each(REQUIRED_COMMON_KEYS)(
    'zh-CN yaml defines common.%s with Chinese characters',
    (key) => {
      const common = readCommonBlock(ZH_CN_YAML);
      const value = common[key];
      expect(value, `common.${key} must be defined to avoid leaking English literal`).toBeDefined();
      expect((value as string).length, `common.${key} value must be non-empty`).toBeGreaterThan(0);
      expect(
        /[一-龥]/.test(value),
        `common.${key}="${value}" must contain Chinese characters`,
      ).toBe(true);
    },
  );
});
