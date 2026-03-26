export type RuntimePageType = 'list' | 'new' | 'detail' | 'page';

function normalize(value?: string | null): string {
  return String(value || '')
    .trim()
    .toLowerCase();
}

export function isCommandPageCategory(pageCategory?: string | null): boolean {
  return normalize(pageCategory) === 'command';
}

export function isDashboardPageCategory(pageCategory?: string | null): boolean {
  return normalize(pageCategory) === 'dashboard';
}

export function mapApiPageTypeToRuntime(pageType?: string | null): RuntimePageType {
  switch (normalize(pageType)) {
    case 'list':
      return 'list';
    case 'detail':
    case 'record':
      return 'detail';
    case 'page':
    case 'transaction':
      return 'page';
    case 'form':
    default:
      return 'new';
  }
}

export function mapRuntimePageTypeToSchemaType(type: string): string {
  switch (String(type || '').toLowerCase()) {
    case 'list':
      return 'list';
    case 'new':
      return 'form';
    case 'detail':
      return 'detail';
    case 'page':
      return 'page';
    default:
      return type;
  }
}

export function mapApiPageTypeToSchemaKind(pageType?: string | null): string | null {
  switch (normalize(pageType)) {
    case 'list':
      return 'List';
    case 'form':
      return 'Form';
    case 'detail':
      return 'Detail';
    case 'dashboard':
      return 'Dashboard';
    case 'page':
      return 'Page';
    case 'record':
      return 'Record';
    case 'transaction':
      return 'Transaction';
    default:
      return null;
  }
}

export function mapRuntimePageTypeToSchemaKind(pageType: string): string {
  switch (String(pageType || '').toLowerCase()) {
    case 'list':
      return 'List';
    case 'new':
      return 'Form';
    case 'detail':
      return 'Detail';
    case 'page':
      return 'Page';
    case 'record':
      return 'Record';
    case 'transaction':
      return 'Transaction';
    default:
      return pageType;
  }
}
