/** Read literal permission codes from actual INSERT registrations, never references/grants. */
export function permissionCodesFromSql(sql) {
  // Migrations use line comments for documentation; ignore comments outside SQL literals.
  let clean = '', quoted = false;
  for (let i = 0; i < sql.length; i++) {
    const c = sql[i];
    if (c === "'") {
      clean += c;
      if (quoted && sql[i + 1] === "'") { clean += sql[++i]; continue; }
      quoted = !quoted;
    } else if (!quoted && c === '-' && sql[i + 1] === '-') {
      while (i < sql.length && sql[i] !== '\n') i++;
      clean += '\n';
    } else if (!quoted && c === '/' && sql[i + 1] === '*') {
      i += 2;
      while (i < sql.length && !(sql[i] === '*' && sql[i + 1] === '/')) i++;
      i++; clean += ' ';
    } else clean += c;
  }
  const codes = new Set();
  const inserts = /\bINSERT\s+INTO\s+(?:public\.)?ab_permission\s*\(([^)]+)\)\s*(SELECT|VALUES)\s*/gi;
  for (const match of clean.matchAll(inserts)) {
    const columns = match[1].split(',').map(v => v.trim().replaceAll('"', '').toLowerCase());
    const codeIndex = columns.indexOf('code');
    if (codeIndex < 0) continue;
    let start = match.index + match[0].length;
    const values = match[2].toUpperCase() === 'VALUES';
    if (values) { if (clean[start] !== '(') continue; start++; }
    let depth = 0, quote = false, expr = '', fields = [];
    for (let i = start; i < clean.length; i++) {
      const c = clean[i];
      if (c === "'") {
        expr += c;
        if (quote && clean[i + 1] === "'") { expr += clean[++i]; continue; }
        quote = !quote; continue;
      }
      if (!quote) {
        if (depth === 0 && (c === ';' || (values && c === ')')
            || (!values && /^(FROM|WHERE|ON|UNION)\b/i.test(clean.slice(i)) && /\s/.test(clean[i - 1])))) {
          fields.push(expr.trim()); break;
        }
        if (c === '(') depth++;
        if (c === ')') depth--;
        if (c === ',' && depth === 0) { fields.push(expr.trim()); expr = ''; continue; }
      }
      expr += c;
      if (i === clean.length - 1) fields.push(expr.trim());
    }
    const literal = fields[codeIndex]?.match(/^'([a-z][a-z0-9_.:-]+)'$/i);
    if (literal) codes.add(literal[1]);
  }
  return codes;
}
