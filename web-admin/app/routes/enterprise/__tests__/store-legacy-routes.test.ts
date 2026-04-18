import { describe, expect, it } from 'vitest'
import * as legacyListRoute from '../StoreList'
import * as legacyFormSchemaRoute from '../StoreListFormSchema'
import * as storeManagementRoute from '../StoreManagement'

describe('legacy store routes', () => {
  it('re-export StoreManagement for the list route', () => {
    expect(legacyListRoute.loader).toBe(storeManagementRoute.loader)
    expect(legacyListRoute.action).toBe(storeManagementRoute.action)
    expect(legacyListRoute.default).toBe(storeManagementRoute.default)
  })

  it('re-export StoreManagement for the form-schema route', () => {
    expect(legacyFormSchemaRoute.loader).toBe(storeManagementRoute.loader)
    expect(legacyFormSchemaRoute.action).toBe(storeManagementRoute.action)
    expect(legacyFormSchemaRoute.default).toBe(storeManagementRoute.default)
  })
})
