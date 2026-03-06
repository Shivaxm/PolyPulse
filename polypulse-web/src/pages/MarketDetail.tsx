import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer,
} from 'recharts';
import type { Market, PricePoint, CorrelationItem, PagedResponse } from '../types';
import { useEventStream } from '../hooks/useEventStream';
import { apiUrl } from '../config/api';

const RANGES = ['1h', '6h', '24h', '7d'] as const;

function formatXAxis(timestamp: string, range: string): string {
  const d = new Date(timestamp);
  switch (range) {
    case '1h':
    case '6h':
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    case '24h':
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    case '7d':
      return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
    default:
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}

export default function MarketDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [market, setMarket] = useState<Market | null>(null);
  const [prices, setPrices] = useState<PricePoint[]>([]);
  const [priceLoading, setPriceLoading] = useState(true);
  const [priceError, setPriceError] = useState<string | null>(null);
  const [correlations, setCorrelations] = useState<CorrelationItem[]>([]);
  const [range, setRange] = useState<string>('24h');

  const [sseUrl, setSseUrl] = useState<string | null>(null);
  const { priceUpdates } = useEventStream(sseUrl ?? '');

  useEffect(() => {
    fetch(apiUrl(`/api/markets/${id}`))
      .then(res => { if (!res.ok) throw new Error('Not found'); return res.json(); })
      .then(data => { setMarket(data); setSseUrl(apiUrl(`/api/stream/markets/${id}`)); })
      .catch(() => navigate('/'));
  }, [id, navigate]);

  useEffect(() => {
    setPriceLoading(true);
    setPriceError(null);
    fetch(apiUrl(`/api/markets/${id}/prices?range=${range}`))
      .then(res => { if (!res.ok) throw new Error(`${res.status}`); return res.json(); })
      .then(data => { setPrices(Array.isArray(data) ? data : []); setPriceLoading(false); })
      .catch(err => { setPriceError(err.message); setPrices([]); setPriceLoading(false); });
  }, [id, range]);

  useEffect(() => {
    fetch(apiUrl(`/api/markets/${id}/correlations?size=20`))
      .then(res => res.ok ? res.json() : { content: [] })
      .then((data: PagedResponse<CorrelationItem>) => setCorrelations(data.content || []))
      .catch(() => setCorrelations([]));
  }, [id]);

  if (!market) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', paddingTop: '2rem' }}>
        <div className="skeleton" style={{ height: '2rem', width: '60%' }} />
        <div className="skeleton" style={{ height: '400px' }} />
      </div>
    );
  }

  const livePrice = priceUpdates.get(market.id)?.price ?? market.yesPrice ?? 0;
  const chartData = prices.map(p => ({
    time: formatXAxis(p.timestamp, range),
    fullTime: new Date(p.timestamp).toLocaleString(),
    price: Number(p.price),
  }));

  return (
    <div>
      {/* Back button */}
      <button
        onClick={() => navigate('/')}
        style={{
          color: 'var(--text-muted)', background: 'none', border: 'none',
          cursor: 'pointer', marginBottom: '1rem', fontSize: '0.8125rem',
          fontFamily: 'var(--font-sans)', display: 'flex', alignItems: 'center', gap: '0.375rem',
          transition: 'color 0.15s',
        }}
        onMouseEnter={e => (e.currentTarget.style.color = 'var(--text-secondary)')}
        onMouseLeave={e => (e.currentTarget.style.color = 'var(--text-muted)')}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
        </svg>
        Back to Markets
      </button>

      {/* Market header */}
      <div style={{
        background: 'var(--bg-panel)', borderRadius: '0.75rem', padding: '1.25rem',
        border: '1px solid var(--border-subtle)', marginBottom: '1rem',
      }}>
        {market.category && (
          <span style={{
            fontSize: '0.625rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em',
            color: 'var(--accent-blue)', background: 'rgba(59, 130, 246, 0.12)', padding: '0.15rem 0.5rem',
            borderRadius: '9999px', marginBottom: '0.5rem', display: 'inline-block',
          }}>
            {market.category}
          </span>
        )}
        <h1 style={{
          fontSize: '1.25rem', fontWeight: 700, color: 'white', marginBottom: '0.75rem',
          fontFamily: 'var(--font-sans)', lineHeight: 1.3,
        }}>
          {market.question}
        </h1>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '1.5rem' }}>
          <div>
            <span style={{ fontSize: '2.5rem', fontWeight: 700, color: 'white', fontFamily: 'var(--font-mono)', lineHeight: 1 }}>
              {Math.round(livePrice * 100)}
            </span>
            <span style={{ fontSize: '1rem', color: 'var(--accent-green)', fontFamily: 'var(--font-mono)', marginLeft: '0.25rem' }}>¢ Yes</span>
          </div>
          <div>
            <span style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
              {Math.round((market.noPrice ?? 0) * 100)}
            </span>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginLeft: '0.25rem' }}>¢ No</span>
          </div>
          {market.volume24h != null && market.volume24h > 0 && (
            <span style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
              ${(market.volume24h / 1000).toFixed(1)}k vol
            </span>
          )}
        </div>
      </div>

      {/* Range selector */}
      <div style={{ display: 'flex', gap: '0.25rem', marginBottom: '0.75rem' }}>
        {RANGES.map(r => (
          <button
            key={r}
            onClick={() => setRange(r)}
            style={{
              padding: '0.375rem 0.875rem', borderRadius: '0.375rem', fontSize: '0.8125rem',
              fontWeight: 500, border: 'none', cursor: 'pointer', fontFamily: 'var(--font-sans)',
              background: range === r ? 'var(--accent-blue)' : 'var(--bg-panel)',
              color: range === r ? 'white' : 'var(--text-muted)',
              transition: 'all 0.15s',
            }}
          >
            {r}
          </button>
        ))}
      </div>

      {/* Chart */}
      <div style={{
        background: 'var(--bg-panel)', borderRadius: '0.75rem', padding: '1rem',
        marginBottom: '1.5rem', height: 380,
        border: '1px solid var(--border-subtle)',
      }}>
        {priceLoading ? (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', color: 'var(--text-muted)' }}>
            Loading price data...
          </div>
        ) : priceError ? (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', color: 'var(--accent-red)', flexDirection: 'column', gap: '0.5rem' }}>
            <div>Failed to load price data</div>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{priceError}</div>
          </div>
        ) : chartData.length === 0 ? (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', color: 'var(--text-muted)', flexDirection: 'column', gap: '0.5rem' }}>
            <div>No price data for this range</div>
            <div style={{ fontSize: '0.75rem' }}>Price history builds over time. Try a shorter range or check back later.</div>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartData}>
              <XAxis
                dataKey="time"
                stroke="var(--text-muted)"
                fontSize={10}
                tickLine={false}
                axisLine={{ stroke: 'var(--border-subtle)' }}
                fontFamily="var(--font-mono)"
              />
              <YAxis
                stroke="var(--text-muted)"
                fontSize={10}
                tickLine={false}
                axisLine={false}
                domain={['auto', 'auto']}
                tickFormatter={(v: number) => `${Math.round(v * 100)}¢`}
                fontFamily="var(--font-mono)"
                width={40}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: 'var(--bg-card)', border: '1px solid var(--border-medium)',
                  borderRadius: '0.5rem', color: 'var(--text-primary)',
                  fontFamily: 'var(--font-mono)', fontSize: '0.8125rem',
                }}
                labelStyle={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}
                formatter={(value: number | string | undefined) => {
                  const numeric = typeof value === 'number' ? value : Number(value ?? 0);
                  return [`${(numeric * 100).toFixed(1)}¢`, 'Price'];
                }}
                labelFormatter={(_, payload) => payload?.[0]?.payload?.fullTime ?? ''}
              />
              <Line
                type="monotone"
                dataKey="price"
                stroke="var(--accent-blue)"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, fill: 'var(--accent-blue)', stroke: 'var(--bg-panel)', strokeWidth: 2 }}
              />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* Correlations */}
      <h2 style={{
        fontSize: '1rem', fontWeight: 600, color: 'white', marginBottom: '0.75rem',
        fontFamily: 'var(--font-sans)', display: 'flex', alignItems: 'center', gap: '0.5rem',
      }}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ color: 'var(--accent-amber)' }}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
        </svg>
        News Correlations
      </h2>

      {correlations.length === 0 ? (
        <p style={{ color: 'var(--text-muted)', fontSize: '0.8125rem' }}>No correlations detected for this market.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
          {correlations.map(c => (
            <div key={c.id} style={{
              background: 'var(--bg-card)', borderRadius: '0.625rem', padding: '0.875rem 1rem',
              border: '1px solid var(--border-subtle)',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div style={{ flex: 1 }}>
                  {c.news?.url ? (
                    <a href={c.news.url} target="_blank" rel="noopener noreferrer" style={{
                      color: 'var(--text-primary)', fontSize: '0.8125rem', textDecoration: 'none',
                      borderBottom: '1px solid var(--border-subtle)',
                    }}>
                      {c.news.headline}
                    </a>
                  ) : (
                    <span style={{ color: 'var(--text-primary)', fontSize: '0.8125rem' }}>{c.news?.headline}</span>
                  )}
                  {c.reasoning && (
                    <div style={{
                      marginTop: '0.375rem', fontSize: '0.75rem', color: '#a78bfa',
                      fontStyle: 'italic', borderLeft: '2px solid #7c3aed40', paddingLeft: '0.5rem',
                    }}>
                      {c.reasoning}
                    </div>
                  )}
                  <div style={{ fontSize: '0.6875rem', color: 'var(--text-muted)', marginTop: '0.375rem' }}>
                    {c.news?.source} · {new Date(c.detectedAt).toLocaleString()}
                  </div>
                </div>
                <div style={{ textAlign: 'right', marginLeft: '1rem', flexShrink: 0 }}>
                  <div style={{
                    fontSize: '0.875rem', fontWeight: 700, fontFamily: 'var(--font-mono)',
                    color: c.priceDelta > 0 ? 'var(--accent-green)' : 'var(--accent-red)',
                  }}>
                    {c.priceDelta > 0 ? '+' : ''}{(c.priceDelta * 100).toFixed(1)}%
                  </div>
                  <div style={{ fontSize: '0.6875rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                    {(c.confidence * 100).toFixed(0)}% conf
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
