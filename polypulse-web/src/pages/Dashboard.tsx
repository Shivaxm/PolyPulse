import { useEffect, useState } from 'react';
import MarketCard from '../components/MarketCard';
import { Market } from '../types';
import { useEventStream } from '../hooks/useEventStream';

export default function Dashboard() {
  const [markets, setMarkets] = useState<Market[]>([]);
  const [loading, setLoading] = useState(true);
  const { priceUpdates, correlations } = useEventStream('/api/stream/live');
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    fetch('/api/markets')
      .then(res => res.json())
      .then(data => {
        setMarkets(data);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  // Show toast on new correlations
  useEffect(() => {
    if (correlations.length > 0) {
      const latest = correlations[0];
      const msg = `${latest.news?.headline ?? 'News event'} → ${latest.market?.question ?? 'Market'}`;
      setToast(msg);
      const timer = setTimeout(() => setToast(null), 5000);
      return () => clearTimeout(timer);
    }
  }, [correlations]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-gray-400">Loading markets...</div>
      </div>
    );
  }

  return (
    <div>
      <div className="grid grid-cols-3 gap-4 mb-6">
        <div className="bg-gray-800 rounded-lg p-4 text-center">
          <div className="text-2xl font-bold text-white">{markets.length}</div>
          <div className="text-xs text-gray-500">Markets Tracked</div>
        </div>
        <div className="bg-gray-800 rounded-lg p-4 text-center">
          <div className="text-2xl font-bold text-white">{correlations.length}</div>
          <div className="text-xs text-gray-500">Correlations (Session)</div>
        </div>
        <div className="bg-gray-800 rounded-lg p-4 text-center">
          <div className="text-2xl font-bold text-white">{priceUpdates.size}</div>
          <div className="text-xs text-gray-500">Live Prices</div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {markets.map(market => (
          <MarketCard
            key={market.id}
            market={market}
            liveUpdate={priceUpdates.get(market.id)}
          />
        ))}
      </div>

      {toast && (
        <div className="fixed bottom-4 right-4 bg-indigo-600 text-white px-4 py-3 rounded-lg shadow-lg max-w-sm text-sm animate-slide-in">
          <div className="font-medium mb-1">New Correlation</div>
          <div className="text-indigo-200 text-xs line-clamp-2">{toast}</div>
        </div>
      )}
    </div>
  );
}
