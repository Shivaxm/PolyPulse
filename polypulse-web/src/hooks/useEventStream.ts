import { useEffect, useRef, useState, useCallback } from 'react';
import type { PriceUpdate, CorrelationItem } from '../types';

interface EventStreamState {
  priceUpdates: Map<number, PriceUpdate>;
  correlations: CorrelationItem[];
  isConnected: boolean;
}

export function useEventStream(url: string) {
  const [state, setState] = useState<EventStreamState>({
    priceUpdates: new Map(),
    correlations: [],
    isConnected: false,
  });

  const pendingPrices = useRef<Map<number, PriceUpdate>>(new Map());
  const pendingCorrelations = useRef<CorrelationItem[]>([]);
  const flushTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const mountedRef = useRef(true);

  const flush = useCallback(() => {
    if (!mountedRef.current) return;
    const hasPriceUpdates = pendingPrices.current.size > 0;
    const hasCorrelationUpdates = pendingCorrelations.current.length > 0;

    if (!hasPriceUpdates && !hasCorrelationUpdates) return;

    setState(prev => {
      let newPrices = prev.priceUpdates;
      if (hasPriceUpdates) {
        newPrices = new Map(prev.priceUpdates);
        for (const [id, update] of pendingPrices.current) {
          newPrices.set(id, update);
        }
        pendingPrices.current.clear();
      }

      let newCorrelations = prev.correlations;
      if (hasCorrelationUpdates) {
        newCorrelations = [...pendingCorrelations.current, ...prev.correlations].slice(0, 50);
        pendingCorrelations.current = [];
      }

      return { ...prev, priceUpdates: newPrices, correlations: newCorrelations };
    });
  }, []);

  useEffect(() => {
    if (!url) return;

    mountedRef.current = true;

    const es = new EventSource(url);

    es.addEventListener('connected', () => {
      if (mountedRef.current) setState(prev => ({ ...prev, isConnected: true }));
    });

    es.addEventListener('price_update', (event) => {
      if (!mountedRef.current) return;
      try {
        const update: PriceUpdate = JSON.parse(event.data);
        pendingPrices.current.set(update.marketId, update);
      } catch { /* ignore */ }
    });

    es.addEventListener('correlation', (event) => {
      if (!mountedRef.current) return;
      try {
        const correlation: CorrelationItem = JSON.parse(event.data);
        pendingCorrelations.current.push(correlation);
      } catch { /* ignore */ }
    });

    es.addEventListener('heartbeat', () => {
      if (mountedRef.current) setState(prev => ({ ...prev, isConnected: true }));
    });

    es.onopen = () => {
      if (mountedRef.current) setState(prev => ({ ...prev, isConnected: true }));
    };

    es.onerror = () => {
      if (mountedRef.current) setState(prev => ({ ...prev, isConnected: false }));
    };

    flushTimer.current = setInterval(flush, 1000);

    return () => {
      mountedRef.current = false;
      es.close();
      if (flushTimer.current) clearInterval(flushTimer.current);
      pendingPrices.current.clear();
      pendingCorrelations.current = [];
    };
  }, [url, flush]);

  return state;
}
