import { chromium } from '@playwright/test'

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
const errors = []
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message))

await page.goto('http://127.0.0.1:5199/', { waitUntil: 'networkidle', timeout: 30000 })
await page.waitForSelector('[data-testid="decisionops-console"]', { timeout: 15000 })

const tabs = ['dashboard','definitions','designer','logs','model','permissions','connectors']
const results = {}
for (const t of tabs) {
  await page.click(`[data-testid="doc-tab-${t}"]`)
  await page.waitForTimeout(250)
  await page.screenshot({ path: `/tmp/drt-golden/${t}.png` })
  // assert the tab's panel rendered
  const panel = await page.$(`[data-testid="doc-panel-${t}"]`)
  results[t] = !!panel
}
// data assertions
const defRow = await page.$('[data-testid="ddl-row-big_amount_route"]')
await page.click('[data-testid="doc-tab-definitions"]'); await page.waitForTimeout(300)
const defRow2 = await page.$('[data-testid="ddl-row-big_amount_route"]')
await page.click('[data-testid="doc-tab-dashboard"]'); await page.waitForTimeout(150)
const kpi = await page.textContent('[data-testid="dd-card-match-rate"]').catch(()=>null)

console.log('TABS_RENDERED=' + JSON.stringify(results))
console.log('DEFINITIONS_ROW_PRESENT=' + !!defRow2)
console.log('DASHBOARD_MATCHRATE=' + (kpi||'').replace(/\s+/g,' '))
console.log('CONSOLE_ERRORS=' + errors.length)
errors.slice(0,5).forEach(e => console.log('  ERR: ' + e))
await browser.close()
