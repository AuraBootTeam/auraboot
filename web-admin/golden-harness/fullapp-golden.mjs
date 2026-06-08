import { chromium } from '@playwright/test'
import { createCookieSessionStorage } from 'react-router'
import { readFileSync } from 'node:fs'

const JWT = readFileSync('/tmp/drt-golden-jwt.txt', 'utf8').trim()
// replicate web-admin's auth.setup.ts session-cookie seal (__session, key jwtToken, default dev secret)
const storage = createCookieSessionStorage({
  cookie: { name: '__session', httpOnly: true, path: '/', sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'], secure: false },
})
const session = await storage.getSession()
session.set('jwtToken', JWT)
const setCookie = await storage.commitSession(session, { maxAge: 604800 })
const value = setCookie.match(/__session=([^;]+)/)[1]

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 950 } })
await ctx.addCookies([
  { name: '__session', value, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' },
])
const page = await ctx.newPage()
const errors = []
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message))
page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 140)) })
try {
  await page.goto('http://127.0.0.1:5108/decision-ops', { waitUntil: 'networkidle', timeout: 45000 })
  await page.waitForSelector('[data-testid="decisionops-console"]', { timeout: 25000 }).catch(() => {})
  await page.screenshot({ path: '/tmp/drt-golden/fullapp-dashboard.png' })
  const hasConsole = await page.$('[data-testid="decisionops-console"]')
  let defRows = null, tabs = 0
  if (hasConsole) {
    await page.click('[data-testid="doc-tab-definitions"]').catch(() => {})
    await page.waitForTimeout(1800)
    await page.screenshot({ path: '/tmp/drt-golden/fullapp-definitions.png' })
    defRows = await page.$$eval('[data-testid^="ddl-row-"]', els => els.map(e => e.textContent.slice(0, 40)))
    tabs = await page.$$eval('[data-testid^="doc-tab-"]', els => els.length)
    await page.click('[data-testid="doc-tab-designer"]').catch(() => {})
    await page.waitForTimeout(800)
    await page.screenshot({ path: '/tmp/drt-golden/fullapp-designer.png' })
  }
  console.log('FINAL_URL=' + page.url())
  console.log('HAS_CONSOLE=' + !!hasConsole)
  console.log('TABS=' + tabs)
  console.log('DEFINITION_ROWS=' + JSON.stringify(defRows))
  console.log('CONSOLE_ERRORS=' + errors.length)
  errors.slice(0, 4).forEach(e => console.log('  ERR ' + e))
} finally {
  await browser.close()
}
