---
type: system-reference
status: stale
---

# Playwright E2E Testing Guide

> Historical guide: some profile, CI and lifecycle examples below are stale.
> Current execution contracts live in `playwright.oss.config.ts`,
> `playwright.workflow.config.ts` and `scripts/oss-golden-stack.sh`.
> The workflow execution section below records its current prerequisites.

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
| Regression trigger | Self-contained runners on nightly cron + release (no GitHub Actions) — §8.3 |

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

### 2.2 Agent product qualification

`playwright.agent-approval.config.ts` is the explicit paid-model entry point. Its
`AGENT_ACCEPTANCE_SCOPE` selects `permissions`, `memory`, `steady`, `forms`,
`governance`, or `recovery`; it never runs global setup, resets storage, or retries
business requests. Supply an absolute private `AGENT_PERMISSION_FIXTURE`, an
absolute `AGENT_PERMISSION_EVIDENCE`, explicit owned Web/backend/DB ports,
`PG_DB`, `PW_SKIP_WEBSERVER=1`, and `AGENT_ACCEPTANCE_REAL_MODEL=deepseek`.

For `forms`, explicitly import the internal test-fixtures plugin and install the
versioned `scripts/seed-form-fill-skill.sql` through the existing initialization
flow. The `AI整理客户资料` menu opens the separate DSL page
`e2et_customer_ai_intake`; it reuses the customer schema and create command. The
fixture declares the new record's code and expected manual values before model
execution. Draft extraction, source review, undo and submission have independent
UI, trace and database assertions; a prior saved record is not a clean baseline.

`governance` requires separate business owner, reader and writer accounts, the
exact reader role and writer initial roles, and user-bound approval policies for
the selected tools. It tests the current ACP skill preview protocol, revocation
after proposal, a server-acknowledged cancellation, and private history/memory.
The private source is a bounded, successful business-turn summary; this does not
qualify retrieval of facts beyond the existing snippet limit.

`recovery` additionally requires `AGENT_PROCESS_FAULTS=owned-jvm` and exact
runtime, bundle and process paths. It checks ownership before interrupting only
the declared JVM. PG approval rows, resumed run identities and append-only plan
checkpoints are the local model-creation oracle; this profile does not claim the
external-network execution ledger was exercised. The expiry counterexample
creates a new approval under the owned policy's valid zero-hour deadline, then
restores that policy to 24 hours. Existing approval rows, Redis TTLs and clocks
are never edited. Retain every failed round, its data and the original evidence.

### 2.3 Page Designer Regression Layers

当前 `page-designer` 已收敛为 8 个稳定回归入口，不再包含历史 V2/composite/list-canvas 套件。

| Layer | Specs |
|-------|-------|
| `smoke` | `tests/e2e/page-designer/load-existing-page.spec.ts` |
| `smoke` | `tests/e2e/page-designer/field-properties.spec.ts` |
| `smoke` | `tests/e2e/page-designer/smart-components.spec.ts` |
| `critical` | `tests/e2e/page-designer/page-designer-full-lifecycle.spec.ts` |
| `critical` | `tests/e2e/page-designer/designer-toolbar-permissions.spec.ts` |
| `deep` | `tests/e2e/page-designer/designer-deep-operations.spec.ts` |
| `deep` | `tests/e2e/page-designer/custom-page-designer.spec.ts` |
| `deep` | `tests/e2e/studio/ai-page-gen.spec.ts` |

不再建议保留或恢复下列类型的 page-designer 旧 spec：

1. 仍把 `chart` / `table` / `toolbar` / `sub-table` 当成当前 form designer 主 block 契约。
2. 依赖已删除的 V2/composite workbench 或 list canvas。
3. 只做“按钮存在/面板可见”的低信号重复覆盖。

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

### 8.2 Page Designer Commands

Page Designer 回归不要再手写长文件列表，统一使用 `package.json` 中的专用脚本：

```bash
cd /home/local-oss-dev/work/auraboot/auraboot/web-admin

pnpm test:page-designer          # 完整 page-designer 回归集
pnpm test:page-designer:smoke    # 入口、属性面板、组件库基础行为
pnpm test:page-designer:critical # 主闭环和权限门禁
pnpm test:page-designer:deep     # PW_WORKERS=1 + 更长超时，避免设计器加载假红
```

推荐日常顺序：smoke → critical → deep。

### 8.3 How These Get Triggered

**这个仓库不靠 GitHub Actions 跑 E2E。** 长期回归靠自包含 runner —— 一条命令起栈、导入、
跑、报 `exit`=结果、拆栈 —— 挂在 nightly crontab 和发版脚本上。写一个 `.yml` 等于写一道
永远不会跑的门。

| 触发点 | 入口 |
|---|---|
| 改动完成判定 | `tools/golden-gate-run.sh --changed`(只跑 `coversPaths` 命中的黄金套件) |
| nightly / 每次回归 | `tools/golden-gate-run.sh`(所有 onboard 仓的 offline 档) |
| 发版 | `tools/golden-gate-run.sh --full` |
| 测试体系自证 | `./scripts/check-test-system.sh`(孤儿 spec / 命令可达 / 矩阵漂移 / 派生列) |

写完一个 spec 之后**它会不会被选中跑**,不由本文件决定,由套件注册决定 ——
未注册的 spec 在名单制 `testMatch` 下是 `No tests found` + **exit 0**,
写了却从不运行,看起来却像有。`check-e2e-spec-registration` 就是拦这一条的。

跨仓的执行/报告/债怎么串成一条链,见 workspace 根的 `tools/TEST-SYSTEM.md`。

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

### Q: Tests pass when I run them, but fail in the nightly / release runner

**A:** 先分清是**环境**还是**产品**。这两类的处理方式相反,搞混会让你去改一个没坏的测试。

1. **档位不同** — `--project=chromium` 跳过 fixtures 导入,大批 spec 变假红。全量 survey
   必须 `PW_PROFILE=oss --project=oss`。用错档位前既不能说产品 bug 也不能说没事。
2. **库不是 fresh** — 复用旧 slot 的库会静默 schema 漂移,而且上一轮的残留数据会让本轮
   "为了错误的原因通过"。runner 走 destroy → up 拿全新库就是为了这个。
3. **等待写成了固定 sleep** — `waitForTimeout` 把"还没开始"当成"正确地什么都没做",
   在快机器上绿、在慢机器上红。等真实完成信号。
4. **真的是环境挂了** — `ECONNREFUSED` / 进程退出 / 端口消失,立即停本轮归
   `environment-invalid`,别把级联失败当产品失败。

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


## Workflow execution profile

`playwright.workflow.config.ts` owns the unchanged automation designer golden and
automation deep suites. Their execution path requires the independent BPM plugin
with its backend JAR, product migrations and `WorkflowCapability` provider. The
Core-only OSS profile cannot deploy automations; exclusion from that profile is
not a passing workflow result.

Use an owned host-first stack composed by `scripts/oss-golden-stack.sh` with an
explicit frozen BPM source, `--extra-plugin-root <bpm-root> --plugin plugin`, and
`--product-migration-root <bpm-root>/migrations/bpm`. The launcher builds the BPM
JAR against the exact Core plugin API and runtime artifacts before importing it.
Pin a BPM commit compatible with that Core API. Explicit `--plugin` arguments
replace the profile list: include the required Core plugins from
`scripts/dev/plugin-import-profiles.json` in their declared order before the BPM
`--plugin plugin` argument. Passing that argument alongside `--plugin-profile demo`
does not append BPM to the demo profile.
Run the normal setup, authentication and smoke phases against that same stack,
then execute:

```bash
PLAYWRIGHT_BASE_URL=<owned-web-url> \
PW_ADMIN_STORAGE_STATE=<private-run-directory>/storage/admin.json \
PW_SKIP_WEBSERVER=1 \
pnpm exec playwright test -c playwright.workflow.config.ts --workers=1 --retries=0
```

Keep the explicit runtime database/backend environment from the launcher. The
workflow preflight requires the live BPM process-definition endpoint before the
browser suites run. Preflight, executed cases, skips and dependent cases that did
not run must be reported separately. The profile contract test compares every
original automation case with the specialized collection; moving a case never
counts as executing it.

The model-notification and automation webhook journeys start owned loopback
receivers. Launch their isolated test stack with the existing exact-host setting
`AURA_SSRF_ALLOWED_PRIVATE_HOSTS=127.0.0.1` and record it with the run. The validator
continues to enforce its normal policy for other hosts. Reapply this setting on
fresh bring-up; historical runtime receipts do not replay this optional variable
on resume. Report an initial fixture-policy failure and its configured control
separately rather than replacing the failed full-run result.
