import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function metaRoutes(): RouteConfigEntry[] {
  return [
    // Models
    route('/meta/models', './plugins/core-meta/pages/meta/models/index.tsx'),
    route('/meta/models/new', './plugins/core-meta/pages/meta/models/new.tsx'),
    route('/meta/models/:pid', './plugins/core-meta/pages/meta/models/$pid.tsx'),
    route('/meta/models/:pid/edit', './plugins/core-meta/pages/meta/models/$pid.edit.tsx'),
    // Fields
    route('/meta/fields', './plugins/core-meta/pages/meta/fields/index.tsx'),
    route('/meta/fields/new', './plugins/core-meta/pages/meta/fields/new.tsx'),
    route('/meta/fields/:pid', './plugins/core-meta/pages/meta/fields/$pid.tsx'),
    route('/meta/fields/:pid/usage', './plugins/core-meta/pages/meta/fields/$pid.usage.tsx'),
    route('/meta/fields/:pid/impact', './plugins/core-meta/pages/meta/fields/$pid.impact.tsx'),
    // Dict
    route('/meta/dict', './plugins/core-meta/pages/meta/dict/index.tsx'),
    route('/meta/dict/new', './plugins/core-meta/pages/meta/dict/new.tsx'),
    route('/meta/dict/:pid', './plugins/core-meta/pages/meta/dict/$pid.tsx'),
    route('/meta/dict/:pid/edit', './plugins/core-meta/pages/meta/dict/$pid.edit.tsx'),
    // Named Queries
    route('/meta/named-queries', './plugins/core-meta/pages/meta/named-queries/index.tsx'),
    route('/meta/named-queries/new', './plugins/core-meta/pages/meta/named-queries/new.tsx'),
    route('/meta/named-queries/:pid', './plugins/core-meta/pages/meta/named-queries/$pid.tsx'),
    // Misc
    route('/meta/consistency-rules', './plugins/core-meta/pages/meta/consistency-rules/index.tsx'),
    route('/meta/ai-modeling', './plugins/core-meta/pages/meta/ai-modeling/index.tsx'),
  ]
}
