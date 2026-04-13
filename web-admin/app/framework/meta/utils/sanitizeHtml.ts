/**
 * HTML sanitization utility using DOMPurify.
 * Prevents XSS attacks when rendering user-provided HTML content.
 */
import DOMPurify from 'dompurify';

/**
 * Sanitize HTML string to prevent XSS attacks.
 * Allows safe HTML tags (formatting, links, etc.) but strips
 * scripts, event handlers, and dangerous protocols.
 *
 * @param html - Raw HTML string to sanitize
 * @returns Sanitized HTML string safe for dangerouslySetInnerHTML
 */
export function sanitizeHtml(html: string): string {
  if (!html) return '';
  return DOMPurify.sanitize(html, {
    // Allow common formatting tags
    ALLOWED_TAGS: [
      'b',
      'i',
      'em',
      'strong',
      'u',
      's',
      'strike',
      'del',
      'p',
      'br',
      'hr',
      'div',
      'span',
      'ul',
      'ol',
      'li',
      'a',
      'img',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'table',
      'thead',
      'tbody',
      'tr',
      'th',
      'td',
      'pre',
      'code',
      'blockquote',
      'sub',
      'sup',
      'small',
    ],
    // Allow safe attributes — 'style' removed to prevent CSS injection
    // (background-image URL tracking, position/z-index UI spoofing)
    ALLOWED_ATTR: [
      'href',
      'target',
      'rel',
      'src',
      'alt',
      'title',
      'class',
      'width',
      'height',
      'colspan',
      'rowspan',
    ],
    // Force all links to open in new tab
    ADD_ATTR: ['target'],
    // Disallow javascript: and data: URIs
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto|tel):|[^a-z]|[a-z+.-]+(?:[^a-z+.\-:]|$))/i,
  });
}
