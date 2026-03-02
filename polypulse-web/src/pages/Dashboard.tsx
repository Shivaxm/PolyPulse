import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AreaChart, Area, ResponsiveContainer } from 'recharts';
import type { Market } from '../types';
import { useEventStream } from '../hooks/useEventStream';

export default function Dashboard() {
  const [markets, setMarkets] = useState<Market[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { priceUpdates, isConnected } = useEventStream('/api/stream/live');
  const navigate = useNavigate();

  useEffect(() => {
    fetch('/api/markets')
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => {
        if (Array.isArray(data)) {
          setMarkets(data);
        } else {
          setMarkets([]);
        }
        setLoading(false);
      })
      .catch(err => {
        console.error('Failed to fetch markets:', err);
        setError('Could not connect to backend. Is the API running on port 8080?');
        setLoading(false);
      });
  }, []);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '50vh', color: '#9ca3af' }}>
        Loading markets...
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '50vh', color: '#ef4444', flexDirection: 'column', gap: '1rem' }}>
        <div style={{ fontSize: '1.25rem' }}>{error}</div>
        <button onClick={() => window.location.reload()} style={{ padding: '0.5rem 1rem', background: '#4f46e5', color: 'white', border: 'none', borderRadius: '0.5rem', cursor: 'pointer' }}>
          Retry
        </button>
      </div>
    );
  }

  return (
    <div>
      {/* Stats bar */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem', marginBottom: '1.5rem' }}>
        <div className="bg-gray-800 rounded-lg p-4 text-center">
          <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: 'white' }}>{markets.length}</div>
          <div style={{ fontSize: '0.75rem', color: '#6b7280' }}>Markets Tracked</div>
        </div>
        <div className="bg-gray-800 rounded-lg p-4 text-center">
          <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: isConnected ? '#34d399' : '#6b7280' }}>
            {isConnected ? 'Live' : 'Offline'}
          </div>
          <div style={{ fontSize: '0.75rem', color: '#6b7280' }}>Connection</div>
        </div>
        <div className="bg-gray-800 rounded-lg p-4 text-center">
          <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: 'white' }}>{priceUpdates.size}</div>
          <div style={{ fontSize: '0.75rem', color: '#6b7280' }}>Live Prices</div>
        </div>
      </div>

      {/* Market grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '1rem' }}>
        {markets.map(market => {
          const livePrice = priceUpdates.get(market.id)?.price ?? market.yesPrice ?? 0;
          const priceDisplay = Math.round(livePrice * 100);
          const sparkData = [
            { v: (market.yesPrice ?? 0.5) - 0.02 },
            { v: (market.yesPrice ?? 0.5) - 0.01 },
            { v: (market.yesPrice ?? 0.5) },
            { v: (market.yesPrice ?? 0.5) + 0.01 },
            { v: livePrice },
          ];

          return (
            <div
              key={market.id}
              onClick={() => navigate(`/market/${market.id}`)}
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
                <div style={{ width: '6rem', height: '3rem' }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={sparkData}>
                      <Area type="monotone" dataKey="v" stroke="#6366f1" fill="#6366f1" fillOpacity={0.2} strokeWidth={1.5} dot={false} />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
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
        })}
      </div>
    </div>
  );
}
