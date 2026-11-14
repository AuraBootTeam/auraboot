import { definePlugin } from '@auraboot/plugin-sdk'

// Register this plugin's DSL form-fill backend with the framework injection
// point (the framework rendering layer no longer imports plugin internals).
import { setDslFormFillBackend } from '~/framework/meta/rendering/dslFormFillBackend';
import { dslFormFillBackend } from './formFillBackend';

setDslFormFillBackend(dslFormFillBackend);
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
  },
})
