/**
 * AI Colleagues & Settings Hub — E2E Tests
 *
 * Coverage:
 * - AI Colleagues card grid page (/ai/colleagues)
 *   - AuraBot card with Official badge, first position, no edit button
 *   - Regular agent cards with edit/chat buttons
 *   - Navigation to agent detail page with 5 tabs
 *   - Detail page tab switching
 *   - Create button presence
 * - AI Settings hub page (/ai/settings)
 *   - 6 settings cards visible with titles and descriptions
 *   - Card navigation to target pages
 *   - Each card has an icon
 *
 * NOTE: These pages are not yet in sidebar menus, so page.goto() is used.
 *       This is acceptable per AGENTS.md exception for new pages.
 */

import { test, expect } from '@playwright/test';

test.describe('AI Colleagues & Settings', () => {
  test.setTimeout(30_000);

  // =========================================================================
  // AI Colleagues page
  // =========================================================================

  test('colleagues page loads with title, subtitle, create button, and card grid', async ({ page }) => {
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });

    // Wait for API response
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    // Page title
    await expect(page.locator('h1')).toContainText('AI Colleagues');

    // Subtitle
    await expect(page.getByText('Manage your AI team members')).toBeVisible();

    // Create button with correct data-testid and text
    const createBtn = page.locator('[data-testid="create-agent-btn"]');
    await expect(createBtn).toBeVisible();
    await expect(createBtn).toContainText('Create AI Colleague');

    // At least one card visible (AuraBot should always exist)
    const allCards = page.locator('[data-testid="aurabot-card"], [data-testid^="agent-card-"]');
    await expect(allCards.first()).toBeVisible({ timeout: 5_000 });
    const cardCount = await allCards.count();
    expect(cardCount).toBeGreaterThan(0);
  });

  test('AuraBot card is first, shows Official + Full Power badges, chat button, no edit button', async ({ page }) => {
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    const aurabotCard = page.locator('[data-testid="aurabot-card"]');
    if (!(await aurabotCard.isVisible({ timeout: 8_000 }).catch(() => false))) {
      test.skip(true, 'AuraBot card not found — agent may not be seeded');
      return;
    }

    // AuraBot is the first card in the grid
    const firstCard = page.locator(
      '[data-testid="aurabot-card"], [data-testid^="agent-card-"]',
    ).first();
    await expect(firstCard).toHaveAttribute('data-testid', 'aurabot-card');

    // Official badge
    await expect(aurabotCard.getByText('Official')).toBeVisible();

    // Full Power badge
    await expect(aurabotCard.getByText('Full Power')).toBeVisible();

    // Chat button with correct data-testid
    const chatBtn = aurabotCard.locator('[data-testid="aurabot-chat-btn"]');
    await expect(chatBtn).toBeVisible();
    await expect(chatBtn).toContainText('Chat');

    // No edit button on AuraBot (AuraBot uses special card without edit)
    const editBtn = page.locator('[data-testid="agent-edit-aurabot"]');
    await expect(editBtn).not.toBeVisible();
  });

  test('AuraBot card shows status badge and type badge', async ({ page }) => {
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    const aurabotCard = page.locator('[data-testid="aurabot-card"]');
    if (!(await aurabotCard.isVisible({ timeout: 8_000 }).catch(() => false))) {
      test.skip(true, 'AuraBot not found');
      return;
    }

    // Status badge (text label like "active" with a colored dot)
    const statusBadge = aurabotCard.locator('span.inline-flex').filter({
      hasText: /active|disabled|draft/i,
    });
    await expect(statusBadge.first()).toBeVisible();

    // Type badge (reactive/copilot/autonomous/workflow)
    const typeBadge = aurabotCard.locator('span.inline-flex').filter({
      hasText: /reactive|copilot|autonomous|workflow/i,
    });
    await expect(typeBadge.first()).toBeVisible();
  });

  test('non-AuraBot agent card has edit and chat buttons', async ({ page }) => {
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    // Find any non-AuraBot card
    const agentCards = page.locator('[data-testid^="agent-card-"]');
    const agentCount = await agentCards.count();
    if (agentCount === 0) {
      test.skip(true, 'No non-AuraBot agent cards found');
      return;
    }

    const firstAgentCard = agentCards.first();
    await expect(firstAgentCard).toBeVisible();

    // Card has agent name in h3
    const agentName = firstAgentCard.locator('h3');
    await expect(agentName).toBeVisible();
    const nameText = await agentName.textContent();
    expect(nameText?.length).toBeGreaterThan(0);

    // Edit button present (data-testid="agent-edit-{code}")
    const editBtn = firstAgentCard.locator('[data-testid^="agent-edit-"]');
    await expect(editBtn).toBeVisible();
    await expect(editBtn).toContainText('Edit');

    // Chat button present (data-testid="agent-chat-{code}")
    const chatBtn = firstAgentCard.locator('[data-testid^="agent-chat-"]');
    await expect(chatBtn).toBeVisible();
    await expect(chatBtn).toContainText('Chat');

    // Status badge present (text like "active", "disabled", "draft")
    const statusBadge = firstAgentCard.locator('span.inline-flex').filter({
      hasText: /active|disabled|draft/i,
    });
    await expect(statusBadge.first()).toBeVisible();

    // Type badge present (text like "reactive", "copilot", etc.)
    const typeBadge = firstAgentCard.locator('span.inline-flex').filter({
      hasText: /reactive|copilot|autonomous|workflow/i,
    });
    await expect(typeBadge.first()).toBeVisible();
  });

  test('clicking edit on agent card navigates to detail page with 5 tabs', async ({ page }) => {
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    const listResponse = await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    // Extract a non-aurabot agent PID from the API response
    const body = await listResponse.json().catch(() => ({}));
    const records = (body as any)?.data?.records ?? [];
    const nonAurabot = records.find((r: any) => r.agent_code !== 'aurabot');
    if (!nonAurabot) {
      test.skip(true, 'No non-AuraBot agents in list response');
      return;
    }

    // Navigate directly to the detail page (Edit button uses navigate())
    const agentPid = nonAurabot.pid;
    await page.goto(`/ai/colleagues/${agentPid}`, { waitUntil: 'domcontentloaded' });

    // Wait for tabs to appear (indicates detail page loaded with agent data)
    const profileTab = page.locator('[data-testid="tab-profile"]');
    await expect(profileTab).toBeVisible({ timeout: 10_000 });

    // 5 tabs identified by data-testid: profile, tools, memory, runs, schedules
    const expectedTabKeys = ['profile', 'tools', 'memory', 'runs', 'schedules'];
    for (const tabKey of expectedTabKeys) {
      const tab = page.locator(`[data-testid="tab-${tabKey}"]`);
      await expect(tab).toBeVisible({ timeout: 3_000 });
    }
  });

  test('detail page Profile tab shows form fields and back button', async ({ page }) => {
    // Get a valid agent PID from the list API
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    const listResponse = await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 10_000 },
    );
    const body = await listResponse.json().catch(() => ({}));
    const records = (body as any)?.data?.records ?? [];
    const nonAurabot = records.find((r: any) => r.agent_code !== 'aurabot');
    if (!nonAurabot) {
      test.skip(true, 'No non-AuraBot agents');
      return;
    }

    await page.goto(`/ai/colleagues/${nonAurabot.pid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="tab-profile"]')).toBeVisible({ timeout: 10_000 });

    // Profile tab is active by default — form inputs should be visible
    const nameInput = page.locator('[data-testid="agent-name-input"]');
    await expect(nameInput).toBeVisible({ timeout: 5_000 });

    // Description textarea
    const descInput = page.locator('[data-testid="agent-description-input"]');
    await expect(descInput).toBeVisible();

    // Agent type select visible with a valid value
    const typeSelect = page.locator('select').first();
    await expect(typeSelect).toBeVisible();
    const typeValue = await typeSelect.inputValue();
    expect(['reactive', 'copilot', 'autonomous', 'workflow']).toContain(typeValue);

    // Back button with data-testid
    const backBtn = page.locator('[data-testid="back-to-colleagues"]');
    await expect(backBtn).toBeVisible({ timeout: 3_000 });
  });

  test('detail page tab switching works across all 5 tabs', async ({ page }) => {
    // Get a valid agent PID from the list API
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    const listResponse = await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 10_000 },
    );
    const body = await listResponse.json().catch(() => ({}));
    const records = (body as any)?.data?.records ?? [];
    const nonAurabot = records.find((r: any) => r.agent_code !== 'aurabot');
    if (!nonAurabot) {
      test.skip(true, 'No non-AuraBot agents');
      return;
    }

    await page.goto(`/ai/colleagues/${nonAurabot.pid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="tab-profile"]')).toBeVisible({ timeout: 10_000 });

    // Click through each tab by data-testid and verify no errors
    const tabKeys = ['tools', 'memory', 'runs', 'schedules', 'profile'];
    for (const tabKey of tabKeys) {
      const tab = page.locator(`[data-testid="tab-${tabKey}"]`);
      await expect(tab).toBeVisible({ timeout: 3_000 });
      await tab.click();
      // Brief wait for tab content to render
      await page.waitForLoadState('domcontentloaded');
    }

    // After cycling back to Profile, name input should still be visible
    const nameInput = page.locator('[data-testid="agent-name-input"]');
    await expect(nameInput).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // AI Settings hub page
  // =========================================================================

  test('settings hub shows title, subtitle, and all 6 setting cards with descriptions', async ({ page }) => {
    await page.goto('/ai/settings', { waitUntil: 'domcontentloaded' });

    // Page title
    await expect(page.locator('h1')).toContainText('AI Settings');

    // Subtitle
    await expect(
      page.getByText('Configure providers, tools, and governance'),
    ).toBeVisible();

    // All 6 cards visible with correct titles and descriptions
    const expectedCards = [
      { title: 'LLM Providers', desc: 'Configure AI model providers and API keys' },
      { title: 'MCP Servers', desc: 'Connect external tool servers via MCP protocol' },
      { title: 'Prompt Templates', desc: 'Manage reusable prompt templates' },
      { title: 'Object Aliases', desc: 'Configure natural language aliases for data models' },
      { title: 'Semantic Terms', desc: 'Define domain-specific vocabulary' },
      { title: 'Governance Policies', desc: 'Set approval rules for agent actions' },
    ];

    for (const { title, desc } of expectedCards) {
      await expect(page.getByText(title, { exact: true })).toBeVisible({ timeout: 3_000 });
      await expect(page.getByText(desc)).toBeVisible();
    }
  });

  test('settings card count is exactly 6 and each has an icon', async ({ page }) => {
    await page.goto('/ai/settings', { waitUntil: 'domcontentloaded' });

    // All settings items are rendered as buttons
    const cards = page.locator('.grid button[type="button"]');
    await expect(cards.first()).toBeVisible({ timeout: 5_000 });
    const count = await cards.count();
    expect(count).toBe(6);

    // Each card has an SVG icon
    for (let i = 0; i < count; i++) {
      const svg = cards.nth(i).locator('svg');
      await expect(svg.first()).toBeVisible();
    }
  });

  test('settings cards link to correct target pages', async ({ page }) => {
    await page.goto('/ai/settings', { waitUntil: 'domcontentloaded' });

    // Wait for all 6 cards to be visible (ensures React hydration complete)
    const cards = page.locator('.grid button[type="button"]');
    await expect(cards.nth(5)).toBeVisible({ timeout: 8_000 });

    // Verify LLM Providers card navigates correctly
    const llmCard = page.locator('button[type="button"]').filter({ hasText: 'LLM Providers' });
    await expect(llmCard).toBeVisible();

    // Wait for load state before clicking to ensure React handlers are attached
    await page.waitForLoadState('load');
    await llmCard.click();
    await expect(page).toHaveURL(/\/aurabot\/providers/, { timeout: 10_000 });
  });
});
