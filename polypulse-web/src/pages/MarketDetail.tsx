import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer,
  ReferenceLine,
} from 'recharts';
import type { Market, PricePoint, CorrelationItem, PagedResponse } from '../types';
import { useEventStream } from '../hooks/useEventStream';

const RANGES = ['1h', '6h', '24h', '7d'] as const;

export default function MarketDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [market, setMarket] = useState<Market | null>(null);
  const [prices, setPrices] = useState<PricePoint[]>([]);
  const [correlations, setCorrelations] = useState<CorrelationItem[]>([]);
  const [range, setRange] = useState<string>('24h');
  const { priceUpdates } = useEventStream(`/api/stream/markets/${id}`);

  useEffect(() => {
    fetch(`/api/markets/${id}`)
      .then(res => {
        if (!res.ok) throw new Error('Not found');
        return res.json();
      })
      .then(setMarket)
      .catch(() => navigate('/'));
  }, [id, navigate]);

  useEffect(() => {
    fetch(`/api/markets/${id}/prices?range=${range}`)
      .then(res => res.ok ? res.json() : [])
      .then(data => setPrices(Array.isArray(data) ? data : []))
      .catch(() => setPrices([]));
  }, [id, range]);

  useEffect(() => {
    fetch(`/api/markets/${id}/correlations?size=20`)
      .then(res => res.ok ? res.json() : { content: [] })
      .then((data: PagedResponse<CorrelationItem>) => setCorrelations(data.content || []))
      .catch(() => setCorrelations([]));
  }, [id]);

  if (!market) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '50vh', color: '#9ca3af' }}>
        Loading...
      </div>
    );
  }

  const livePrice = priceUpdates.get(market.id)?.price ?? market.yesPrice ?? 0;
  const chartData = prices.map(p => ({
    time: new Date(p.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    price: Number(p.price),
  }));

  return (
    <div>
      <button
        onClick={() => navigate('/')}
        style={{ color: '#9ca3af', background: 'none', border: 'none', cursor: 'pointer', marginBottom: '1rem', fontSize: '0.875rem' }}
      >
        &larr; Back to Dashboard
      </button>

      <h1 style={{ fontSize: '1.25rem', fontWeight: 'bold', color: 'white', marginBottom: '0.5rem' }}>{market.question}</h1>

      <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem', marginBottom: '1.5rem' }}>
        <div>
          <span style={{ fontSize: '2.5rem', fontWeight: 'bold', color: 'white' }}>{Math.round(livePrice * 100)}</span>
          <span style={{ fontSize: '1.25rem', color: '#9ca3af' }}>&cent; Yes</span>
        </div>
        <div style={{ color: '#6b7280' }}>
          <span style={{ fontSize: '1.125rem' }}>{Math.round((market.noPrice ?? 0) * 100)}&cent;</span> No
        </div>
      </div>

      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem' }}>
        {RANGES.map(r => (
          <button
            key={r}
            onClick={() => setRange(r)}
            style={{
              padding: '0.25rem 0.75rem',
              borderRadius: '0.25rem',
              fontSize: '0.875rem',
              border: 'none',
              cursor: 'pointer',
              background: range === r ? '#4f46e5' : '#1f2937',
              color: range === r ? 'white' : '#9ca3af',
            }}
          >
            {r}
          </button>
        ))}
      </div>

      <div style={{ background: '#1f2937', borderRadius: '0.5rem', padding: '1rem', marginBottom: '1.5rem', height: 400 }}>
        {chartData.length === 0 ? (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', color: '#6b7280' }}>
            No price data for this range yet
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartData}>
              <XAxis dataKey="time" stroke="#6b7280" fontSize={11} tickLine={false} />
              <YAxis stroke="#6b7280" fontSize={11} tickLine={false} domain={['auto', 'auto']} tickFormatter={(v: number) => `${Math.round(v * 100)}c`} />
              <Tooltip
                contentStyle={{ backgroundColor: '#1f2937', border: '1px solid #374151', borderRadius: '0.5rem', color: '#fff' }}
                formatter={(value: number | undefined) => [`${Math.round((value ?? 0) * 100)}c`, 'Price']}
              />
              {correlations.map((c, i) => (
                <ReferenceLine
                  key={i}
                  x={new Date(c.detectedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  stroke={c.priceDelta > 0 ? '#22c55e' : '#ef4444'}
                  strokeDasharray="3 3"
                />
              ))}
              <Line type="monotone" dataKey="price" stroke="#6366f1" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>

      <h2 style={{ fontSize: '1.125rem', fontWeight: '600', color: 'white', marginBottom: '0.75rem' }}>News Correlations</h2>
      {correlations.length === 0 ? (
        <p style={{ color: '#6b7280', fontSize: '0.875rem' }}>No correlations detected yet.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
          {correlations.map(c => (
            <div key={c.id} style={{ background: '#1f2937', borderRadius: '0.5rem', padding: '1rem', border: '1px solid #374151' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <div style={{ flex: 1 }}>
                  {c.news?.url ? (
                    <a href={c.news.url} target="_blank" rel="noopener noreferrer" style={{ color: '#818cf8', fontSize: '0.875rem', textDecoration: 'none' }}>
                      {c.news.headline}
                    </a>
                  ) : (
                    <span style={{ color: '#e5e7eb', fontSize: '0.875rem' }}>{c.news?.headline}</span>
                  )}
                  <div style={{ fontSize: '0.75rem', color: '#6b7280', marginTop: '0.25rem' }}>
                    {c.news?.source} &middot; {new Date(c.detectedAt).toLocaleString()}
                  </div>
                </div>
                <div style={{ textAlign: 'right', marginLeft: '1rem' }}>
                  <div style={{ fontSize: '0.875rem', fontFamily: 'monospace', color: c.priceDelta > 0 ? '#34d399' : '#f87171' }}>
                    {c.priceDelta > 0 ? '+' : ''}{(c.priceDelta * 100).toFixed(1)}%
                  </div>
                  <div style={{ fontSize: '0.75rem', color: '#6b7280' }}>
                    conf: {(c.confidence * 100).toFixed(0)}%
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
