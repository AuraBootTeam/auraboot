export interface CommerceMoney {
  amount: string;
  currencyCode: string;
}

export interface StorefrontThemeRef {
  themeId: string;
  version?: string;
  previewToken?: string;
}

export interface StorefrontBootstrap {
  storeHandle: string;
  storeName: string;
  locale: string;
  currencyCode: string;
  theme?: StorefrontThemeRef;
  channels?: Array<{
    code: string;
    name: string;
  }>;
}

export interface MerchantStoreSummary {
  id: string;
  handle: string;
  name: string;
  status?: string;
  storefrontPath?: string;
}

export interface MerchantOperationLink {
  code: 'products' | 'inventory' | 'orders' | 'fulfillment' | 'settings' | string;
  route: string;
  enabled: boolean;
}

export interface MerchantCommerceContext {
  tenantId: number;
  selectedStore?: MerchantStoreSummary | null;
  stores: MerchantStoreSummary[];
  operations: MerchantOperationLink[];
}

export interface StorefrontProductSummary {
  id: string;
  handle: string;
  title: string;
  subtitle?: string;
  featuredImage?: string;
  price?: CommerceMoney;
  availableForSale?: boolean;
}

export interface StorefrontProductDetail extends StorefrontProductSummary {
  descriptionHtml?: string;
  media?: Array<{
    id: string;
    url: string;
    altText?: string;
  }>;
  variants?: StorefrontVariant[];
}

export interface StorefrontVariant {
  id: string;
  sku?: string;
  title: string;
  price?: CommerceMoney;
  availableForSale?: boolean;
  selectedOptions?: Array<{
    name: string;
    value: string;
  }>;
}

export interface StorefrontProductList {
  items: StorefrontProductSummary[];
  total?: number;
  nextCursor?: string;
}

export interface StorefrontCartLineInput {
  variantId: string;
  quantity: number;
}

export interface StorefrontCart {
  id: string;
  checkoutUrl?: string;
  lines: Array<{
    id: string;
    productTitle: string;
    variantTitle?: string;
    quantity: number;
    unitPrice?: CommerceMoney;
    lineTotal?: CommerceMoney;
  }>;
  subtotal?: CommerceMoney;
}

export interface CreateStorefrontCartInput {
  lines: StorefrontCartLineInput[];
}

export interface CreateCheckoutInput {
  storeHandle: string;
  cartId?: string;
  lines?: StorefrontCartLineInput[];
  email?: string;
}

export interface CheckoutSession {
  id: string;
  token: string;
  storeHandle: string;
  status: 'draft' | 'ready' | 'payment_pending' | 'completed' | 'expired' | string;
  cartId?: string;
  total?: CommerceMoney;
}

export interface CompleteCheckoutInput {
  idempotencyKey: string;
  paymentToken?: string;
}

export interface CompleteCheckoutResult {
  checkoutId: string;
  orderId?: string;
  status: 'completed' | 'requires_payment' | 'failed' | string;
}

export interface CommerceLoadState<T> {
  data: T | null;
  error: string | null;
}
