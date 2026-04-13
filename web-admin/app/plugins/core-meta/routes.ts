import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function metaRoutes(): RouteConfigEntry[] {
  return [
    // Models
    route('/meta/models', './routes/meta/models/index.tsx'),
    route('/meta/models/new', './routes/meta/models/new.tsx'),
    route('/meta/models/:pid', './routes/meta/models/$pid.tsx'),
    route('/meta/models/:pid/edit', './routes/meta/models/$pid.edit.tsx'),
    // Fields
    route('/meta/fields', './routes/meta/fields/index.tsx'),
    route('/meta/fields/new', './routes/meta/fields/new.tsx'),
    route('/meta/fields/:pid', './routes/meta/fields/$pid.tsx'),
    route('/meta/fields/:pid/usage', './routes/meta/fields/$pid.usage.tsx'),
    route('/meta/fields/:pid/impact', './routes/meta/fields/$pid.impact.tsx'),
    // Dict
    route('/meta/dict', './routes/meta/dict/index.tsx'),
    route('/meta/dict/new', './routes/meta/dict/new.tsx'),
    route('/meta/dict/:pid', './routes/meta/dict/$pid.tsx'),
    route('/meta/dict/:pid/edit', './routes/meta/dict/$pid.edit.tsx'),
    // Named Queries
    route('/meta/named-queries', './routes/meta/named-queries/index.tsx'),
    route('/meta/named-queries/new', './routes/meta/named-queries/new.tsx'),
    route('/meta/named-queries/:pid', './routes/meta/named-queries/$pid.tsx'),
    // Misc
    route('/meta/consistency-rules', './routes/meta/consistency-rules/index.tsx'),
    route('/meta/ai-modeling', './routes/meta/ai-modeling/index.tsx'),
  ]
}
