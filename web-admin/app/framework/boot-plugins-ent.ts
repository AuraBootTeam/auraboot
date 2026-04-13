/**
 * boot-plugins-ent — overlay slot for enterprise plugin registration.
 *
 * In OSS this exports an empty array. The enterprise build's overlay
 * (auraboot-enterprise/web-admin-ext/plugins/ent-platform-guard/overlay/
 *  app/framework/boot-plugins-ent.ts) replaces this file with one that
 * imports the 12 ent-* plugin definitions and exports them.
 *
 * `boot-plugins.ts` concatenates this array with the core plugin list,
 * so enterprise plugins go through the same install/enable/activate
 * lifecycle as core plugins — full PluginLoader treatment, not just
 * file-overlay shadowing.
 */

import type { PluginDefinition } from '@auraboot/plugin-sdk'

export const ENT_PLUGINS: PluginDefinition[] = []
