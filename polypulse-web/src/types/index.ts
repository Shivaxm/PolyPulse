export interface Market {
  id: number;
  question: string;
  yesPrice: number | null;
  noPrice: number | null;
  volume24h: number | null;
  liquidity: number | null;
  category: string | null;
  createdAtSource: string | null;
  hasRecentCorrelation: boolean;
  lastUpdated: string;
  sparkline: { timestamp: string; price: number }[];
}

export interface PricePoint {
  timestamp: string;
  price: number;
  volume: number;
}

export interface CorrelationItem {
  id: number;
  market: { id: number; question: string } | null;
  news: {
    headline: string;
    source: string | null;
    url: string | null;
    publishedAt: string;
  } | null;
  priceBefore: number;
  priceAfter: number;
  priceDelta: number;
  confidence: number;
  detectedAt: string;
  reasoning: string | null;
}

export interface PriceUpdate {
  marketId: number;
  price: number;
  timestamp: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
  last: boolean;
}
