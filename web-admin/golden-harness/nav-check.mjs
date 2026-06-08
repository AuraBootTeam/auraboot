// Full-app nav golden: the DecisionOps menu item (seeded by plugins/core-decisionops/config/menus.json)
// shows in the real sidebar, and clicking it routes to /decision-ops and renders the console.
// Requires the drt-golden stack up + a seed JWT in /tmp/drt-golden-jwt.txt (see README).
import { chromium } from '@playwright/test'
import { createCookieSessionStorage } from 'react-router'
import { readFileSync } from 'node:fs'
const JWT = readFileSync('/tmp/drt-golden-jwt.txt', 'utf8').trim()
const storage = createCookieSessionStorage({
  cookie: { name: '__session', httpOnly: true, path: '/', sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'], secure: false },
})
const s = await storage.getSession(); s.set('jwtToken', JWT)
const value = (await storage.commitSession(s, { maxAge: 604800 })).match(/__session=([^;]+)/)[1]
const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 950 } })
await ctx.addCookies([{ name: '__session', value, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }])
const page = await ctx.newPage()
try {
  await page.goto('http://127.0.0.1:5108/', { waitUntil: 'networkidle', timeout: 45000 })
  await page.waitForTimeout(2000)
  const navText = await page.$$eval('nav, aside, [class*="sidebar"], [class*="menu"]',
    els => els.map(e => e.textContent.replace(/\s+/g, ' ')).join(' | '))
  const navHasItem = navText.includes('决策中心')
  const linkCount = await page.locator('a[href*="decision-ops"]').count()
  await page.locator('a[href*="decision-ops"]').first().click({ timeout: 8000 }).catch(() => {})
  await page.waitForURL('**/decision-ops', { timeout: 8000 }).catch(() => {})
  await page.waitForSelector('[data-testid="decisionops-console"]', { timeout: 15000 }).catch(() => {})
  await page.screenshot({ path: '/tmp/drt-golden/nav-clicked.png' })
  console.log('NAV_HAS_决策中心=' + navHasItem)
  console.log('DECISION_OPS_LINK_COUNT=' + linkCount)
  console.log('URL=' + page.url())
  console.log('CONSOLE_RENDERED=' + !!(await page.$('[data-testid="decisionops-console"]')))
  console.log('TABS=' + await page.$$eval('[data-testid^="doc-tab-"]', e => e.length).catch(() => 0))
} finally {
  await browser.close()
}
