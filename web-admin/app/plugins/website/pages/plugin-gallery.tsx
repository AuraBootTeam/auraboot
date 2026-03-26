import { useState } from 'react';
import type { MetaFunction } from 'react-router';
import { PluginCard } from '../components/PluginCard';

export const meta: MetaFunction = () => [
  { title: 'Plugin Ecosystem — AuraBoot' },
  { name: 'description', content: 'Browse 27+ plugins across business, platform, and industry layers.' },
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

const CATEGORIES = ['All', 'L1 Business', 'Platform', 'Solution'] as const;

export default function PluginGalleryPage() {
  const [filter, setFilter] = useState<string>('All');

  const filtered = filter === 'All' ? PLUGINS : PLUGINS.filter(p => p.category === filter);

  return (
    <div className="pt-24 pb-16 mx-auto max-w-7xl px-6">
      {/* Header */}
      <div className="text-center">
        <h1 className="text-4xl font-bold text-gray-900 sm:text-5xl">Plugin Ecosystem</h1>
        <p className="mt-4 text-lg text-gray-600">
          27+ plugins across business, platform, and industry layers.
        </p>
      </div>

      {/* Category filter */}
      <div className="mt-10 flex flex-wrap justify-center gap-3">
        {CATEGORIES.map(cat => (
          <button
            key={cat}
            onClick={() => setFilter(cat)}
            className={`rounded-full px-4 py-2 text-sm font-medium transition-colors ${
              filter === cat
                ? 'bg-purple-600 text-white'
                : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {cat}
          </button>
        ))}
      </div>

      {/* Plugin grid */}
      <div className="mt-12 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {filtered.map(plugin => (
          <PluginCard key={plugin.id} {...plugin} />
        ))}
      </div>
    </div>
  );
}
