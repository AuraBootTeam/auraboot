import { definePlugin } from '@auraboot/plugin-sdk'
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'

/**
 * core-dashboard plugin — dashboard viewer + designer.
 *
 * The legacy module barrel lives in ./module.ts and remains the entry used by
 * route-level lazy imports for both viewer and authoring surfaces.
 */
export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
  },
})
