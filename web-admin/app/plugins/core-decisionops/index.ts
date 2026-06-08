import { definePlugin } from '@auraboot/plugin-sdk'
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'

/**
 * core-decisionops plugin. Resources (path, file, menu, permission) declared in ./resources.ts as
 * the single source of truth; the DecisionOps console page composes the F1-F8 surfaces.
 */
export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
    ctx.log.info('core-decisionops activated', { routes: RESOURCES.length })
  },
})
