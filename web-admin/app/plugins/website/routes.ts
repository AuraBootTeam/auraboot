/**
 * Website Platform Plugin — Route Definitions (Reference)
 *
 * NOTE: These routes are registered inline in web-admin/app/routes.ts
 * because TypeScript's module resolution cannot resolve @react-router/dev
 * from files outside the web-admin project tree.
 *
 * This file serves as the canonical reference for the website plugin's routes.
 * Any changes here must be synced to web-admin/app/routes.ts manually.
 */

// Path prefix relative to web-admin/app/
// const P = "../../plugins/platform/website/frontend";
//
// layout(`${P}/layouts/MarketingLayout.tsx`, { id: "marketing-layout" }, [
//   index(`${P}/pages/home.tsx`),                        // /
//   route("pricing", `${P}/pages/pricing.tsx`),           // /pricing
//   route("about", `${P}/pages/about.tsx`),               // /about
//   route("community", `${P}/pages/community.tsx`),       // /community
//   route("demo", `${P}/pages/demo.tsx`),                 // /demo
//   route("blog", `${P}/pages/blog-list.tsx`),            // /blog
//   route("blog/:slug", `${P}/pages/blog-post.tsx`),      // /blog/:slug
//   route("docs/*", `${P}/pages/docs.tsx`),               // /docs/*
//   route("plugins", `${P}/pages/plugin-gallery.tsx`),    // /plugins
//   route("plugins/:pluginId", `${P}/pages/plugin-detail.tsx`), // /plugins/:pluginId
// ]);

export {};
