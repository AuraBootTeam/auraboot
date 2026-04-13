import { definePlugin } from '@auraboot/plugin-sdk'
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
  },
})
