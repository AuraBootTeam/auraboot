import type { MetaFunction } from 'react-router';
import { useLoaderData } from 'react-router';
import { BlogCard } from '../components/BlogCard';

export async function loader() {
  const { loadBlogPosts } = await import('../lib/mdx.server');
  const posts = await loadBlogPosts();
  return { posts };
}

export const meta: MetaFunction = () => [
  { title: 'Blog — AuraBoot' },
  {
    name: 'description',
    content:
      'News, tutorials, and insights from the AuraBoot team.',
  },
];

export default function BlogListPage() {
  const { posts } = useLoaderData<typeof loader>();

  return (
    <div className="pt-24 pb-16 mx-auto max-w-7xl px-6">
      {/* Header */}
      <div className="text-center mb-12">
        <h1 className="text-4xl font-bold text-gray-900">Blog</h1>
        <p className="mt-4 text-lg text-gray-600 max-w-2xl mx-auto">
          News, tutorials, and insights from the AuraBoot team.
        </p>
      </div>

      {/* Posts grid */}
      {posts.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
          {posts.map((post) => (
            <BlogCard
              key={post.slug}
              slug={post.slug}
              title={post.title}
              excerpt={post.excerpt}
              date={post.date}
              author={post.author}
              tags={post.tags}
              coverImage={post.coverImage as string | undefined}
            />
          ))}
        </div>
      ) : (
        <div className="text-center py-20">
          <p className="text-gray-500 text-lg">No blog posts yet. Stay tuned!</p>
        </div>
      )}
    </div>
  );
}
