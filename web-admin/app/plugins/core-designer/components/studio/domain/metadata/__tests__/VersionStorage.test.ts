import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LocalVersionStorage } from '../VersionStorage'
import { VersionStatus, VersionType } from '../types'
import type { Version } from '../types'

const makeVersion = (overrides: Partial<Version> = {}): Version => ({
  id: 'v1',
  version: '1.0.0',
  status: VersionStatus.draft,
  type: VersionType.MINOR,
  schema: { id: 'page-a' } as Version['schema'],
  createdAt: new Date('2026-01-01T00:00:00Z'),
  updatedAt: new Date('2026-01-02T00:00:00Z'),
  createdBy: 'alice',
  updatedBy: 'alice',
  ...overrides,
})

describe('LocalVersionStorage', () => {
  let storage: LocalVersionStorage

  beforeEach(() => {
    localStorage.clear()
    storage = new LocalVersionStorage()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('saves and loads a version, restoring Date instances', async () => {
    const version = makeVersion({ publishedAt: new Date('2026-01-03T00:00:00Z') })
    await storage.saveVersion(version)

    const loaded = await storage.loadVersion('v1')
    expect(loaded.id).toBe('v1')
    expect(loaded.createdAt).toBeInstanceOf(Date)
    expect(loaded.updatedAt).toBeInstanceOf(Date)
    expect(loaded.publishedAt).toBeInstanceOf(Date)
    expect(loaded.publishedAt!.toISOString()).toBe('2026-01-03T00:00:00.000Z')
  })

  it('overwrites an existing version with the same id', async () => {
    await storage.saveVersion(makeVersion())
    await storage.saveVersion(makeVersion({ status: VersionStatus.published, updatedBy: 'bob' }))

    const loaded = await storage.loadVersion('v1')
    expect(loaded.status).toBe(VersionStatus.published)
    expect(loaded.updatedBy).toBe('bob')
  })

  it('throws when loading a missing version', async () => {
    await expect(storage.loadVersion('nope')).rejects.toThrow('Version nope not found')
  })

  it('deletes a version and reports existence', async () => {
    await storage.saveVersion(makeVersion())
    await storage.saveVersion(makeVersion({ id: 'v2' }))

    expect(await storage.versionExists('v1')).toBe(true)
    await storage.deleteVersion('v1')
    expect(await storage.versionExists('v1')).toBe(false)
    expect(await storage.versionExists('v2')).toBe(true)
  })

  it('lists only the requested pageId with default newest-first sorting and pagination', async () => {
    await storage.saveVersion(makeVersion({ id: 'a1', schema: { id: 'page-a' } as Version['schema'], createdAt: new Date('2026-01-01T00:00:00Z') }))
    await storage.saveVersion(makeVersion({ id: 'a2', schema: { id: 'page-a' } as Version['schema'], createdAt: new Date('2026-03-01T00:00:00Z') }))
    await storage.saveVersion(makeVersion({ id: 'b1', schema: { id: 'page-b' } as Version['schema'] }))

    const result = await storage.listVersions('page-a', { page: 1, size: 1 })
    expect(result.total).toBe(2)
    expect(result.totalPages).toBe(2)
    expect(result.versions.map((v) => v.id)).toEqual(['a2']) // newest first, first page

    const page2 = await storage.listVersions('page-a', { page: 2, size: 1 })
    expect(page2.versions.map((v) => v.id)).toEqual(['a1'])
  })

  it('filters by status, type, creator, tags, and date range', async () => {
    const v1 = makeVersion({ id: 'v1', createdAt: new Date('2026-01-01T00:00:00Z'), tags: ['wip'] })
    const v2 = makeVersion({ id: 'v2', status: VersionStatus.published, type: VersionType.MAJOR, createdBy: 'bob', createdAt: new Date('2026-02-01T00:00:00Z') })
    const v3 = makeVersion({ id: 'v3', createdAt: new Date('2026-03-01T00:00:00Z'), tags: ['release'] })
    for (const v of [v1, v2, v3]) await storage.saveVersion(v)

    expect((await storage.listVersions('page-a', { status: VersionStatus.published })).versions.map((v) => v.id)).toEqual(['v2'])
    expect((await storage.listVersions('page-a', { type: VersionType.MAJOR })).versions.map((v) => v.id)).toEqual(['v2'])
    expect((await storage.listVersions('page-a', { createdBy: 'bob' })).versions.map((v) => v.id)).toEqual(['v2'])
    expect((await storage.listVersions('page-a', { tags: ['release'] })).versions.map((v) => v.id)).toEqual(['v3'])
    expect((await storage.listVersions('page-a', { startDate: new Date('2026-02-01T00:00:00Z') })).versions.map((v) => v.id).sort()).toEqual(['v2', 'v3'])
    expect((await storage.listVersions('page-a', { endDate: new Date('2026-01-15T00:00:00Z') })).versions.map((v) => v.id)).toEqual(['v1'])
  })

  it('sorts by version, updatedAt, and status with asc/desc ordering', async () => {
    await storage.saveVersion(makeVersion({ id: 'low', version: '1.0.0', updatedAt: new Date('2026-01-05T00:00:00Z'), status: VersionStatus.draft }))
    await storage.saveVersion(makeVersion({ id: 'high', version: '2.0.0', updatedAt: new Date('2026-01-01T00:00:00Z'), status: VersionStatus.published }))

    const byVersionAsc = await storage.listVersions('page-a', { sortBy: 'version', sortOrder: 'asc' })
    expect(byVersionAsc.versions.map((v) => v.id)).toEqual(['low', 'high'])

    const byVersionDesc = await storage.listVersions('page-a', { sortBy: 'version', sortOrder: 'desc' })
    expect(byVersionDesc.versions.map((v) => v.id)).toEqual(['high', 'low'])

    const byUpdatedAsc = await storage.listVersions('page-a', { sortBy: 'updatedAt', sortOrder: 'asc' })
    expect(byUpdatedAsc.versions.map((v) => v.id)).toEqual(['high', 'low'])

    const byStatusDesc = await storage.listVersions('page-a', { sortBy: 'status', sortOrder: 'desc' })
    // String sort: desc puts 'published' (high) before 'draft' (low).
    expect(byStatusDesc.versions.map((v) => v.id)).toEqual(['high', 'low'])
  })

  it('maintains the version index alongside saves', async () => {
    await storage.saveVersion(makeVersion())
    const raw = localStorage.getItem('designer_version_index')
    expect(raw).toBeTruthy()
    const index = JSON.parse(raw!)
    expect(index.pages['page-a']).toHaveLength(1)
    expect(index.status[VersionStatus.draft]).toContain('v1')
  })

  it('survives corrupt localStorage payloads by treating them as empty', async () => {
    localStorage.setItem('designer_versions', '{not-json')
    expect(await storage.versionExists('v1')).toBe(false)
    const result = await storage.listVersions('page-a')
    expect(result.versions).toEqual([])
  })
})
