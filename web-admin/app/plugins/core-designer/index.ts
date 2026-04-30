import { definePlugin } from '@auraboot/plugin-sdk'
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'
import { initRegistry } from './components/studio/registry/index.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    // Populate WidgetRegistry + BlockRegistry up-front so any
    // SchemaBlockConfigPanel / WidgetSpecificPanel mounted outside the
    // PageDesignerEditor entry chunk (block previews, standalone config
    // surfaces, tests, SSR) finds populated PropertySchema definitions.
    //
    // Without this, the schema-driven panels render null silently — see
    // memory `feedback_g1_init_registry_bootstrap`. `initRegistry()` is
    // idempotent; PageDesignerEditorImpl still calls it at module-load
    // time as a defence-in-depth measure.
    initRegistry()
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
  },
})
