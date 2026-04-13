/**
 * core-demo demo page — minimal proof that PluginContext-registered
 * components reach the rendered tree.
 */
export default function DemoPage() {
  return (
    <div className="p-8">
      <h1 className="text-2xl font-semibold mb-2">AuraBoot Plugin Kernel</h1>
      <p className="text-gray-600">
        This page is rendered by <code>@auraboot/plugin-sdk</code>. The route
        was registered via <code>PluginContext.registerNavigationResource()</code>{' '}
        when the App shell called <code>pluginLoader.activateAll()</code>.
      </p>
      <p className="mt-2 text-sm text-gray-500">
        plugin code: <code>core.demo</code> · version <code>0.1.0</code>
      </p>
    </div>
  )
}
