import { Link, useParams } from 'react-router';
import type { MetaFunction } from 'react-router';

export const meta: MetaFunction = () => [
  { title: 'Plugin Details — AuraBoot' },
];

const PLUGINS = [
  { id: 'crm', name: 'crm', description: 'Customer relationship management with leads, opportunities, contacts, and pipeline tracking.', category: 'L1 Business', author: 'AuraBoot', version: '1.0.0' },
  { id: 'sales', name: 'Sales', description: 'Sales order management, quotations, and revenue tracking.', category: 'L1 Business', author: 'AuraBoot', version: '1.0.0' },
  { id: 'procurement', name: 'Procurement', description: 'Purchase orders, supplier management, and procurement workflows.', category: 'L1 Business', author: 'AuraBoot', version: '1.0.0' },
  { id: 'inventory', name: 'Inventory', description: 'Warehouse management, stock tracking, and inventory operations.', category: 'L1 Business', author: 'AuraBoot', version: '1.0.0' },
  { id: 'finance', name: 'Finance', description: 'Financial management with accounts payable, receivable, and reporting.', category: 'L1 Business', author: 'AuraBoot', version: '1.0.0' },
  { id: 'quality', name: 'Quality', description: 'Quality inspection, non-conformance tracking, and corrective actions.', category: 'L1 Business', author: 'AuraBoot', version: '1.0.0' },
  { id: 'project-management', name: 'Project Management', description: 'Projects, tasks, milestones, Gantt charts, and team collaboration.', category: 'Platform', author: 'AuraBoot', version: '1.0.0' },
  { id: 'agent-control-plane', name: 'Agent Control Plane', description: 'AI agent orchestration, mission control, tool registry, and observability.', category: 'Platform', author: 'AuraBoot', version: '1.0.0' },
  { id: 'pcba-solution', name: 'PCBA-ERP', description: 'Complete ERP solution for PCB assembly manufacturing with 126+ models.', category: 'Solution', author: 'AuraBoot', version: '1.0.0' },
  { id: 'quarry-solution', name: 'Quarry Management', description: 'Quarry operations management with safety, contracts, and production tracking.', category: 'Solution', author: 'AuraBoot', version: '1.0.0' },
];

const CATEGORY_COLORS: Record<string, string> = {
  'L1 Business': 'bg-blue-100 text-blue-700',
  'L2 Industry': 'bg-green-100 text-green-700',
  'Platform': 'bg-purple-100 text-purple-700',
  'Solution': 'bg-orange-100 text-orange-700',
};

export default function PluginDetailPage() {
  const { pluginId } = useParams<{ pluginId: string }>();
  const plugin = PLUGINS.find(p => p.id === pluginId);

  if (!plugin) {
    return (
      <div className="pt-24 pb-16 mx-auto max-w-7xl px-6 text-center">
        <h1 className="text-4xl font-bold text-gray-900">Plugin Not Found</h1>
        <p className="mt-4 text-gray-600">
          The plugin you are looking for does not exist.
        </p>
        <Link
          to="/plugins"
          className="mt-8 inline-block rounded-lg bg-purple-600 px-6 py-3 text-sm font-semibold text-white hover:bg-purple-700 transition-colors"
        >
          Back to Plugins
        </Link>
      </div>
    );
  }

  const badgeColor = CATEGORY_COLORS[plugin.category] ?? 'bg-gray-100 text-gray-700';

  return (
    <div className="pt-24 pb-16 mx-auto max-w-3xl px-6">
      <Link
        to="/plugins"
        className="inline-flex items-center text-sm text-gray-500 hover:text-purple-600 transition-colors"
      >
        <svg className="mr-1.5 h-4 w-4" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 19.5L8.25 12l7.5-7.5" />
        </svg>
        Back to Plugins
      </Link>

      <div className="mt-8">
        <span className={`inline-block rounded-full px-3 py-1 text-xs font-medium ${badgeColor}`}>
          {plugin.category}
        </span>
        <h1 className="mt-4 text-4xl font-bold text-gray-900">{plugin.name}</h1>
        <p className="mt-4 text-lg leading-relaxed text-gray-600">{plugin.description}</p>

        <div className="mt-8 flex items-center gap-6 text-sm text-gray-500">
          <div>
            <span className="font-medium text-gray-700">Author:</span> {plugin.author}
          </div>
          <div>
            <span className="font-medium text-gray-700">Version:</span> {plugin.version}
          </div>
        </div>

        <div className="mt-10">
          <Link
            to="/docs/guides/plugin-development"
            className="inline-block rounded-lg bg-purple-600 px-6 py-3 text-sm font-semibold text-white hover:bg-purple-700 transition-colors"
          >
            Install Plugin
          </Link>
        </div>
      </div>
    </div>
  );
}
