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
      .then(res => res.ok ? res.json() : { content: [], last: true })
      .then((data: PagedResponse<CorrelationItem>) => {
        const items = data.content || [];
        if (pageNum === 0) {
          setCorrelations(items);
        } else {
          setCorrelations(prev => [...prev, ...items]);
        }
        setHasMore(!data.last);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchPage(0);
  }, [fetchPage]);

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
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '50vh', color: '#9ca3af' }}>
        Loading correlations...
      </div>
    );
  }

  return (
    <div>
      <h1 style={{ fontSize: '1.25rem', fontWeight: 'bold', color: 'white', marginBottom: '1rem' }}>Correlations Feed</h1>

      {correlations.length === 0 ? (
        <p style={{ color: '#6b7280' }}>No correlations detected yet. The engine is watching for news events that move markets.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
          {correlations.map((c, idx) => (
            <div key={`${c.id}-${idx}`} style={{ background: '#1f2937', borderRadius: '0.5rem', padding: '1rem', border: '1px solid #374151' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <div style={{ flex: 1 }}>
                  <div
                    style={{ fontSize: '0.875rem', color: '#818cf8', cursor: 'pointer', marginBottom: '0.25rem' }}
                    onClick={() => c.market && navigate(`/market/${c.market.id}`)}
                  >
                    {c.market?.question ?? 'Unknown market'}
                  </div>
                  <div style={{ fontSize: '0.875rem', color: '#d1d5db' }}>
                    {c.news?.headline ?? 'Unknown news event'}
                  </div>
                  <div style={{ fontSize: '0.75rem', color: '#6b7280', marginTop: '0.25rem' }}>
                    {c.news?.source ?? ''} &middot; {new Date(c.detectedAt).toLocaleString()}
                  </div>
                </div>
                <div style={{ textAlign: 'right', marginLeft: '1rem', flexShrink: 0 }}>
                  <div style={{ fontSize: '0.875rem', fontFamily: 'monospace', fontWeight: 'bold', color: c.priceDelta > 0 ? '#34d399' : '#f87171' }}>
                    {c.priceDelta > 0 ? '+' : ''}{(c.priceDelta * 100).toFixed(1)}%
                  </div>
                  <div style={{ width: '4rem', background: '#374151', borderRadius: '9999px', height: '0.375rem', marginTop: '0.25rem' }}>
                    <div style={{ background: '#6366f1', height: '0.375rem', borderRadius: '9999px', width: `${Math.min(c.confidence * 100, 100)}%` }} />
                  </div>
                  <div style={{ fontSize: '0.75rem', color: '#6b7280', marginTop: '0.125rem' }}>
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
          onClick={() => { const next = page + 1; setPage(next); fetchPage(next); }}
          style={{ marginTop: '1rem', width: '100%', padding: '0.5rem', background: '#1f2937', color: '#9ca3af', border: '1px solid #374151', borderRadius: '0.5rem', cursor: 'pointer', fontSize: '0.875rem' }}
        >
          Load more
        </button>
      )}
    </div>
  );
}
