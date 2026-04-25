/**
 * HeaderPage - component page object for the top header bar.
 * This is NOT a full page PO — it represents the header component
 * present on all authenticated pages.
 *
 * Provides access to theme toggle, language switch, notifications,
 * and user menu (including logout).
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';

export class HeaderPage {
  constructor(public readonly page: Page) {}

  // --- Header container ---

  /** The top-level <header> element */
  get header(): Locator {
    return this.page.locator('header');
  }

  /** Wait for the header to be visible (indicates page is rendered) */
  async waitForHeader(): Promise<void> {
    await expect(this.header).toBeVisible();
  }

  // --- Theme toggle ---

  /** The theme toggle container [data-testid="theme-toggle"] */
  get themeToggle(): Locator {
    return this.page.locator('[data-testid="theme-toggle"]');
  }

  /** The clickable theme button inside the toggle */
  get themeButton(): Locator {
    return this.page
      .locator('[data-testid="theme-toggle"] button, [data-testid="theme-toggle"]')
      .first();
  }

  /** The theme dropdown panel */
  get themeDropdown(): Locator {
    return this.page
      .locator(
        '[data-testid="theme-dropdown"], [role="menu"]:has-text("浅色"), [role="menu"]:has-text("Dark")',
      )
      .first();
  }

  /** Open the theme dropdown if current UI uses dropdown mode. */
  async openThemeDropdown(): Promise<boolean> {
    // Wait for button to be visible and stable (don't use networkidle — DSL pages have ongoing API calls)
    await this.themeButton.waitFor({ state: 'visible', timeout: 10000 });
    await this.themeButton.click();
    let visible = await this.themeDropdown.isVisible({ timeout: 3000 }).catch(() => false);
    if (!visible) {
      // Retry — sometimes first click doesn't register during hydration
      await this.themeButton.click();
      visible = await this.themeDropdown.isVisible({ timeout: 3000 }).catch(() => false);
    }
    return visible;
  }

  /**
   * Select a theme option by matching text.
   * @param theme - text to match, e.g. /深色|Dark/ or /浅色|Light/
   */
  async selectTheme(theme: string | RegExp): Promise<void> {
    const hasDropdown = await this.openThemeDropdown();
    if (hasDropdown) {
      const option = this.themeDropdown.locator('button').filter({ hasText: theme });
      await expect(option).toBeVisible();
      await option.click();
      return;
    }

    // Some UI variants use a direct light/dark toggle without dropdown.
    const isDarkTarget =
      typeof theme === 'string' ? /dark|深色/i.test(theme) : theme.test('Dark 深色');
    const isDarkNow = await this.page
      .locator('html')
      .getAttribute('class')
      .then((v) => /dark/.test(v ?? ''))
      .catch(() => false);
    if (isDarkNow !== isDarkTarget) {
      await this.themeButton.click();
    }
  }

  /** Select dark theme */
  async selectDarkTheme(): Promise<void> {
    await this.selectTheme(/深色|Dark/);
  }

  /** Select light theme */
  async selectLightTheme(): Promise<void> {
    await this.selectTheme(/浅色|Light/);
  }

  /** Assert that dark mode is active on <html> */
  async expectDarkMode(): Promise<void> {
    await expect
      .poll(
        async () => {
          const className = await this.page.locator('html').getAttribute('class');
          return /dark/.test(className ?? '');
        },
        { timeout: 5000 },
      )
      .toBe(true);
  }

  /** Assert that dark mode is NOT active on <html> */
  async expectLightMode(): Promise<void> {
    await expect
      .poll(
        async () => {
          const className = await this.page.locator('html').getAttribute('class');
          return /dark/.test(className ?? '');
        },
        { timeout: 5000 },
      )
      .toBe(false);
  }

  // --- Language toggle ---

  /** The language toggle container [data-testid="lang-toggle"] */
  get langToggle(): Locator {
    return this.page
      .locator(
        '[data-testid="lang-toggle"], [data-testid="language-switcher"], button[aria-label*="lang" i], button[aria-label*="语言"]',
      )
      .first();
  }

  /** The clickable language button inside the toggle */
  get langButton(): Locator {
    return this.header
      .locator(
        '[data-testid="lang-toggle"] > button, [data-testid="language-switcher"] > button, [data-testid="lang-toggle"], [data-testid="language-switcher"], button[aria-label*="lang" i], button[aria-label*="语言"]',
      )
      .first();
  }

  /** The language dropdown panel */
  get langDropdown(): Locator {
    return this.page
      .locator(
        '[data-testid="lang-dropdown"], [role="menu"]:has-text("English"), [role="menu"]:has-text("中文"), [role="listbox"]',
      )
      .first();
  }

  /** Check if the language toggle is visible */
  async isLangToggleVisible(): Promise<boolean> {
    await this.waitForHeader();
    try {
      await this.langButton.waitFor({ state: 'visible', timeout: 10000 });
      return true;
    } catch {
      return false;
    }
  }

  /** Open the language dropdown */
  async openLangDropdown(): Promise<void> {
    await this.langButton.waitFor({ state: 'visible', timeout: 10000 });
    await this.langButton.click();
    const visible = await this.langDropdown.isVisible({ timeout: 3000 }).catch(() => false);
    if (!visible) {
      // Retry — sometimes first click doesn't register during hydration
      await this.langButton.click();
    }
    await expect(this.langDropdown).toBeVisible({ timeout: 5000 });
  }

  /**
   * Switch language by selecting an option from the dropdown.
   * @param lang - text to match, e.g. 'English' or '中文'
   */
  async switchLanguage(lang: string): Promise<void> {
    await this.openLangDropdown();
    const option = this.langDropdown
      .locator('button')
      .filter({ hasText: new RegExp(lang.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i') })
      .first();
    await expect(option).toBeVisible();
    await option.click();
  }

  // --- Notifications ---

  /** The notification link in the header */
  get notificationLink(): Locator {
    return this.page
      .locator(
        'header [data-testid="notification-bell"], header a[href="/notifications"], header [data-testid="header-notifications"], header button[aria-label*="notification" i]',
      )
      .first();
  }

  /** The bell icon SVG inside the notification link */
  get notificationIcon(): Locator {
    return this.notificationLink.locator('svg');
  }

  /** Click the notification link and wait for navigation */
  async goToNotifications(): Promise<void> {
    await this.notificationLink.click();
    await Promise.race([
      this.page.waitForURL('**/notifications', { timeout: 10000 }),
      this.page
        .locator(
          '[data-testid="notification-dropdown-panel"], [data-testid="notification-panel"], [role="dialog"], [role="menu"]',
        )
        .first()
        .waitFor({ state: 'visible', timeout: 10000 }),
    ]).catch(() => null);
  }

  // --- User menu ---

  /** The user menu container [data-testid="user-menu"] */
  get userMenu(): Locator {
    return this.page.locator('[data-testid="user-menu"]');
  }

  /** The user menu button (contains the avatar) */
  get userMenuButton(): Locator {
    return this.userMenu.locator('button');
  }

  /** The user avatar image */
  get userAvatar(): Locator {
    return this.page.locator('header img[alt="User avatar"]');
  }

  /** The logout link inside the user dropdown */
  get logoutLink(): Locator {
    return this.page
      .locator(
        '[data-testid="user-dropdown"] a[href="/logout"], [data-testid="user-dropdown"] a:has-text("退出"), [data-testid="user-dropdown"] a:has-text("Log Out")',
      )
      .first();
  }

  /** The user dropdown panel */
  get userDropdown(): Locator {
    return this.page.locator('[data-testid="user-dropdown"]');
  }

  /** Check if the user is authenticated (user menu button visible) */
  async isAuthenticated(): Promise<boolean> {
    return this.userMenuButton.isVisible({ timeout: 8000 }).catch(() => false);
  }

  /**
   * Open the user dropdown menu.
   * Polls for the dropdown so the first click swallowed during hydration
   * doesn't make auth/logout flows flaky.
   */
  async openUserMenu(): Promise<void> {
    await expect
      .poll(
        async () => {
          await this.userMenuButton.click().catch(async () => {
            await this.userAvatar.click();
          });
          return this.userDropdown.isVisible({ timeout: 500 }).catch(() => false);
        },
        {
          timeout: 5000,
          intervals: [100, 250, 500, 1000],
        },
      )
      .toBe(true);

    await expect(this.logoutLink).toBeVisible({ timeout: 5000 });
  }

  /** Close the user dropdown (Escape key, fallback to clicking main area) */
  async closeUserMenu(): Promise<void> {
    await this.page.keyboard.press('Escape');
    const stillVisible = await this.userDropdown.isVisible({ timeout: 1000 }).catch(() => false);
    if (stillVisible) {
      await this.page.locator('main').click({ position: { x: 10, y: 10 } });
    }
  }

  /**
   * Perform full logout flow:
   * 1. Open user menu
   * 2. Click logout link (or fallback to logout button)
   * 3. Wait for navigation to /logout or /login
   * 4. Click "Log Out" confirmation if present
   */
  async logout(): Promise<void> {
    await this.openUserMenu();

    const logoutVisible = await this.logoutLink.isVisible({ timeout: 3000 }).catch(() => false);
    if (logoutVisible) {
      await Promise.all([
        this.page.waitForURL(/\/logout/, { timeout: 8000 }).catch(() => {}),
        this.logoutLink.click(),
      ]);
    } else {
      // Fallback: try button with logout text
      const logoutBtn = this.page.locator('button:has-text("退出")');
      await Promise.all([
        this.page.waitForURL(/\/logout/, { timeout: 8000 }).catch(() => {}),
        logoutBtn.click(),
      ]);
    }

    // Wait for navigation to logout/login page
    await this.page.waitForURL(/\/(logout|login)/, { timeout: 8000 }).catch(() => {});

    // Handle logout confirmation page if present (button text is "确认退出" in Chinese UI)
    const logoutButton = this.page.locator('button:has-text("确认退出"), button:has-text("Log Out"), button[type="submit"]').first();
    const hasLogoutButton = await logoutButton.isVisible({ timeout: 5000 }).catch(() => false);
    if (hasLogoutButton) {
      await Promise.all([
        this.page.waitForURL(/\/login/, { timeout: 10000 }).catch(() => {}),
        logoutButton.click(),
      ]);
    }
  }
}
