import type { LoaderFunctionArgs, MetaFunction } from 'react-router';
import { useLoaderData, Link } from 'react-router';

export async function loader({ params }: LoaderFunctionArgs) {
  const { loadMdx, BLOG_DIR } = await import('../lib/mdx.server');
  const path = await import('path');

  const slug = params.slug;
  if (!slug) {
    return {
      compiled: null,
      frontmatter: { title: 'Not Found' },
      slug: '',
      error: 'No slug provided',
    };
  }

  const mdxPath = path.join(BLOG_DIR, `${slug}.mdx`);
  const mdPath = path.join(BLOG_DIR, `${slug}.md`);

  for (const filePath of [mdxPath, mdPath]) {
    try {
      const { compiled, frontmatter } = await loadMdx(filePath);
      return { compiled, frontmatter, slug, error: null };
    } catch {
      // Try next extension
    }
  }

  return {
    compiled: null,
    frontmatter: { title: 'Not Found' },
    slug,
    error: 'Post not found',
  };
}

export const meta: MetaFunction<typeof loader> = ({ data }) => {
  if (!data || data.error) return [{ title: 'Blog — AuraBoot' }];
  return [
    { title: `${data.frontmatter.title} — AuraBoot Blog` },
    {
      name: 'description',
      content: (data.frontmatter.excerpt as string) || '',
    },
  ];
};

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

export default function BlogPostPage() {
  const { compiled, frontmatter, slug, error } =
    useLoaderData<typeof loader>();

  if (error) {
    return (
      <div className="pt-24 pb-16 mx-auto max-w-3xl px-6 text-center">
        <h1 className="text-2xl font-bold text-gray-900 mb-4">
          Post Not Found
        </h1>
        <p className="text-gray-600 mb-8">
          The blog post &ldquo;{slug}&rdquo; could not be found.
        </p>
        <Link
          to="/blog"
          className="text-purple-600 hover:text-purple-700 font-medium"
        >
          Back to blog
        </Link>
      </div>
    );
  }

  const tags = (frontmatter.tags as string[]) || [];
  const date = frontmatter.date as string;
  const author = frontmatter.author as string;

  return (
    <div className="pt-24 pb-16 mx-auto max-w-3xl px-6">
      {/* Back link */}
      <Link
        to="/blog"
        className="inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-purple-600 transition-colors mb-8"
      >
        <svg
          className="h-4 w-4"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M15 19l-7-7 7-7"
          />
        </svg>
        Back to blog
      </Link>

      {/* Article header */}
      <header className="mb-10">
        {tags.length > 0 && (
          <div className="mb-4 flex flex-wrap gap-1.5">
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

        <h1 className="text-4xl font-bold text-gray-900 leading-tight">
          {frontmatter.title as string}
        </h1>

        <div className="mt-4 flex items-center gap-3 text-sm text-gray-500">
          {author && <span className="font-medium text-gray-700">{author}</span>}
          {author && date && <span className="text-gray-300">|</span>}
          {date && <time dateTime={date}>{formatDate(date)}</time>}
        </div>
      </header>

      {/* Article content */}
      <div className="prose prose-gray max-w-none">
        {frontmatter.excerpt && (
          <p className="text-lg text-gray-600 leading-relaxed">
            {frontmatter.excerpt as string}
          </p>
        )}
        <div className="mt-8 whitespace-pre-wrap font-mono text-sm bg-gray-50 p-6 rounded-lg">
          {compiled
            ? 'MDX content loaded successfully. Full client-side rendering will be available with MDX runtime integration.'
            : ''}
        </div>
      </div>
    </div>
  );
}
