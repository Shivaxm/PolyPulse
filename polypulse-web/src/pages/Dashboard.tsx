import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { Market } from '../types';
import { useEventStream } from '../hooks/useEventStream';
import MarketCard from '../components/MarketCard';
import { apiUrl } from '../config/api';

const KNOWN_CATEGORY_COLORS: Record<string, string> = {
  politics: '#3b82f6',
  crypto: '#f59e0b',
  sports: '#10b981',
  science: '#8b5cf6',
  business: '#06b6d4',
  culture: '#ec4899',
  tech: '#6366f1',
  'pop-culture': '#ec4899',
  geopolitics: '#0ea5e9',
  finance: '#14b8a6',
  ai: '#a78bfa',
  elections: '#6366f1',
  entertainment: '#f472b6',
  world: '#38bdf8',
};

function categoryColor(name: string): string {
  const key = name.toLowerCase();
  if (KNOWN_CATEGORY_COLORS[key]) return KNOWN_CATEGORY_COLORS[key];

  // Generate a stable color for unknown categories.
  let hash = 0;
  for (const ch of key) hash = ch.charCodeAt(0) + ((hash << 5) - hash);
  return `hsl(${Math.abs(hash) % 360}, 60%, 55%)`;
}

type SortOption = 'popularity' | 'recency' | 'volume' | 'name';
const PAGE_SIZE = 24;

export default function Dashboard() {
  const [markets, setMarkets] = useState<Market[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortOption>('popularity');
  const [searchInput, setSearchInput] = useState('');
  const [searchParams, setSearchParams] = useSearchParams();
  const searchParamsRef = useRef(searchParams);
  searchParamsRef.current = searchParams;
  const { priceUpdates, isConnected } = useEventStream(apiUrl('/api/stream/live'));

  const search = searchParams.get('search') ?? '';
  const activeCategory = searchParams.get('category');
  const parsedPage = Number(searchParams.get('page') ?? '0');
  const page = Number.isFinite(parsedPage) && parsedPage >= 0 ? Math.floor(parsedPage) : 0;

  useEffect(() => {
    setSearchInput(search);
  }, [search]);

  useEffect(() => {
    const timer = setTimeout(() => {
      const normalized = searchInput.trim();
      if (normalized === search) return;

      const params = new URLSearchParams(searchParamsRef.current);
      if (normalized) {
        params.set('search', normalized);
      } else {
        params.delete('search');
      }
      params.delete('page');
      setSearchParams(params, { replace: true });
    }, 250);

    return () => clearTimeout(timer);
  }, [searchInput, search, setSearchParams]);

  useEffect(() => {
    setLoading(true);
    fetch(apiUrl('/api/markets'))
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => {
        setMarkets(Array.isArray(data) ? data : []);
        setLoading(false);
      })
      .catch(() => {
        setError('Could not connect to backend. Is the API running on port 8080?');
        setLoading(false);
      });
  }, []);

  const categories = useMemo(() => {
    const cats = new Map<string, number>();
    markets.forEach(m => {
      const cat = (m.category ?? 'uncategorized').toLowerCase();
      cats.set(cat, (cats.get(cat) ?? 0) + 1);
    });
    return Array.from(cats.entries())
      .sort((a, b) => b[1] - a[1])
      .map(([name, count]) => ({ name, count }));
  }, [markets]);

  const filteredMarkets = useMemo(() => {
    let result = markets;

    if (search) {
      const q = search.toLowerCase();
      result = result.filter(m => m.question.toLowerCase().includes(q));
    }

    if (activeCategory) {
      result = result.filter(m => (m.category ?? 'uncategorized').toLowerCase() === activeCategory);
    }

    result = [...result].sort((a, b) => {
      switch (sortBy) {
        case 'popularity': {
          const liqA = a.liquidity;
          const liqB = b.liquidity;
          if (liqA == null && liqB == null) return 0;
          if (liqA == null) return 1;
          if (liqB == null) return -1;
          return liqB - liqA;
        }
        case 'recency': {
          const dateA = a.createdAtSource ? new Date(a.createdAtSource).getTime() : 0;
          const dateB = b.createdAtSource ? new Date(b.createdAtSource).getTime() : 0;
          if (dateA === 0 && dateB === 0) return 0;
          if (dateA === 0) return 1;
          if (dateB === 0) return -1;
          return dateB - dateA;
        }
        case 'volume':
          return (b.volume24h ?? 0) - (a.volume24h ?? 0);
        case 'name':
          return a.question.localeCompare(b.question);
        default:
          return 0;
      }
    });

    return result;
  }, [markets, search, activeCategory, sortBy]);

  const totalPages = Math.max(1, Math.ceil(filteredMarkets.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages - 1);
  const paginatedMarkets = filteredMarkets.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE);

  const setCategory = (category: string | null) => {
    const params = new URLSearchParams(searchParams);
    if (category) {
      params.set('category', category);
    } else {
      params.delete('category');
    }
    params.delete('page');
    setSearchParams(params, { replace: true });
  };

  const setPageParam = (nextPage: number) => {
    const params = new URLSearchParams(searchParams);
    if (nextPage > 0) {
      params.set('page', String(nextPage));
    } else {
      params.delete('page');
    }
    setSearchParams(params, { replace: true });
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', paddingTop: '2rem' }}>
        {[...Array(6)].map((_, i) => (
          <div key={i} className="skeleton" style={{ height: '5rem', borderRadius: '0.75rem' }} />
        ))}
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '50vh', flexDirection: 'column', gap: '1rem' }}>
        <div style={{ fontSize: '1.125rem', color: 'var(--accent-red)' }}>{error}</div>
        <button onClick={() => window.location.reload()} style={{
          padding: '0.5rem 1.5rem', background: 'var(--accent-blue)', color: 'white',
          border: 'none', borderRadius: '0.5rem', cursor: 'pointer', fontWeight: 500,
          fontFamily: 'var(--font-sans)', fontSize: '0.875rem',
        }}>
          Retry
        </button>
      </div>
    );
  }

  return (
    <div>
      {/* Stats bar */}
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '0.75rem',
        marginBottom: '1.5rem',
      }}>
        {[
          { label: 'Markets Tracked', value: markets.length.toString(), color: 'var(--text-primary)' },
          { label: 'Connection', value: isConnected ? 'Live' : 'Offline', color: isConnected ? 'var(--accent-green)' : 'var(--text-muted)' },
          { label: 'Live Prices', value: priceUpdates.size.toString(), color: 'var(--text-primary)' },
        ].map((stat, i) => (
          <div key={i} style={{
            background: 'var(--bg-panel)', borderRadius: '0.75rem', padding: '1rem',
            border: '1px solid var(--border-subtle)', textAlign: 'center',
          }}>
            <div style={{
              fontSize: '1.5rem', fontWeight: 700, color: stat.color,
              fontFamily: 'var(--font-mono)', lineHeight: 1.2,
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem',
            }}>
              {stat.value}
              {stat.label === 'Connection' && isConnected && (
                <span className="animate-pulse-live" style={{
                  width: '0.5rem', height: '0.5rem', borderRadius: '50%',
                  background: 'var(--accent-green)', display: 'inline-block',
                }} />
              )}
            </div>
            <div style={{ fontSize: '0.6875rem', color: 'var(--text-muted)', marginTop: '0.25rem', textTransform: 'uppercase', letterSpacing: '0.05em', fontWeight: 500 }}>
              {stat.label}
            </div>
          </div>
        ))}
      </div>

      {/* Search + Sort row */}
      <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1rem', alignItems: 'center', flexWrap: 'wrap' }}>
        {/* Search */}
        <div style={{ flex: 1, minWidth: '14rem', position: 'relative' }}>
          <svg style={{ position: 'absolute', left: '0.75rem', top: '50%', transform: 'translateY(-50%)', width: '1rem', height: '1rem', color: 'var(--text-muted)' }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <input
            type="text"
            placeholder="Search markets..."
            value={searchInput}
            onChange={e => setSearchInput(e.target.value)}
            style={{
              width: '100%', padding: '0.5rem 0.75rem 0.5rem 2.5rem',
              background: 'var(--bg-panel)', border: '1px solid var(--border-subtle)',
              borderRadius: '0.5rem', color: 'var(--text-primary)', fontSize: '0.875rem',
              fontFamily: 'var(--font-sans)', outline: 'none',
              transition: 'border-color 0.2s',
            }}
            onFocus={e => e.target.style.borderColor = 'var(--accent-blue)'}
            onBlur={e => e.target.style.borderColor = 'var(--border-subtle)'}
          />
        </div>

        {/* Sort pills */}
        <div style={{ display: 'flex', gap: '0.25rem', flexWrap: 'wrap' }}>
          {([
            { value: 'popularity', label: 'Popular' },
            { value: 'recency', label: 'New' },
            { value: 'volume', label: 'Volume' },
            { value: 'name', label: 'A–Z' },
          ] as { value: SortOption; label: string }[]).map(opt => {
            const isActive = sortBy === opt.value;
            return (
              <button
                key={opt.value}
                onClick={() => { setSortBy(opt.value); setPageParam(0); }}
                style={{
                  padding: '0.3rem 0.625rem', borderRadius: '0.375rem', fontSize: '0.6875rem',
                  fontWeight: 500, cursor: 'pointer', transition: 'all 0.15s',
                  border: `1px solid ${isActive ? 'var(--accent-blue)' : 'var(--border-subtle)'}`,
                  background: isActive ? 'rgba(59, 130, 246, 0.12)' : 'transparent',
                  color: isActive ? 'var(--accent-blue)' : 'var(--text-muted)',
                  fontFamily: 'var(--font-sans)',
                }}
              >
                {opt.label}
              </button>
            );
          })}
        </div>

      </div>

      {/* Category pills */}
      <div style={{ display: 'flex', gap: '0.375rem', marginBottom: '1.25rem', flexWrap: 'wrap' }}>
        <button
          onClick={() => setCategory(null)}
          style={{
            padding: '0.3rem 0.75rem', borderRadius: '9999px', fontSize: '0.75rem',
            fontWeight: 500, cursor: 'pointer', transition: 'all 0.15s',
            border: activeCategory == null ? '1px solid var(--accent-blue)' : '1px solid var(--border-subtle)',
            background: activeCategory == null ? 'rgba(59, 130, 246, 0.15)' : 'var(--bg-panel)',
            color: activeCategory == null ? 'var(--accent-blue)' : 'var(--text-muted)',
            fontFamily: 'var(--font-sans)',
          }}
        >
          All ({markets.length})
        </button>
        {categories.map(cat => {
          const isActive = activeCategory === cat.name;
          const color = categoryColor(cat.name);
          return (
            <button
              key={cat.name}
              onClick={() => setCategory(isActive ? null : cat.name)}
              style={{
                padding: '0.3rem 0.75rem', borderRadius: '9999px', fontSize: '0.75rem',
                fontWeight: 500, cursor: 'pointer', transition: 'all 0.15s',
                border: `1px solid ${isActive ? color : 'var(--border-subtle)'}`,
                background: isActive ? `${color}20` : 'var(--bg-panel)',
                color: isActive ? color : 'var(--text-muted)',
                fontFamily: 'var(--font-sans)',
                textTransform: 'capitalize',
              }}
            >
              {cat.name} ({cat.count})
            </button>
          );
        })}
      </div>

      {/* Results count */}
      {(search || activeCategory) && (
        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }}>
          Showing {filteredMarkets.length === 0 ? 0 : currentPage * PAGE_SIZE + 1}-
          {Math.min((currentPage + 1) * PAGE_SIZE, filteredMarkets.length)} of {filteredMarkets.length} markets
          {search && <> matching "{search}"</>}
          {activeCategory && <> in <span style={{ textTransform: 'capitalize', color: categoryColor(activeCategory) }}>{activeCategory}</span></>}
        </div>
      )}

      {/* Market grid */}
      {paginatedMarkets.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '3rem 0', color: 'var(--text-muted)' }}>
          <div style={{ fontSize: '1rem', marginBottom: '0.5rem' }}>No markets found</div>
          <div style={{ fontSize: '0.8125rem' }}>
            {search ? 'Try a different search term' : 'No markets in this category'}
          </div>
        </div>
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: '0.75rem' }}>
            {paginatedMarkets.map((market) => (
              <MarketCard
                key={market.id}
                market={market}
                livePrice={priceUpdates.get(market.id)?.price}
                categoryColor={categoryColor((market.category ?? '').toLowerCase())}
              />
            ))}
          </div>

          {totalPages > 1 && (
            <div style={{
              display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '0.5rem',
              marginTop: '1.5rem', paddingBottom: '1rem',
            }}>
              <button
                onClick={() => setPageParam(Math.max(0, currentPage - 1))}
                disabled={currentPage === 0}
                style={{
                  padding: '0.4rem 0.75rem', borderRadius: '0.375rem', fontSize: '0.8125rem',
                  fontFamily: 'var(--font-sans)', fontWeight: 500, cursor: currentPage === 0 ? 'default' : 'pointer',
                  background: currentPage === 0 ? 'var(--bg-panel)' : 'var(--bg-card)',
                  color: currentPage === 0 ? 'var(--text-muted)' : 'var(--text-primary)',
                  border: '1px solid var(--border-subtle)',
                  opacity: currentPage === 0 ? 0.5 : 1,
                }}
              >
                ← Prev
              </button>

              <span style={{
                fontSize: '0.8125rem', color: 'var(--text-secondary)', fontFamily: 'var(--font-mono)',
                minWidth: '6rem', textAlign: 'center',
              }}>
                {currentPage + 1} / {totalPages}
              </span>

              <button
                onClick={() => setPageParam(Math.min(totalPages - 1, currentPage + 1))}
                disabled={currentPage >= totalPages - 1}
                style={{
                  padding: '0.4rem 0.75rem', borderRadius: '0.375rem', fontSize: '0.8125rem',
                  fontFamily: 'var(--font-sans)', fontWeight: 500,
                  cursor: currentPage >= totalPages - 1 ? 'default' : 'pointer',
                  background: currentPage >= totalPages - 1 ? 'var(--bg-panel)' : 'var(--bg-card)',
                  color: currentPage >= totalPages - 1 ? 'var(--text-muted)' : 'var(--text-primary)',
                  border: '1px solid var(--border-subtle)',
                  opacity: currentPage >= totalPages - 1 ? 0.5 : 1,
                }}
              >
                Next →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
