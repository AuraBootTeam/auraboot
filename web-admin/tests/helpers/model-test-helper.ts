/**
 * ModelTestHelper — Domain Action layer for E2E tests.
 *
 * Provides configuration-driven API operations and UI navigation
 * for model-based E2E tests, eliminating duplicate helper functions
 * across spec files.
 *
 * Architecture:
 *   ModelTestHelper (Domain Action)
 *     ├── uses → DynamicListPage (PO layer)
 *     ├── uses → DynamicFormPage (PO layer)
 *     └── uses → executeCommandViaApi (Helper layer)
 *
 * @since 6.1.0
 */

import { type Page, expect } from '@playwright/test';
import { DynamicListPage, DynamicFormPage } from '../pages';
import { executeCommandViaApi, normalizeDynamicPageKey, waitForFormReady } from '../e2e/helpers';
import { ErrorCodes } from '~/services/http-client/types';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ModelTestConfig {
  modelCode: string;
  pageKey: string;
  namespace: string;
  commands: Record<string, string>;
  defaultData: () => Record<string, unknown>;
  deleteOperationType?: string;
  children?: Record<string, ChildModelTestConfig>;
}

export interface ChildModelTestConfig extends ModelTestConfig {
  parentField: string;
}

// ---------------------------------------------------------------------------
// ModelTestHelper
// ---------------------------------------------------------------------------

export class ModelTestHelper {
  protected page: Page;
  protected config: ModelTestConfig;

  constructor(page: Page, config: ModelTestConfig) {
    this.page = page;
    this.config = config;
  }

  protected get normalizedPageKey(): string {
    return normalizeDynamicPageKey(this.config.pageKey);
  }

  // --- Config access ---

  /** Build full command code: 'submit' → 'e2et:submit_order' */
  commandCode(action: string): string {
    const cmd = this.config.commands[action];
    if (!cmd) {
      throw new Error(
        `Unknown action "${action}" for model "${this.config.modelCode}". ` +
          `Available: ${Object.keys(this.config.commands).join(', ')}`,
      );
    }
    return `${this.config.namespace}:${cmd}`;
  }

  // --- API operations (data setup/cleanup) ---

  /** Create a record via API, returning its PID. */
  async createViaApi(overrides?: Record<string, unknown>): Promise<string> {
    const payload = { ...this.config.defaultData(), ...overrides };
    const result = await executeCommandViaApi(this.page, this.commandCode('create'), payload);
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    return result.recordId;
  }

  /** Delete a record via API. */
  async deleteViaApi(pid: string): Promise<void> {
    await executeCommandViaApi(
      this.page,
      this.commandCode('delete'),
      {},
      pid,
      this.config.deleteOperationType ?? 'delete',
    );
  }

  /** Execute a named command action on a record. */
  async executeCommand(
    action: string,
    pid: string,
    payload: Record<string, unknown> = {},
  ): Promise<{ code: string; recordId: string }> {
    return executeCommandViaApi(this.page, this.commandCode(action), payload, pid);
  }

  /** Execute a sequence of state transitions on a record. */
  async transitionViaApi(pid: string, actions: string[]): Promise<void> {
    for (const action of actions) {
      const result = await this.executeCommand(action, pid);
      expect(result.code).toBe(ErrorCodes.SUCCESS);
    }
  }

  /** Fetch a single record by PID via the dynamic data API. */
  async fetchViaApi(pid: string): Promise<Record<string, unknown>> {
    const resp = await this.page.request.get(`/api/dynamic/${this.normalizedPageKey}/${pid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    return body.data ?? body;
  }

  // --- Child model access ---

  /** Get a ChildModelTestHelper for a named child model. */
  child(name: string): ChildModelTestHelper {
    const childConfig = this.config.children?.[name];
    if (!childConfig) {
      throw new Error(
        `Unknown child "${name}" for model "${this.config.modelCode}". ` +
          `Available: ${Object.keys(this.config.children ?? {}).join(', ')}`,
      );
    }
    return new ChildModelTestHelper(this.page, childConfig);
  }

  // --- UI navigation (returns PO instances) ---

  /** Navigate to the list page for this model. */
  async gotoList(): Promise<DynamicListPage> {
    const listPage = new DynamicListPage(this.page, `/p/${this.normalizedPageKey}`);
    await listPage.goto();
    return listPage;
  }

  /** Navigate to the new form page. */
  async gotoNewForm(): Promise<DynamicFormPage> {
    const formPage = new DynamicFormPage(this.page, `/p/${this.normalizedPageKey}/new`);
    await this.page.goto(`/p/${this.normalizedPageKey}/new`);
    await this.page.waitForLoadState('domcontentloaded');
    await waitForFormReady(this.page, 10000);
    return formPage;
  }

  /** Navigate to the edit form for a specific record. */
  async gotoEditForm(pid: string): Promise<DynamicFormPage> {
    const updateCommand = this.commandCode('update');
    const editPath = `/p/${this.normalizedPageKey}/${pid}/edit?commandCode=${encodeURIComponent(updateCommand)}`;
    const formPage = new DynamicFormPage(this.page, editPath);
    await this.page.goto(editPath);
    await this.page.waitForLoadState('domcontentloaded');
    await waitForFormReady(this.page, 10000);
    return formPage;
  }

  // --- Composite UI operations ---

  /** Wait for a command API response. */
  waitForCommandResponse(timeout = 10000): Promise<import('@playwright/test').Response> {
    return this.page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meta/commands/execute/${this.config.namespace}:`) &&
        r.request().method().toLowerCase() === 'post',
      { timeout },
    );
  }
}

// ---------------------------------------------------------------------------
// ChildModelTestHelper
// ---------------------------------------------------------------------------

export class ChildModelTestHelper extends ModelTestHelper {
  private childConfig: ChildModelTestConfig;

  constructor(page: Page, config: ChildModelTestConfig) {
    super(page, config);
    this.childConfig = config;
  }

  /** Create a child record for a parent, returning the child PID. */
  async createForParent(parentPid: string, overrides?: Record<string, unknown>): Promise<string> {
    const payload = {
      [this.childConfig.parentField]: parentPid,
      ...this.config.defaultData(),
      ...overrides,
    };
    const result = await executeCommandViaApi(this.page, this.commandCode('create'), payload);
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    return result.recordId;
  }

  /** List child records for a parent via the dynamic data API. */
  async listForParent(parentPid: string): Promise<unknown[]> {
    const filters = JSON.stringify([
      { fieldName: this.childConfig.parentField, operator: 'EQ', value: parentPid },
    ]);
    const resp = await this.page.request.get(
      `/api/dynamic/${this.normalizedPageKey}/list?filters=${encodeURIComponent(filters)}`,
    );
    if (!resp.ok()) return [];
    const body = await resp.json();
    return body.data?.records ?? body.data?.list ?? [];
  }
}
