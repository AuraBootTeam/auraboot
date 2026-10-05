/** Deployment auth appearance contract. Keep the delivery copy byte-identical. */
export const CONTENT_FIELDS = ['badge', 'headline', 'headlineEm', 'lead', 'features', 'loginTitle', 'loginLead', 'mobileLead'];
export const AUTH_LOCALES = ['zh-CN', 'en-US'];
const LIMITS = { badge: 80, headline: 120, headlineEm: 120, lead: 240, features: 160, loginTitle: 80, loginLead: 240, mobileLead: 120 };
function fail(path, message) { throw new Error(`authAppearance.${path} ${message}`); }
function object(value, keys, path) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(path, 'must be an object');
  for (const key of Object.keys(value)) if (!keys.includes(key)) fail(`${path}.${key}`, 'is unsupported');
}
function choice(value, choices, path) { if (!choices.includes(value)) fail(path, `must be one of ${choices.join(', ')}`); }
function text(value, max, path) {
  if (typeof value !== 'string' || !value.trim() || Array.from(value).length > max) fail(path, `must be non-empty text of at most ${max} characters`);
}
function url(value, path) {
  text(value, 2048, path);
  // Use exactly the supported URL grammar in the Java/JSON Schema contract.
  const supported = new RegExp("^(?:/(?!/)[^\\u0000-\\u0020\\\\]*|https://[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*(?::(?:[0-9]{1,4}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5]))?(?:[/?#][^\\u0000-\\u0020\\\\]*)?)$");
  if (!supported.test(value)) fail(path, 'must be a safe same-origin path or HTTPS URL');
}
function number(value, min, max, path) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) fail(path, `must be between ${min} and ${max}`);
}
export function validateAuthAppearance(value) {
  object(value, ['version', 'template', 'defaultLocale', 'brandPosition', 'cardPosition', 'content', 'images', 'theme', 'helpUrl'], '');
  if (value.version !== 1) fail('version', 'must be 1');
  choice(value.template, ['split', 'centered', 'background'], 'template');
  choice(value.defaultLocale, AUTH_LOCALES, 'defaultLocale');
  if (value.brandPosition !== undefined) choice(value.brandPosition, ['left', 'right'], 'brandPosition');
  if (value.cardPosition !== undefined) choice(value.cardPosition, ['left', 'center', 'right'], 'cardPosition');
  if (value.helpUrl !== undefined) url(value.helpUrl, 'helpUrl');
  if (value.content !== undefined) {
    object(value.content, CONTENT_FIELDS, 'content');
    for (const [key, field] of Object.entries(value.content)) {
      const path = `content.${key}`;
      object(field, ['mode', 'values'], path);
      choice(field.mode, ['default', 'custom', 'hidden'], `${path}.mode`);
      if (field.mode !== 'custom') { if (field.values !== undefined) fail(`${path}.values`, 'is only allowed for custom content'); continue; }
      object(field.values, AUTH_LOCALES, `${path}.values`);
      if (field.values[value.defaultLocale] === undefined) fail(`${path}.values`, 'must include the default locale');
      for (const [locale, copy] of Object.entries(field.values)) {
        if (key === 'features') {
          if (!Array.isArray(copy) || copy.length < 1 || copy.length > 4) fail(`${path}.values.${locale}`, 'must contain 1-4 features');
          for (const item of copy) text(item, LIMITS[key], `${path}.values.${locale}`);
          if (new Set(copy).size !== copy.length) fail(`${path}.values.${locale}`, 'must contain unique features');
        } else text(copy, LIMITS[key], `${path}.values.${locale}`);
      }
    }
  }
  if (value.images !== undefined) {
    const keys = ['mode', 'logoUrl', 'darkLogoUrl', 'heroUrl', 'darkHeroUrl', 'backgroundUrl', 'darkBackgroundUrl', 'focusX', 'focusY', 'mobileFocusX', 'mobileFocusY', 'overlay'];
    object(value.images, keys, 'images');
    choice(value.images.mode, ['none', 'illustration', 'background'], 'images.mode');
    for (const key of keys.filter(k => k.endsWith('Url'))) if (value.images[key] !== undefined) url(value.images[key], `images.${key}`);
    for (const key of ['focusX', 'focusY', 'mobileFocusX', 'mobileFocusY']) if (value.images[key] !== undefined) number(value.images[key], 0, 100, `images.${key}`);
    if (value.images.overlay !== undefined) number(value.images.overlay, 0, 0.8, 'images.overlay');
    if (value.images.mode === 'illustration' && !value.images.heroUrl) fail('images.heroUrl', 'is required for illustration mode');
    if (value.images.mode === 'background' && !value.images.backgroundUrl) fail('images.backgroundUrl', 'is required for background mode');
  }
  if (value.template === 'background' && value.images?.mode !== 'background') fail('images.mode', 'must be background for the background template');
  if (value.theme !== undefined) {
    object(value.theme, ['accent', 'background', 'radius', 'mode'], 'theme');
    for (const key of ['accent', 'background']) if (value.theme[key] !== undefined && (typeof value.theme[key] !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(value.theme[key]))) fail(`theme.${key}`, 'must be a six-digit hex color');
    if (value.theme.radius !== undefined) choice(value.theme.radius, ['square', 'soft', 'rounded'], 'theme.radius');
    if (value.theme.mode !== undefined) choice(value.theme.mode, ['light', 'dark', 'auto'], 'theme.mode');
  }
  return value;
}
