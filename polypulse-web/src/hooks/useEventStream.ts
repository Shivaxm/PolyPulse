import { useEffect, useRef, useState } from 'react';
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
  const esRef = useRef<EventSource | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;

    const es = new EventSource(url);
    esRef.current = es;

    es.addEventListener('connected', () => {
      if (mountedRef.current) setState(prev => ({ ...prev, isConnected: true }));
    });

    es.addEventListener('price_update', (event) => {
      if (!mountedRef.current) return;
      try {
        const update: PriceUpdate = JSON.parse(event.data);
        setState(prev => {
          const newMap = new Map(prev.priceUpdates);
          newMap.set(update.marketId, update);
          return { ...prev, priceUpdates: newMap };
        });
      } catch { /* ignore */ }
    });

    es.addEventListener('correlation', (event) => {
      if (!mountedRef.current) return;
      try {
        const correlation: CorrelationItem = JSON.parse(event.data);
        setState(prev => ({
          ...prev,
          correlations: [correlation, ...prev.correlations].slice(0, 50),
        }));
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

    return () => {
      mountedRef.current = false;
      es.close();
      esRef.current = null;
    };
  }, [url]);

  return state;
}
