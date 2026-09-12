import type { APIRequestContext, Page } from '@playwright/test'

export interface RuntimeIdentityExpectation {
  applicationId: string
  applicationVersion: string
  lockIdentity: string
}

/** Read-only release identity assertion shared by independent application journeys. */
export async function readRuntimeIdentity(
  request: APIRequestContext,
  endpoint = '/api/application/identity',
): Promise<Record<string, unknown>> {
  const response = await request.get(endpoint)
  if (!response.ok()) {
    throw new Error(`runtime identity request failed: ${response.status()} ${endpoint}`)
  }
  return response.json() as Promise<Record<string, unknown>>
}

export async function assertRuntimeIdentity(
  request: APIRequestContext,
  expected: RuntimeIdentityExpectation,
): Promise<void> {
  const actual = await readRuntimeIdentity(request)
  for (const [key, value] of Object.entries(expected)) {
    if (actual[key] !== value) {
      throw new Error(`runtime identity mismatch for ${key}: expected ${value}, got ${String(actual[key])}`)
    }
  }
}

/** Navigate through the real browser and fail if the universal route does not settle. */
export async function openApplicationRoute(page: Page, path: string): Promise<void> {
  await page.goto(path)
  await page.waitForLoadState('domcontentloaded')
  if (page.url().includes('/error')) throw new Error(`application route failed: ${page.url()}`)
}
