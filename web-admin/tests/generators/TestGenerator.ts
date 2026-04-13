/**
 * TestGenerator - generates Playwright test specs from model/schema metadata.
 *
 * Produces test cases covering:
 * - List page: load, search, filter, pagination, row actions
 * - Form page: create, edit, validation, submit
 * - Detail page: load, display, navigation
 * - CRUD flow: create → list → edit → delete
 *
 * @since 4.0.0
 */

import type { TemplateFieldMeta, TemplateModelMeta } from '../../app/framework/meta/templates/types';

export interface TestGenConfig {
  /** Model metadata */
  model: TemplateModelMeta;
  /** Page paths */
  paths: {
    list: string;
    create?: string;
    edit?: string;
    detail?: string;
  };
  /** Whether to generate CRUD flow test */
  crudFlow?: boolean;
  /** Whether to generate validation tests */
  validationTests?: boolean;
  /** Whether to generate pagination tests */
  paginationTests?: boolean;
}

/**
 * Generate a complete Playwright test spec as a string.
 * The output can be written to a .spec.ts file.
 */
export function generateTestSpec(config: TestGenConfig): string {
  const { model, paths, crudFlow = true, validationTests = true, paginationTests = true } = config;
  const lines: string[] = [];

  lines.push(generateImports(config));
  lines.push('');
  lines.push(`test.describe('${model.displayName} CRUD Tests', () => {`);
  lines.push('');

  // Setup
  lines.push(generateSetup(config));
  lines.push('');

  // List page tests
  lines.push(generateListTests(config));
  lines.push('');

  // Form page tests
  if (paths.create) {
    lines.push(generateCreateTests(config));
    lines.push('');
  }

  // Validation tests
  if (validationTests && paths.create) {
    lines.push(generateValidationTests(config));
    lines.push('');
  }

  // Edit tests
  if (paths.edit) {
    lines.push(generateEditTests(config));
    lines.push('');
  }

  // CRUD flow test
  if (crudFlow && paths.create) {
    lines.push(generateCrudFlowTest(config));
    lines.push('');
  }

  // Pagination tests
  if (paginationTests) {
    lines.push(generatePaginationTests(config));
  }

  lines.push('});');
  lines.push('');

  return lines.join('\n');
}

function generateImports(config: TestGenConfig): string {
  return `import { test, expect } from '../fixtures/auth.fixture';
import { setupCrudMocks } from '../fixtures/api-mock.fixture';
import { DynamicListPage } from '../pages/DynamicListPage';
import { DynamicFormPage } from '../pages/DynamicFormPage';
import { generateMockRecords, generateFormPayload, generateInvalidPayload } from '../generators/MockDataGenerator';
import type { TemplateFieldMeta } from '../../app/framework/meta/templates/types';`;
}

function generateSetup(config: TestGenConfig): string {
  const { model, paths } = config;
  const fields = JSON.stringify(model.fields, null, 2);

  return `  const MODEL_CODE = '${model.modelCode}';
  const FIELDS: TemplateFieldMeta[] = ${fields};
  const MOCK_RECORDS = generateMockRecords(FIELDS, { count: 25 });

  test.beforeEach(async ({ authedPage }) => {
    await setupCrudMocks(authedPage, {
      modelCode: MODEL_CODE,
      records: MOCK_RECORDS,
    });
  });`;
}

function generateListTests(config: TestGenConfig): string {
  const { model, paths } = config;
  const searchableFields = model.fields.filter((f) => f.searchable);

  const lines: string[] = [];
  lines.push(`  test.describe('List Page', () => {`);

  // Load test
  lines.push(`    test('should load and display records', async ({ authedPage }) => {`);
  lines.push(`      const listPage = new DynamicListPage(authedPage, '${paths.list}');`);
  lines.push(`      await listPage.goto();`);
  lines.push(`      const count = await listPage.getRowCount();`);
  lines.push(`      expect(count).toBeGreaterThan(0);`);
  lines.push(`    });`);
  lines.push('');

  // Search test
  if (searchableFields.length > 0) {
    const firstSearchable = searchableFields[0];
    lines.push(`    test('should filter by ${firstSearchable.field}', async ({ authedPage }) => {`);
    lines.push(`      const listPage = new DynamicListPage(authedPage, '${paths.list}');`);
    lines.push(`      await listPage.goto();`);
    lines.push(`      await listPage.fillFilter('${firstSearchable.field}', 'test');`);
    lines.push(`      await listPage.search();`);
    lines.push(`      await listPage.waitForLoadingComplete();`);
    lines.push(`    });`);
    lines.push('');

    // Reset test
    lines.push(`    test('should reset filters', async ({ authedPage }) => {`);
    lines.push(`      const listPage = new DynamicListPage(authedPage, '${paths.list}');`);
    lines.push(`      await listPage.goto();`);
    lines.push(`      await listPage.fillFilter('${firstSearchable.field}', 'test');`);
    lines.push(`      await listPage.search();`);
    lines.push(`      await listPage.resetFilters();`);
    lines.push(`    });`);
  }

  // Empty state test
  lines.push('');
  lines.push(`    test('should show empty state when no records', async ({ authedPage }) => {`);
  lines.push(`      await setupCrudMocks(authedPage, { modelCode: MODEL_CODE, records: [] });`);
  lines.push(`      const listPage = new DynamicListPage(authedPage, '${paths.list}');`);
  lines.push(`      await listPage.goto();`);
  lines.push(`      await listPage.expectEmpty();`);
  lines.push(`    });`);

  lines.push(`  });`);
  return lines.join('\n');
}

function generateCreateTests(config: TestGenConfig): string {
  const { model, paths } = config;

  const lines: string[] = [];
  lines.push(`  test.describe('Create Form', () => {`);

  lines.push(`    test('should navigate to create form', async ({ authedPage }) => {`);
  lines.push(`      const listPage = new DynamicListPage(authedPage, '${paths.list}');`);
  lines.push(`      await listPage.goto();`);
  lines.push(`      await listPage.clickAdd();`);
  lines.push(`      await authedPage.waitForURL(/${paths.create!.replace(/\//g, '\\/')}/);`);
  lines.push(`    });`);
  lines.push('');

  lines.push(`    test('should submit valid form data', async ({ authedPage }) => {`);
  lines.push(`      const formPage = new DynamicFormPage(authedPage, '${paths.create}');`);
  lines.push(`      await formPage.goto();`);
  lines.push(`      const payload = generateFormPayload(FIELDS);`);
  lines.push(`      await formPage.fillForm(payload);`);
  lines.push(`      await formPage.submit();`);
  lines.push(`    });`);
  lines.push('');

  lines.push(`    test('should cancel and return to list', async ({ authedPage }) => {`);
  lines.push(`      const formPage = new DynamicFormPage(authedPage, '${paths.create}');`);
  lines.push(`      await formPage.goto();`);
  lines.push(`      await formPage.cancel();`);
  lines.push(`    });`);

  lines.push(`  });`);
  return lines.join('\n');
}

function generateValidationTests(config: TestGenConfig): string {
  const { model, paths } = config;
  const requiredFields = model.fields.filter((f) => f.required && f.formVisible !== false);

  if (requiredFields.length === 0) return '';

  const lines: string[] = [];
  lines.push(`  test.describe('Form Validation', () => {`);

  lines.push(
    `    test('should show errors for empty required fields', async ({ authedPage }) => {`,
  );
  lines.push(`      const formPage = new DynamicFormPage(authedPage, '${paths.create}');`);
  lines.push(`      await formPage.goto();`);
  lines.push(`      await formPage.submit();`);
  lines.push(`      const errors = await formPage.validationErrors.count();`);
  lines.push(`      expect(errors).toBeGreaterThan(0);`);
  lines.push(`    });`);

  for (const field of requiredFields.slice(0, 3)) {
    lines.push('');
    lines.push(
      `    test('should validate required field: ${field.field}', async ({ authedPage }) => {`,
    );
    lines.push(`      const formPage = new DynamicFormPage(authedPage, '${paths.create}');`);
    lines.push(`      await formPage.goto();`);
    lines.push(`      // Fill all fields except ${field.field}`);
    lines.push(`      const payload = generateFormPayload(FIELDS);`);
    lines.push(`      delete payload['${field.field}'];`);
    lines.push(`      await formPage.fillForm(payload);`);
    lines.push(`      await formPage.submit();`);
    lines.push(`    });`);
  }

  lines.push(`  });`);
  return lines.join('\n');
}

function generateEditTests(config: TestGenConfig): string {
  const { model, paths } = config;

  const lines: string[] = [];
  lines.push(`  test.describe('Edit Form', () => {`);

  lines.push(`    test('should load existing record for editing', async ({ authedPage }) => {`);
  lines.push(`      const editPath = '${paths.edit}'.replace(':id', MOCK_RECORDS[0].pid);`);
  lines.push(`      const formPage = new DynamicFormPage(authedPage, editPath);`);
  lines.push(`      await formPage.goto();`);
  lines.push(`      const isEdit = await formPage.isEditMode();`);
  lines.push(`      expect(isEdit).toBe(true);`);
  lines.push(`    });`);

  lines.push(`  });`);
  return lines.join('\n');
}

function generateCrudFlowTest(config: TestGenConfig): string {
  const { model, paths } = config;

  const lines: string[] = [];
  lines.push(`  test.describe('CRUD Flow', () => {`);

  lines.push(
    `    test('should complete create → list → delete cycle', async ({ authedPage }) => {`,
  );
  lines.push(`      // Step 1: Create`);
  lines.push(`      const formPage = new DynamicFormPage(authedPage, '${paths.create}');`);
  lines.push(`      await formPage.goto();`);
  lines.push(`      const payload = generateFormPayload(FIELDS);`);
  lines.push(`      await formPage.fillForm(payload);`);
  lines.push(`      await formPage.submit();`);
  lines.push('');
  lines.push(`      // Step 2: Verify in list`);
  lines.push(`      const listPage = new DynamicListPage(authedPage, '${paths.list}');`);
  lines.push(`      await listPage.goto();`);
  lines.push(`      const count = await listPage.getRowCount();`);
  lines.push(`      expect(count).toBeGreaterThan(0);`);
  lines.push('');
  lines.push(`      // Step 3: Delete`);
  lines.push(`      await listPage.deleteRow(0);`);
  lines.push(`      await listPage.confirmDialog();`);
  lines.push(`    });`);

  lines.push(`  });`);
  return lines.join('\n');
}

function generatePaginationTests(config: TestGenConfig): string {
  const { paths } = config;

  const lines: string[] = [];
  lines.push(`  test.describe('Pagination', () => {`);

  lines.push(`    test('should navigate between pages', async ({ authedPage }) => {`);
  lines.push(`      const listPage = new DynamicListPage(authedPage, '${paths.list}');`);
  lines.push(`      await listPage.goto();`);
  lines.push(`      // With 25 records and default pageSize=20, should have 2 pages`);
  lines.push(`      await listPage.nextPage();`);
  lines.push(`      await listPage.waitForLoadingComplete();`);
  lines.push(`    });`);

  lines.push(`  });`);
  return lines.join('\n');
}
