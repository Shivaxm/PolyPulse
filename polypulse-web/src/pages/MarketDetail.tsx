import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer,
  ReferenceLine,
} from 'recharts';
import { Market, PricePoint, CorrelationItem, PagedResponse } from '../types';
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
      .then(res => res.json())
      .then(setPrices)
      .catch(() => {});
  }, [id, range]);

  useEffect(() => {
    fetch(`/api/markets/${id}/correlations?size=20`)
      .then(res => res.json())
      .then((data: PagedResponse<CorrelationItem>) => setCorrelations(data.content))
      .catch(() => {});
  }, [id]);

  // Append live ticks to chart
  useEffect(() => {
    const update = priceUpdates.get(Number(id));
    if (update) {
      setPrices(prev => [...prev, {
        timestamp: update.timestamp,
        price: update.price,
        volume: 0,
      }]);
    }
  }, [priceUpdates, id]);

  if (!market) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-gray-400">Loading...</div>
      </div>
    );
  }

  const livePrice = priceUpdates.get(market.id)?.price ?? market.yesPrice ?? 0;
  const chartData = prices.map(p => ({
    time: new Date(p.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    price: Number(p.price),
    fullTime: p.timestamp,
  }));

  // Get correlation timestamps for reference lines
  const correlationTimes = correlations.map(c => ({
    time: new Date(c.detectedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    headline: c.news?.headline ?? '',
    delta: c.priceDelta,
  }));

  return (
    <div>
      <button
        onClick={() => navigate('/')}
        className="text-gray-400 hover:text-white text-sm mb-4 inline-flex items-center gap-1"
      >
        &larr; Back to Dashboard
      </button>

      <h1 className="text-xl font-bold text-white mb-2">{market.question}</h1>

      <div className="flex items-center gap-6 mb-6">
        <div>
          <span className="text-4xl font-bold text-white">
            {Math.round(livePrice * 100)}
          </span>
          <span className="text-xl text-gray-400">&cent; Yes</span>
        </div>
        <div className="text-gray-500">
          <span className="text-lg">{Math.round((market.noPrice ?? 0) * 100)}&cent;</span> No
        </div>
      </div>

      <div className="flex gap-2 mb-4">
        {RANGES.map(r => (
          <button
            key={r}
            onClick={() => setRange(r)}
            className={`px-3 py-1 rounded text-sm ${
              range === r
                ? 'bg-indigo-600 text-white'
                : 'bg-gray-800 text-gray-400 hover:text-white'
            }`}
          >
            {r}
          </button>
        ))}
      </div>

      <div className="bg-gray-800 rounded-lg p-4 mb-6" style={{ height: 400 }}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={chartData}>
            <XAxis
              dataKey="time"
              stroke="#6b7280"
              fontSize={11}
              tickLine={false}
            />
            <YAxis
              stroke="#6b7280"
              fontSize={11}
              tickLine={false}
              domain={['auto', 'auto']}
              tickFormatter={(v: number) => `${Math.round(v * 100)}¢`}
            />
            <Tooltip
              contentStyle={{
                backgroundColor: '#1f2937',
                border: '1px solid #374151',
                borderRadius: '0.5rem',
                color: '#fff',
              }}
              formatter={(value: number) => [`${Math.round(value * 100)}¢`, 'Price']}
            />
            {correlationTimes.map((ct, i) => (
              <ReferenceLine
                key={i}
                x={ct.time}
                stroke={ct.delta > 0 ? '#22c55e' : '#ef4444'}
                strokeDasharray="3 3"
                label={{
                  value: '!',
                  position: 'top',
                  fill: ct.delta > 0 ? '#22c55e' : '#ef4444',
                }}
              />
            ))}
            <Line
              type="monotone"
              dataKey="price"
              stroke="#6366f1"
              strokeWidth={2}
              dot={false}
              animationDuration={300}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>

      <h2 className="text-lg font-semibold text-white mb-3">News Correlations</h2>
      {correlations.length === 0 ? (
        <p className="text-gray-500 text-sm">No correlations detected yet.</p>
      ) : (
        <div className="space-y-3">
          {correlations.map(c => (
            <div key={c.id} className="bg-gray-800 rounded-lg p-4 border border-gray-700">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  {c.news?.url ? (
                    <a
                      href={c.news.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-sm text-indigo-400 hover:text-indigo-300"
                    >
                      {c.news.headline}
                    </a>
                  ) : (
                    <span className="text-sm text-gray-200">{c.news?.headline}</span>
                  )}
                  <div className="text-xs text-gray-500 mt-1">
                    {c.news?.source} &middot; {new Date(c.detectedAt).toLocaleString()}
                  </div>
                </div>
                <div className="text-right ml-4">
                  <div className={`text-sm font-mono ${c.priceDelta > 0 ? 'text-green-400' : 'text-red-400'}`}>
                    {c.priceDelta > 0 ? '+' : ''}{(c.priceDelta * 100).toFixed(1)}%
                  </div>
                  <div className="text-xs text-gray-500">
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
