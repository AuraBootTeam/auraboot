import { Link } from 'react-router';

interface BlogCardProps {
  slug: string;
  title: string;
  excerpt: string;
  date: string;
  author: string;
  tags: string[];
  coverImage?: string;
}

function formatDate(dateStr: string): string {
  try {
    return new Date(dateStr).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  } catch {
    return dateStr;
  }
}

export function BlogCard({
  slug,
  title,
  excerpt,
  date,
  author,
  tags,
  coverImage,
}: BlogCardProps) {
  return (
    <Link
      to={`/blog/${slug}`}
      className="group block overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm hover:shadow-md transition-shadow"
    >
      {/* Cover image or gradient placeholder */}
      <div className="aspect-[16/9] overflow-hidden">
        {coverImage ? (
          <img
            src={coverImage}
            alt={title}
            className="h-full w-full object-cover group-hover:scale-105 transition-transform duration-300"
          />
        ) : (
          <div className="h-full w-full bg-gradient-to-br from-purple-500 via-indigo-500 to-blue-600 flex items-center justify-center">
            <span className="text-white/40 text-6xl font-bold">
              {title.charAt(0)}
            </span>
          </div>
        )}
      </div>

      {/* Content */}
      <div className="p-5">
        {/* Tags */}
        {tags.length > 0 && (
          <div className="mb-3 flex flex-wrap gap-1.5">
            {tags.map((tag) => (
              <span
                key={tag}
                className="inline-block rounded-full bg-purple-100 px-2.5 py-0.5 text-xs font-medium text-purple-700"
              >
                {tag}
              </span>
            ))}
          </div>
        )}

        <h3 className="text-lg font-semibold text-gray-900 group-hover:text-purple-600 transition-colors line-clamp-2">
          {title}
        </h3>

        {excerpt && (
          <p className="mt-2 text-sm text-gray-600 line-clamp-3">{excerpt}</p>
        )}

        {/* Meta */}
        <div className="mt-4 flex items-center gap-3 text-xs text-gray-500">
          {author && <span>{author}</span>}
          {author && date && <span className="text-gray-300">|</span>}
          {date && <time dateTime={date}>{formatDate(date)}</time>}
        </div>
      </div>
    </Link>
  );
}
