import { Link } from 'react-router';

interface PluginCardProps {
  id: string;
  name: string;
  description: string;
  category: string;
  author: string;
  version: string;
}

const CATEGORY_COLORS: Record<string, string> = {
  'L1 Business': 'bg-blue-100 text-blue-700',
  'L2 Industry': 'bg-green-100 text-green-700',
  'Platform': 'bg-purple-100 text-purple-700',
  'Solution': 'bg-orange-100 text-orange-700',
};

export function PluginCard({ id, name, description, category, author, version }: PluginCardProps) {
  const badgeColor = CATEGORY_COLORS[category] ?? 'bg-gray-100 text-gray-700';

  return (
    <Link
      to={`/plugins/${id}`}
      className="group block rounded-xl border border-gray-200 p-6 transition-all duration-300 hover:border-purple-300 hover:shadow-lg"
    >
      <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${badgeColor}`}>
        {category}
      </span>
      <h3 className="mt-3 text-lg font-bold text-gray-900 group-hover:text-purple-600 transition-colors">
        {name}
      </h3>
      <p className="mt-2 text-sm leading-relaxed text-gray-600 line-clamp-2">
        {description}
      </p>
      <div className="mt-4 flex items-center justify-between text-xs text-gray-400">
        <span>{author}</span>
        <span>v{version}</span>
      </div>
    </Link>
  );
}
