import { useEffect, useState } from 'react';
import type { Market } from '../types';
import { useEventStream } from '../hooks/useEventStream';
import MarketCard from '../components/MarketCard';

export default function Dashboard() {
  const [markets, setMarkets] = useState<Market[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { priceUpdates, isConnected } = useEventStream('/api/stream/live');

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
        {markets.map(market => (
          <MarketCard
            key={market.id}
            market={market}
            livePrice={priceUpdates.get(market.id)?.price}
          />
        ))}
      </div>
    </div>
  );
}
