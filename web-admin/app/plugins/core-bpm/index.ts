import { definePlugin } from '@auraboot/plugin-sdk'
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'

/**
 * core-bpm plugin. Resources (paths, files, menu, permissions) declared
 * in ./resources.ts as the single source of truth.
 */
export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
    ctx.log.info('core-bpm activated', { routes: RESOURCES.length })
  },
})
