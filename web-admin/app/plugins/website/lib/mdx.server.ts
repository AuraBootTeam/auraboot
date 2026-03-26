import fs from 'fs/promises';
import path from 'path';
import { createRequire } from 'module';

// gray-matter is CJS-only — use createRequire to avoid Vite SSR "require is not defined"
const esmRequire = createRequire(import.meta.url);
const matter: (input: string) => { data: Record<string, unknown>; content: string } = esmRequire('gray-matter');

// Resolve from web-admin/ (process.cwd()) to plugin content dir
const CONTENT_DIR = path.resolve(
  process.cwd(),
  '../plugins/platform/website/content',
);
const DOCS_DIR = path.join(CONTENT_DIR, 'docs');
const BLOG_DIR = path.join(CONTENT_DIR, 'blog');

// LRU cache
interface CacheEntry {
  content: string;
  frontmatter: Record<string, unknown>;
  mtime: number;
}

const cache = new Map<string, CacheEntry>();
const MAX_CACHE = 200;

export async function loadMdx(filePath: string) {
  const stat = await fs.stat(filePath);
  const cached = cache.get(filePath);
  if (cached && cached.mtime === stat.mtimeMs) {
    return { content: cached.content, frontmatter: cached.frontmatter };
  }

  const source = await fs.readFile(filePath, 'utf-8');
  const { data: frontmatter, content } = matter(source);

  const result: CacheEntry = { content, frontmatter, mtime: stat.mtimeMs };

  if (cache.size >= MAX_CACHE) {
    const firstKey = cache.keys().next().value;
    if (firstKey) cache.delete(firstKey);
  }
  cache.set(filePath, result);

  return { content, frontmatter };
}

export interface DocNavItem {
  slug: string;
  title: string;
  order: number;
  category: string;
}

export async function loadDocsSidebar(): Promise<DocNavItem[]> {
  const items: DocNavItem[] = [];
  try {
    const categories = await fs.readdir(DOCS_DIR);
    for (const cat of categories) {
      const catPath = path.join(DOCS_DIR, cat);
      const catStat = await fs.stat(catPath);
      if (!catStat.isDirectory()) continue;

      const files = await fs.readdir(catPath);
      for (const file of files) {
        if (!file.endsWith('.mdx') && !file.endsWith('.md')) continue;
        const source = await fs.readFile(path.join(catPath, file), 'utf-8');
        const { data } = matter(source);
        items.push({
          slug: `${cat}/${file.replace(/\.mdx?$/, '')}`,
          title: (data.title as string) || file.replace(/\.mdx?$/, ''),
          order: (data.order as number) || 99,
          category: cat,
        });
      }
    }
  } catch {
    // No docs directory or empty
  }
  return items.sort(
    (a, b) => a.category.localeCompare(b.category) || a.order - b.order,
  );
}

export interface BlogPost {
  slug: string;
  title: string;
  date: string;
  author: string;
  tags: string[];
  excerpt: string;
  [key: string]: unknown;
}

export async function loadBlogPosts(): Promise<BlogPost[]> {
  const posts: BlogPost[] = [];
  try {
    const files = await fs.readdir(BLOG_DIR);
    for (const file of files) {
      if (!file.endsWith('.mdx') && !file.endsWith('.md')) continue;
      const source = await fs.readFile(path.join(BLOG_DIR, file), 'utf-8');
      const { data } = matter(source);
      posts.push({
        slug: file.replace(/\.mdx?$/, ''),
        title: (data.title as string) || file.replace(/\.mdx?$/, ''),
        date: (data.date as string) || '',
        author: (data.author as string) || '',
        tags: (data.tags as string[]) || [],
        excerpt: (data.excerpt as string) || '',
        ...data,
      });
    }
  } catch {
    // No blog directory or empty
  }
  return posts.sort(
    (a, b) => new Date(b.date).getTime() - new Date(a.date).getTime(),
  );
}

export { DOCS_DIR, BLOG_DIR };
