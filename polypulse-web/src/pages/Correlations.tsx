import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CorrelationItem, PagedResponse } from '../types';
import { useEventStream } from '../hooks/useEventStream';
import { apiUrl } from '../config/api';

export default function Correlations() {
  const navigate = useNavigate();
  const [correlations, setCorrelations] = useState<CorrelationItem[]>([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const { correlations: liveCorrelations } = useEventStream(apiUrl('/api/stream/live'));

  const fetchPage = useCallback((pageNum: number) => {
    fetch(apiUrl(`/api/correlations/recent?page=${pageNum}&size=20`))
      .then(res => res.ok ? res.json() : { content: [], last: true })
      .then((data: PagedResponse<CorrelationItem>) => {
        const items = data.content || [];
        if (pageNum === 0) setCorrelations(items);
        else setCorrelations(prev => [...prev, ...items]);
        setHasMore(!data.last);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  useEffect(() => { fetchPage(0); }, [fetchPage]);

  useEffect(() => {
    if (liveCorrelations.length > 0) {
      setCorrelations(prev => {
        const ids = new Set(prev.map(c => c.id));
        const fresh = liveCorrelations.filter(c => !ids.has(c.id));
        return [...fresh, ...prev];
      });
    }
  }, [liveCorrelations]);

  if (loading) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', paddingTop: '2rem' }}>
        {[...Array(5)].map((_, i) => (
          <div key={i} className="skeleton" style={{ height: '6rem', borderRadius: '0.75rem' }} />
        ))}
      </div>
    );
  }

  const formatTime = (iso: string) => {
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h ago`;
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
  };

  return (
    <div>
      {/* Header */}
      <div style={{ marginBottom: '1.5rem' }}>
        <h1 style={{
          fontSize: '1.25rem', fontWeight: 700, color: 'white',
          fontFamily: 'var(--font-sans)', marginBottom: '0.375rem',
        }}>
          Correlations Feed
        </h1>
        <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
          AI-detected links between breaking news and prediction market movements
        </p>
      </div>

      {correlations.length === 0 ? (
        <div style={{
          textAlign: 'center', padding: '4rem 0', color: 'var(--text-muted)',
          background: 'var(--bg-panel)', borderRadius: '0.75rem', border: '1px solid var(--border-subtle)',
        }}>
          <svg style={{ width: '2rem', height: '2rem', margin: '0 auto 0.75rem', opacity: 0.5 }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
          <div style={{ fontSize: '0.875rem' }}>No correlations detected yet</div>
          <div style={{ fontSize: '0.75rem', marginTop: '0.25rem' }}>The engine is watching for news events that move markets</div>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.625rem' }}>
          {correlations.map((c, idx) => {
            const isPositive = c.priceDelta > 0;
            return (
              <div
                key={`${c.id}-${idx}`}
                className="animate-fade-up"
                style={{
                  animationDelay: `${Math.min(idx * 40, 400)}ms`,
                  background: 'var(--bg-card)', borderRadius: '0.75rem',
                  padding: '1rem 1.125rem',
                  border: '1px solid var(--border-subtle)',
                  transition: 'border-color 0.2s',
                }}
                onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--border-medium)')}
                onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--border-subtle)')}
              >
                {/* Top: Market question + price delta */}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem' }}>
                  <div
                    style={{
                      fontSize: '0.875rem', fontWeight: 600, color: 'var(--text-primary)',
                      cursor: 'pointer', lineHeight: 1.4,
                      transition: 'color 0.15s',
                    }}
                    onClick={() => c.market && navigate(`/market/${c.market.id}`)}
                    onMouseEnter={e => (e.currentTarget.style.color = '#c4b5fd')}
                    onMouseLeave={e => (e.currentTarget.style.color = 'var(--text-primary)')}
                  >
                    {c.market?.question ?? 'Unknown market'}
                  </div>
                  <div style={{
                    flexShrink: 0, textAlign: 'right', minWidth: '5rem',
                  }}>
                    <div style={{
                      fontSize: '1rem', fontWeight: 700, fontFamily: 'var(--font-mono)',
                      color: isPositive ? 'var(--accent-green)' : 'var(--accent-red)',
                    }}>
                      {isPositive ? '+' : ''}{(c.priceDelta * 100).toFixed(1)}%
                    </div>
                    {/* Confidence bar */}
                    <div style={{
                      display: 'flex', alignItems: 'center', gap: '0.375rem', justifyContent: 'flex-end', marginTop: '0.25rem',
                    }}>
                      <div style={{
                        width: '3rem', height: '0.25rem', background: 'var(--border-subtle)',
                        borderRadius: '9999px', overflow: 'hidden',
                      }}>
                        <div style={{
                          height: '100%', borderRadius: '9999px',
                          width: `${Math.min(c.confidence * 100, 100)}%`,
                          background: c.confidence > 0.7 ? 'var(--accent-green)' : c.confidence > 0.5 ? 'var(--accent-blue)' : 'var(--text-muted)',
                        }} />
                      </div>
                      <span style={{ fontSize: '0.6875rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                        {(c.confidence * 100).toFixed(0)}%
                      </span>
                    </div>
                  </div>
                </div>

                {/* News headline */}
                <div style={{ marginTop: '0.5rem', fontSize: '0.8125rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  {c.news?.url ? (
                    <a
                      href={c.news.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{ color: 'inherit', textDecoration: 'none', borderBottom: '1px solid var(--border-subtle)' }}
                      onClick={e => e.stopPropagation()}
                    >
                      {c.news.headline}
                    </a>
                  ) : (
                    c.news?.headline ?? 'Unknown news'
                  )}
                </div>

                {/* LLM reasoning */}
                {c.reasoning && (
                  <div style={{
                    marginTop: '0.5rem', fontSize: '0.75rem', color: '#a78bfa',
                    fontStyle: 'italic', lineHeight: 1.45,
                    borderLeft: '2px solid #7c3aed40', paddingLeft: '0.625rem',
                  }}>
                    {c.reasoning}
                  </div>
                )}

                {/* Footer: source + time */}
                <div style={{
                  marginTop: '0.5rem', display: 'flex', justifyContent: 'space-between',
                  fontSize: '0.6875rem', color: 'var(--text-muted)',
                }}>
                  <span>{c.news?.source ?? ''}</span>
                  <span>{formatTime(c.detectedAt)}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {hasMore && (
        <button
          onClick={() => { const n = page + 1; setPage(n); fetchPage(n); }}
          style={{
            marginTop: '1rem', width: '100%', padding: '0.625rem',
            background: 'var(--bg-panel)', color: 'var(--text-muted)',
            border: '1px solid var(--border-subtle)', borderRadius: '0.5rem',
            cursor: 'pointer', fontSize: '0.8125rem', fontFamily: 'var(--font-sans)',
            fontWeight: 500, transition: 'all 0.15s',
          }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--border-medium)';
            (e.currentTarget as HTMLElement).style.color = 'var(--text-secondary)';
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--border-subtle)';
            (e.currentTarget as HTMLElement).style.color = 'var(--text-muted)';
          }}
        >
          Load more
        </button>
      )}
    </div>
  );
}
