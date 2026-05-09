/**
 * HTML sanitization utility using DOMPurify.
 * Prevents XSS attacks when rendering user-provided HTML content.
 */
import DOMPurify from 'dompurify';

/**
 * Sanitize HTML string to prevent XSS attacks.
 * Allows safe HTML/SVG tags (formatting, links, diagrams) but strips
 * scripts, event handlers, and dangerous protocols.
 *
 * @param html - Raw HTML string to sanitize
 * @returns Sanitized HTML string safe for dangerouslySetInnerHTML
 */
export function sanitizeHtml(html: string): string {
  if (!html) return '';
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'b', 'i', 'em', 'strong', 'u', 's', 'strike', 'del',
      'p', 'br', 'hr', 'div', 'span',
      'ul', 'ol', 'li',
      'a', 'img',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'pre', 'code', 'blockquote',
      'sub', 'sup', 'small',
      // SVG primitives — diagram support
      'svg', 'g', 'defs', 'marker',
      'path', 'rect', 'circle', 'ellipse',
      'line', 'polyline', 'polygon',
      'text', 'tspan', 'title',
    ],
    ALLOWED_ATTR: [
      'href', 'target', 'rel', 'src', 'alt', 'title', 'class',
      'width', 'height', 'colspan', 'rowspan',
      // SVG attrs (presentation only — no event handlers)
      'viewBox', 'preserveAspectRatio',
      'd', 'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin',
      'cx', 'cy', 'r', 'rx', 'ry',
      'x', 'y', 'x1', 'y1', 'x2', 'y2',
      'points', 'transform', 'opacity',
      'text-anchor', 'font-size', 'font-family', 'font-weight',
      'marker-start', 'marker-end', 'marker-mid',
      'refX', 'refY', 'markerWidth', 'markerHeight', 'orient',
      'role',
    ],
    ADD_ATTR: ['target'],
    ALLOW_DATA_ATTR: true,
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto|tel):|[^a-z]|[a-z+.-]+(?:[^a-z+.\-:]|$))/i,
  });
}
