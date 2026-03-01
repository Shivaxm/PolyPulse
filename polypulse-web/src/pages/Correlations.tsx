import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { CorrelationItem, PagedResponse } from '../types';
import { useEventStream } from '../hooks/useEventStream';

export default function Correlations() {
  const navigate = useNavigate();
  const [correlations, setCorrelations] = useState<CorrelationItem[]>([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const { correlations: liveCorrelations } = useEventStream('/api/stream/live');

  const fetchPage = useCallback((pageNum: number) => {
    fetch(`/api/correlations/recent?page=${pageNum}&size=20`)
      .then(res => res.json())
      .then((data: PagedResponse<CorrelationItem>) => {
        if (pageNum === 0) {
          setCorrelations(data.content);
        } else {
          setCorrelations(prev => [...prev, ...data.content]);
        }
        setHasMore(!data.last);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchPage(0);
  }, [fetchPage]);

  // Prepend live correlations
  useEffect(() => {
    if (liveCorrelations.length > 0) {
      setCorrelations(prev => {
        const existingIds = new Set(prev.map(c => c.id));
        const newOnes = liveCorrelations.filter(c => !existingIds.has(c.id));
        return [...newOnes, ...prev];
      });
    }
  }, [liveCorrelations]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-gray-400">Loading correlations...</div>
      </div>
    );
  }

  return (
    <div>
      <h1 className="text-xl font-bold text-white mb-4">Correlations Feed</h1>

      {correlations.length === 0 ? (
        <p className="text-gray-500">No correlations detected yet. The engine is watching for news events that move markets.</p>
      ) : (
        <div className="space-y-3">
          {correlations.map((c, idx) => (
            <div
              key={`${c.id}-${idx}`}
              className="bg-gray-800 rounded-lg p-4 border border-gray-700 animate-fade-in"
            >
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div
                    className="text-sm text-indigo-400 hover:text-indigo-300 cursor-pointer mb-1"
                    onClick={() => c.market && navigate(`/market/${c.market.id}`)}
                  >
                    {c.market?.question ?? 'Unknown market'}
                  </div>
                  <div className="text-sm text-gray-300">
                    {c.news?.headline ?? 'Unknown news event'}
                  </div>
                  <div className="text-xs text-gray-500 mt-1">
                    {c.news?.source ?? ''} &middot; {new Date(c.detectedAt).toLocaleString()}
                  </div>
                </div>
                <div className="text-right ml-4 shrink-0">
                  <div className={`text-sm font-mono font-bold ${c.priceDelta > 0 ? 'text-green-400' : 'text-red-400'}`}>
                    {c.priceDelta > 0 ? '+' : ''}{(c.priceDelta * 100).toFixed(1)}%
                  </div>
                  <div className="w-16 bg-gray-700 rounded-full h-1.5 mt-1">
                    <div
                      className="bg-indigo-500 h-1.5 rounded-full"
                      style={{ width: `${Math.min(c.confidence * 100, 100)}%` }}
                    />
                  </div>
                  <div className="text-xs text-gray-500 mt-0.5">
                    {(c.confidence * 100).toFixed(0)}% conf
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {hasMore && (
        <button
          onClick={() => {
            const nextPage = page + 1;
            setPage(nextPage);
            fetchPage(nextPage);
          }}
          className="mt-4 w-full py-2 bg-gray-800 text-gray-400 rounded-lg hover:text-white hover:bg-gray-750 transition-colors text-sm"
        >
          Load more
        </button>
      )}
    </div>
  );
}
