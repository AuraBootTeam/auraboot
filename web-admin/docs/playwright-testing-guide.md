# Playwright E2E Testing Guide

> AuraBoot E2E Testing Architecture and Best Practices

---

## 1. Architecture Overview

### 1.1 Design Principles

1. **Real Authentication** - All tests use real login against the actual backend. No mock authentication.
2. **State Caching** - Login state is cached in `storageState` to avoid repeated UI logins.
3. **API-first Data Preparation** - Use API calls for test data setup, not UI operations.
4. **Test Independence** - Each test must be self-contained and runnable in any order.
5. **No Hard-coded Waits** - Use proper waiting strategies instead of `waitForTimeout`.

### 1.2 Technology Stack

| Component | Technology |
|-----------|------------|
| Test Framework | Playwright Test |
| Language | TypeScript |
| Assertion | Playwright's built-in `expect` |
| Browser | Chromium (primary), Firefox/WebKit (optional) |
| CI Integration | GitHub Actions |

---

## 2. Directory Structure

```
tests/
├── e2e/                          # E2E test cases (grouped by feature)
│   ├── auth/
│   │   └── login.spec.ts
│   ├── model/
│   │   ├── model-crud.spec.ts
│   │   ├── field-management.spec.ts
│   │   └── dictionary.spec.ts
│   ├── page-designer/
│   │   ├── smart-components.spec.ts
│   │   └── field-properties.spec.ts
│   ├── header/
│   │   └── header-features.spec.ts
│   └── dynamic/
│       └── store-crud.spec.ts
│
├── fixtures/                     # Playwright Fixtures (core)
│   ├── auth.fixture.ts           # Authentication fixture
│   ├── api.fixture.ts            # API client fixture
│   ├── test-data.fixture.ts      # Test data fixture
│   └── index.ts                  # Unified export
│
├── helpers/                      # Pure logic utilities (no Playwright API)
│   ├── api-client.ts             # HTTP API wrapper
│   ├── data-factory.ts           # Test data generators
│   ├── validators.ts             # Validation helpers
│   └── constants.ts              # Constants
│
├── pages/                        # Page Objects (lightweight)
│   ├── base.page.ts
│   ├── login.page.ts
│   ├── model-list.page.ts
│   └── dynamic-form.page.ts
│
├── workflows/                    # Reusable business flows (critical)
│   ├── login.workflow.ts
│   ├── create-model.workflow.ts
│   └── create-record.workflow.ts
│
├── test-data/                    # Static test data
│   ├── users.ts
│   └── models.ts
│
├── storage/                      # Auth state cache
│   ├── admin.json                # Cached login state
│   └── .gitkeep
│
├── global-setup.ts               # Global initialization (generates login state)
├── global-teardown.ts            # Global cleanup
└── playwright.config.ts          # Configuration
```

### 2.1 Directory Responsibilities

| Directory | Purpose | Example |
|-----------|---------|---------|
| `e2e/` | Test specs organized by feature | `model/model-crud.spec.ts` |
| `fixtures/` | Playwright test fixtures | `{ api, authedPage }` |
| `helpers/` | Pure functions, no Playwright API | `generateCode('model')` |
| `pages/` | Page Objects for complex pages | `ModelListPage` |
| `workflows/` | Multi-step business flows | `createModel(api, data)` |
| `test-data/` | Static test data definitions | `TEST_USERS` |
| `storage/` | Cached auth state | `admin.json` |

---

## 3. Fixtures Usage

### 3.1 Built-in Fixtures

```typescript
import { test, expect } from '../fixtures';

test('example test', async ({ page, api, authedPage }) => {
  // page - standard Playwright page
  // api - ApiClient instance with auth
  // authedPage - page with storageState applied
});
```

### 3.2 Custom Fixtures

The `fixtures/index.ts` exports an extended test with custom fixtures:

```typescript
// fixtures/index.ts
import { test as base, expect } from '@playwright/test';
import { ApiClient } from '../helpers/api-client';

type Fixtures = {
  api: ApiClient;
};

export const test = base.extend<Fixtures>({
  api: async ({ page }, use) => {
    // ApiClient uses page.request to inherit auth cookies
    const client = new ApiClient(page);
    await use(client);
  },
});

export { expect };
```

### 3.3 Using Fixtures in Tests

```typescript
// e2e/model/model-crud.spec.ts
import { test, expect } from '../../fixtures';
import { createModelData } from '../../helpers/data-factory';

test.describe('Model CRUD', () => {
  test('can create a model', async ({ page, api }) => {
    const modelData = createModelData();

    // API for data setup
    const response = await api.createModel(modelData);
    expect(response.success).toBe(true);

    // UI for verification
    await page.goto(`/meta/models/${response.data.pid}`);
    await expect(page.locator('h1')).toContainText(modelData.displayName);
  });
});
```

---

## 4. Workflows Design Pattern

### 4.1 What is a Workflow?

A workflow encapsulates a multi-step business operation that:
- May involve API calls and/or UI interactions
- Is reusable across multiple tests
- Handles errors gracefully
- Returns structured data

### 4.2 Workflow Example

```typescript
// workflows/create-model.workflow.ts
import { ApiClient } from '../helpers/api-client';
import { createModelData, ModelTestData } from '../helpers/data-factory';

export interface CreateModelOptions {
  code?: string;
  name?: string;
  modelType?: 'ENTITY' | 'VIEW' | 'AGGREGATE';
}

export interface CreateModelResult {
  pid: string;
  code: string;
  modelType: string;
}

export async function createModel(
  api: ApiClient,
  options: CreateModelOptions = {}
): Promise<CreateModelResult> {
  const data = createModelData({
    code: options.code,
    displayName: options.name,
    modelType: options.modelType || 'ENTITY',
  });

  const response = await api.createModel(data);

  if (!api.isSuccess(response)) {
    throw new Error(`Failed to create model: ${response.message || response.desc}`);
  }

  return {
    pid: response.data!.pid,
    code: response.data!.code,
    modelType: response.data!.modelType,
  };
}
```

### 4.3 Using Workflows in Tests

```typescript
import { test, expect } from '../../fixtures';
import { createModel } from '../../workflows/create-model.workflow';

test('can delete a model', async ({ page, api }) => {
  // Arrange: Create model via workflow
  const model = await createModel(api, { modelType: 'ENTITY' });

  // Act: Navigate and delete via UI
  await page.goto(`/meta/models/${model.pid}`);
  await page.click('button:has-text("Delete")');
  await page.click('button:has-text("Confirm")');

  // Assert: Verify deletion
  await expect(page).toHaveURL('/meta/models');
  const getResponse = await api.getModelByPid(model.pid);
  expect(api.isSuccess(getResponse)).toBe(false);
});
```

---

## 5. Login State Management (storageState)

### 5.1 How It Works

1. **auth setup project (`tests/auth.setup.ts`)** runs first
2. Performs real login (API first, UI fallback)
3. Saves authentication state to `tests/storage/*.json`
4. **fixture setup project (`tests/api/setup/test-fixtures.setup.ts`)** runs second
5. Business projects (`chromium` / `smoke` / `critical` / `api`) depend on setup and reuse cached auth

### 5.2 Configuration

```typescript
// playwright.config.ts
export default defineConfig({
  globalTeardown: './tests/global-teardown.ts',

  projects: [
    { name: 'auth', testMatch: /auth\.setup\.ts/ },
    { name: 'setup', testMatch: /test-fixtures\.setup\.ts/, dependencies: ['auth'] },
    {
      name: 'chromium',
      dependencies: ['setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: './tests/storage/admin.json',
      },
    },
  ],
});
```

### 5.3 Runtime Profiles

```typescript
// package.json
"test": "PW_PROFILE=fast playwright test",
"test:smoke": "PW_PROFILE=smoke playwright test",
"test:critical": "PW_PROFILE=critical playwright test",
"test:full": "PW_PROFILE=full playwright test"
```

### 5.4 When Login State Expires

If tests start failing with auth errors:
1. Delete `tests/storage/admin.json`
2. Re-run tests - `auth.setup.ts` will generate fresh storage state

---

## 6. Test Data Management

### 6.1 Data Factory Pattern

```typescript
// helpers/data-factory.ts
export function generateCode(prefix: string): string {
  const timestamp = Date.now();
  const random = Math.random().toString(36).slice(2, 7);
  return `e2e_${prefix}_${timestamp}_${random}`;
}

export interface ModelTestData {
  code: string;
  displayName: string;
  modelType: 'ENTITY' | 'VIEW' | 'AGGREGATE';
  description?: string;
}

export function createModelData(overrides: Partial<ModelTestData> = {}): ModelTestData {
  const code = generateCode('model');
  return {
    code,
    displayName: `Test Model ${code}`,
    modelType: 'ENTITY',
    description: 'E2E test model',
    ...overrides,
  };
}
```

### 6.2 Test Data Cleanup

Tests should clean up their own data when possible:

```typescript
test('model workflow', async ({ api }) => {
  const model = await createModel(api);

  try {
    // Test operations...
  } finally {
    // Cleanup
    await api.deleteModel(model.pid);
  }
});
```

For complex cleanup, use `test.afterEach`:

```typescript
test.describe('Model tests', () => {
  const createdModels: string[] = [];

  test.afterEach(async ({ api }) => {
    for (const pid of createdModels) {
      await api.deleteModel(pid).catch(() => {});
    }
    createdModels.length = 0;
  });

  test('create model', async ({ api }) => {
    const model = await createModel(api);
    createdModels.push(model.pid);
    // ...
  });
});
```

---

## 7. Best Practices

### 7.1 Prohibited Patterns

```typescript
// ❌ Hard-coded waits
await page.waitForTimeout(3000);

// ❌ UI for data setup
await page.click('New');
await page.fill('[name="code"]', 'test');
await page.click('Save');

// ❌ Test interdependency
test('Test B depends on Test A', ...);

// ❌ Login in beforeEach via UI
beforeEach(async ({ page }) => {
  await login(page); // Slow - repeats for each test
});

// ❌ Using Chinese text selectors directly
await page.click('text=新增');  // Fragile - breaks if UI text changes
```

### 7.2 Recommended Patterns

```typescript
// ✅ Wait for state/visibility
await expect(page.locator('[data-testid="success-toast"]')).toBeVisible();
await page.waitForLoadState('networkidle');

// ✅ API for data preparation
const model = await api.createModel({ code: 'test_model' });
await page.goto(`/meta/models/${model.code}`);

// ✅ Each test is independent
test('Can delete model', async ({ page, api }) => {
  // Setup within test
  const model = await createModel(api);

  // Execute deletion
  await page.goto(`/meta/models/${model.pid}`);
  await page.click('[data-testid="delete-button"]');

  // Verify result
  const response = await api.getModelByPid(model.pid);
  expect(response.success).toBe(false);
});

// ✅ Use storageState
// Configured in playwright.config.ts, tests auto-authenticated

// ✅ Use data-testid for selectors
await page.click('[data-testid="create-button"]');
```

### 7.3 Waiting Strategies

| Scenario | Strategy |
|----------|----------|
| Page load | `await page.waitForLoadState('networkidle')` |
| Element visible | `await expect(locator).toBeVisible()` |
| Element hidden | `await expect(locator).not.toBeVisible()` |
| URL change | `await page.waitForURL(/pattern/)` |
| API response | `await page.waitForResponse(url => ...)` |
| Text appears | `await expect(page.locator('...')).toContainText('...')` |

### 7.4 Selector Priority

1. `data-testid` attributes (most stable)
2. ARIA roles and labels
3. Semantic HTML elements
4. CSS classes (least stable)

```typescript
// Best to worst
page.locator('[data-testid="submit-btn"]')           // Best
page.locator('button[type="submit"]')                 // Good
page.locator('.btn-primary')                          // Avoid
page.locator('button:has-text("提交")')              // Fragile
```

---

## 8. Running Tests

### 8.1 Basic Commands

```bash
# Run all tests
npx playwright test

# Run specific test file
npx playwright test tests/e2e/model/model-crud.spec.ts

# Run with UI mode (debugging)
npx playwright test --ui

# Run with headed browser
npx playwright test --headed

# Run in debug mode
npx playwright test --debug

# Generate test report
npx playwright show-report
```

### 8.2 CI Configuration

```yaml
# .github/workflows/e2e-tests.yml
name: E2E Tests
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Install dependencies
        run: npm ci
        working-directory: web-admin

      - name: Install Playwright browsers
        run: npx playwright install --with-deps chromium
        working-directory: web-admin

      - name: Run E2E tests
        run: npx playwright test
        working-directory: web-admin
        env:
          CI: true

      - name: Upload test report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report
          path: web-admin/test-results/
```

---

## 9. FAQ

### Q: Tests fail with "Login failed" error

**A:** The cached auth state may have expired. Delete `tests/storage/admin.json` and re-run tests.

### Q: How to run a single test?

**A:** Use the test title with `-g` flag:
```bash
npx playwright test -g "can create a model"
```

### Q: How to debug a failing test?

**A:** Use debug mode:
```bash
npx playwright test --debug tests/e2e/model/model-crud.spec.ts
```

### Q: Tests pass locally but fail in CI

**A:** Common causes:
1. Timing issues - add proper waits
2. Network latency - increase timeouts in CI
3. Missing dependencies - check CI logs

### Q: How to add a new test?

1. Create spec file in appropriate `e2e/` subdirectory
2. Import from `../../fixtures` for fixtures
3. Use workflows for data setup
4. Follow the naming convention: `feature-name.spec.ts`

### Q: How to mock API responses?

**A:** Generally avoid mocking in E2E tests. If absolutely necessary:
```typescript
await page.route('**/api/endpoint', route => {
  route.fulfill({ status: 200, body: JSON.stringify({ data: 'mock' }) });
});
```

---

## 10. Appendix

### 10.1 Project Structure Checklist

- [ ] `global-setup.ts` generates `admin.json`
- [ ] All tests import from `fixtures/index.ts`
- [ ] No `waitForTimeout` usage
- [ ] Data setup via API, not UI
- [ ] Each test is independent
- [ ] Documentation is up to date

### 10.2 Code Review Checklist

- [ ] Test has clear arrange/act/assert structure
- [ ] Uses proper waiting strategies
- [ ] Cleans up test data
- [ ] Doesn't depend on other tests
- [ ] Uses `data-testid` selectors where possible
- [ ] Handles edge cases

---

*Document Version: 1.0*
*Last Updated: 2026-01-30*
