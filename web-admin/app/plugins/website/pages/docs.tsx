import type { LoaderFunctionArgs, MetaFunction } from 'react-router';
import { useLoaderData, Link } from 'react-router';
import { DocsSidebar } from '../components/DocsSidebar';

export async function loader({ params }: LoaderFunctionArgs) {
  // Dynamic import to avoid CJS/ESM issues at Vite startup
  const { loadMdx, loadDocsSidebar, DOCS_DIR } = await import('../lib/mdx.server');
  const path = await import('path');

  const slug = params['*'] || 'getting-started/introduction';
  const mdxPath = path.join(DOCS_DIR, `${slug}.mdx`);
  const mdPath = path.join(DOCS_DIR, `${slug}.md`);

  // Try .mdx first, then .md
  for (const filePath of [mdxPath, mdPath]) {
    try {
      const { content, frontmatter } = await loadMdx(filePath);
      const sidebar = await loadDocsSidebar();
      return { content, frontmatter, sidebar, slug, error: null };
    } catch {
      // Try next extension
    }
  }

  // Not found
  const sidebar = await loadDocsSidebar();
  return {
    content: null,
    frontmatter: { title: 'Not Found' },
    sidebar,
    slug,
    error: 'Page not found',
  };
}

export const meta: MetaFunction<typeof loader> = ({ data }) => {
  if (!data) return [{ title: 'Docs — AuraBoot' }];
  return [
    { title: `${data.frontmatter.title} — AuraBoot Docs` },
    { name: 'description', content: (data.frontmatter.description as string) || '' },
  ];
};

export default function DocsPage() {
  const { content, frontmatter, sidebar, slug, error } =
    useLoaderData<typeof loader>();

  return (
    <div className="pt-20 min-h-screen flex">
      {/* Sidebar */}
      <aside className="hidden lg:block w-64 shrink-0 border-r border-gray-200 p-6 overflow-y-auto sticky top-20 h-[calc(100vh-5rem)]">
        <DocsSidebar items={sidebar} activeSlug={slug} />
      </aside>

      {/* Main content */}
      <article className="flex-1 p-8 max-w-4xl">
        {error ? (
          <div className="text-center py-20">
            <h1 className="text-2xl font-bold text-gray-900 mb-4">
              Page Not Found
            </h1>
            <p className="text-gray-600 mb-8">
              The documentation page &ldquo;{slug}&rdquo; was not found.
            </p>
            <Link
              to="/docs"
              className="text-purple-600 hover:text-purple-700 font-medium"
            >
              Back to docs
            </Link>
          </div>
        ) : (
          <div className="prose prose-gray max-w-none">
            <h1>{frontmatter.title as string}</h1>
            {frontmatter.description && (
              <p className="text-lg text-gray-600 mt-2 not-prose">
                {frontmatter.description as string}
              </p>
            )}
            <div className="mt-8 whitespace-pre-wrap font-mono text-sm bg-gray-50 p-6 rounded-lg">
              {content
                ? 'MDX content loaded successfully. Full client-side rendering will be available with MDX runtime integration.'
                : ''}
            </div>
          </div>
        )}
      </article>
    </div>
  );
}
