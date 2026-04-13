import type { LocalizedText } from '@auraboot/dsl-types'

export interface MenuNode {
  key: string
  path: string
  title: LocalizedText
  icon?: string
  order: number
  group?: string
  children: MenuNode[]
}

export type MenuTree = MenuNode[]

export interface BreadcrumbItem {
  key: string
  path: string
  title: LocalizedText
}

export type BreadcrumbTrail = BreadcrumbItem[]
