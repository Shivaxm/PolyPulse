import { memo } from 'react';
import type { Market } from '../types';
import Sparkline from './Sparkline';

interface MarketCardProps {
  market: Market;
  livePrice: number | undefined;
  onClick: () => void;
}

const MarketCard = memo(function MarketCard({ market, livePrice, onClick }: MarketCardProps) {
  const price = livePrice ?? market.yesPrice ?? 0;
  const priceDisplay = Math.round(price * 100);

  const sparkPrices = market.sparkline && market.sparkline.length > 1
    ? market.sparkline.map(p => p.price)
    : [market.yesPrice ?? 0.5];

  return (
    <div
      onClick={onClick}
      style={{
        background: '#1f2937',
        borderRadius: '0.5rem',
        padding: '1rem',
        cursor: 'pointer',
        border: market.hasRecentCorrelation ? '1px solid #f59e0b80' : '1px solid #374151',
      }}
    >
      <h3 style={{ fontSize: '0.875rem', color: '#e5e7eb', marginBottom: '0.75rem', minHeight: '2.5rem', overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
        {market.question}
      </h3>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
        <div>
          <span style={{ fontSize: '1.875rem', fontWeight: 'bold', color: 'white' }}>{priceDisplay}</span>
          <span style={{ fontSize: '1.125rem', color: '#9ca3af' }}>&cent;</span>
        </div>
        <Sparkline data={sparkPrices} />
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem', color: '#6b7280' }}>
        <span>{market.category ?? 'uncategorized'}</span>
        {market.volume24h != null && <span>${(market.volume24h / 1000).toFixed(1)}k vol</span>}
      </div>
      {market.hasRecentCorrelation && (
        <div style={{ marginTop: '0.5rem', fontSize: '0.75rem', color: '#fbbf24' }}>
          News impact detected
        </div>
      )}
    </div>
  );
});

export default MarketCard;
