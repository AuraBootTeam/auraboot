import { definePlugin } from '@auraboot/plugin-sdk'
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'
import { registerMetaPageRenderers } from './runtime/registerMetaPageRenderers.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    registerMetaPageRenderers()
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
  },
})
