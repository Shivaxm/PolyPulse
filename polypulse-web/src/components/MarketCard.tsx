import { useNavigate } from 'react-router-dom';
import { AreaChart, Area, ResponsiveContainer } from 'recharts';
import { Market, PriceUpdate } from '../types';

interface Props {
  market: Market;
  liveUpdate?: PriceUpdate;
}

export default function MarketCard({ market, liveUpdate }: Props) {
  const navigate = useNavigate();
  const currentPrice = liveUpdate?.price ?? market.yesPrice ?? 0;
  const priceDisplay = Math.round(currentPrice * 100);

  // Simple sparkline data (just shows current price level as a flat line if no history)
  const sparkData = [
    { v: (market.yesPrice ?? 0.5) * 100 - 2 },
    { v: (market.yesPrice ?? 0.5) * 100 - 1 },
    { v: (market.yesPrice ?? 0.5) * 100 },
    { v: (market.yesPrice ?? 0.5) * 100 + 1 },
    { v: currentPrice * 100 },
  ];

  return (
    <div
      onClick={() => navigate(`/market/${market.id}`)}
      className={`bg-gray-800 rounded-lg p-4 cursor-pointer hover:bg-gray-750 transition-colors border ${
        market.hasRecentCorrelation
          ? 'border-amber-500/50'
          : 'border-gray-700'
      }`}
    >
      <h3 className="text-sm font-medium text-gray-200 line-clamp-2 mb-3 min-h-[2.5rem]">
        {market.question}
      </h3>

      <div className="flex items-end justify-between mb-2">
        <div>
          <span className="text-3xl font-bold text-white">{priceDisplay}</span>
          <span className="text-lg text-gray-400 ml-0.5">&cent;</span>
        </div>
        <div className="w-24 h-12">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={sparkData}>
              <Area
                type="monotone"
                dataKey="v"
                stroke="#6366f1"
                fill="#6366f1"
                fillOpacity={0.2}
                strokeWidth={1.5}
                dot={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="flex items-center justify-between text-xs text-gray-500">
        <span>{market.category ?? 'uncategorized'}</span>
        {market.volume24h != null && (
          <span>${(market.volume24h / 1000).toFixed(1)}k vol</span>
        )}
      </div>

      {market.hasRecentCorrelation && (
        <div className="mt-2 text-xs text-amber-400 flex items-center gap-1">
          <span>&#x1F4F0;</span> News impact detected
        </div>
      )}
    </div>
  );
}
