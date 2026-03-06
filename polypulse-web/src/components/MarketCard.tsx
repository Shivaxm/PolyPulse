import { memo } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Market } from '../types';
import Sparkline from './Sparkline';

interface MarketCardProps {
  market: Market;
  livePrice: number | undefined;
  categoryColor: string;
}

const MarketCard = memo(function MarketCard({ market, livePrice, categoryColor }: MarketCardProps) {
  const navigate = useNavigate();
  const liveYes = livePrice ?? market.yesPrice ?? (market.noPrice != null ? 1 - market.noPrice : null);
  const yesPrice = liveYes ?? 0;
  const noPrice = market.noPrice ?? (liveYes != null ? 1 - liveYes : null);
  const yesDisplay = liveYes != null ? `${Math.round(yesPrice * 100)}¢` : '—';
  const noDisplay = noPrice != null ? `${Math.round(noPrice * 100)}¢` : '—';

  const sparkPrices = market.sparkline && market.sparkline.length > 1
    ? market.sparkline.map(p => p.price)
    : [yesPrice || 0.5];

  // Determine price direction from sparkline
  const firstPrice = sparkPrices[0] ?? 0;
  const lastPrice = sparkPrices[sparkPrices.length - 1] ?? 0;
  const direction = lastPrice > firstPrice + 0.005 ? 'up' : lastPrice < firstPrice - 0.005 ? 'down' : 'flat';
  const directionColor = direction === 'up' ? 'var(--accent-green)' : direction === 'down' ? 'var(--accent-red)' : 'var(--text-muted)';
  const sparkColor = direction === 'up' ? '#10b981' : direction === 'down' ? '#ef4444' : '#6366f1';
  const changePct = firstPrice > 0 ? ((lastPrice - firstPrice) / firstPrice * 100) : 0;

  return (
    <div
      onClick={() => navigate(`/market/${market.id}`)}
      style={{
        background: 'var(--bg-card)',
        borderRadius: '0.75rem',
        padding: '1rem 1.125rem',
        cursor: 'pointer',
        border: market.hasRecentCorrelation
          ? `1px solid ${categoryColor}60`
          : '1px solid var(--border-subtle)',
        transition: 'all 0.2s ease',
        position: 'relative',
        overflow: 'hidden',
      }}
      onMouseEnter={e => {
        (e.currentTarget as HTMLElement).style.background = 'var(--bg-card-hover)';
        (e.currentTarget as HTMLElement).style.borderColor = categoryColor + '80';
        (e.currentTarget as HTMLElement).style.transform = 'translateY(-1px)';
      }}
      onMouseLeave={e => {
        (e.currentTarget as HTMLElement).style.background = 'var(--bg-card)';
        (e.currentTarget as HTMLElement).style.borderColor = market.hasRecentCorrelation ? categoryColor + '60' : 'var(--border-subtle)';
        (e.currentTarget as HTMLElement).style.transform = 'translateY(0)';
      }}
    >
      {/* Category color bar at top */}
      <div style={{
        position: 'absolute', top: 0, left: 0, right: 0, height: '2px',
        background: `linear-gradient(90deg, ${categoryColor}, transparent)`,
        opacity: 0.6,
      }} />

      {/* Category badge + correlation flag */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.625rem' }}>
        <span style={{
          fontSize: '0.625rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em',
          color: categoryColor, background: categoryColor + '15', padding: '0.15rem 0.5rem',
          borderRadius: '9999px', fontFamily: 'var(--font-sans)',
        }}>
          {market.category ?? 'uncategorized'}
        </span>
        {market.hasRecentCorrelation && (
          <span style={{
            fontSize: '0.625rem', fontWeight: 600, color: 'var(--accent-amber)',
            display: 'flex', alignItems: 'center', gap: '0.25rem',
          }}>
            <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor">
              <path d="M13 3L4 14h7l-2 7 9-11h-7l2-7z" />
            </svg>
            NEWS
          </span>
        )}
      </div>

      {/* Question */}
      <h3 style={{
        fontSize: '0.8125rem', fontWeight: 500, color: 'var(--text-primary)',
        marginBottom: '0.75rem', minHeight: '2.25rem', lineHeight: 1.4,
        overflow: 'hidden', display: '-webkit-box',
        WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
      }}>
        {market.question}
      </h3>

      {/* Price + Sparkline row */}
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.125rem' }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: '0.375rem' }}>
            <span style={{
              fontSize: '1.2rem', fontWeight: 700, color: 'var(--accent-green)',
              fontFamily: 'var(--font-mono)', lineHeight: 1,
            }}>
              Yes {yesDisplay}
            </span>
            {direction !== 'flat' && (
              <span style={{
                fontSize: '0.6875rem', fontWeight: 600, color: directionColor,
                fontFamily: 'var(--font-mono)', display: 'flex', alignItems: 'center', gap: '0.125rem',
              }}>
                {direction === 'up' ? '▲' : '▼'}
                {Math.abs(changePct).toFixed(1)}%
              </span>
            )}
          </div>
          <span style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
            No {noDisplay}
          </span>
        </div>
        <Sparkline data={sparkPrices} color={sparkColor} />
      </div>

      {/* Footer: volume */}
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        fontSize: '0.6875rem', color: 'var(--text-muted)',
      }}>
        <span style={{ fontFamily: 'var(--font-mono)' }}>
          {market.volume24h != null && market.volume24h > 0
            ? `$${(market.volume24h / 1000).toFixed(1)}k vol`
            : '—'}
        </span>
        <span style={{ fontSize: '0.625rem', fontFamily: 'var(--font-mono)' }}>
          Yes + No ≈ {liveYes != null && noPrice != null ? `${Math.round((yesPrice + noPrice) * 100)}¢` : '—'}
        </span>
      </div>
    </div>
  );
});

export default MarketCard;
