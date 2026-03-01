import { useEffect, useRef, useState, useCallback } from 'react';
import { PriceUpdate, CorrelationItem } from '../types';

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
  const eventSourceRef = useRef<EventSource | null>(null);

  const connect = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    const es = new EventSource(url);
    eventSourceRef.current = es;

    es.addEventListener('connected', () => {
      setState(prev => ({ ...prev, isConnected: true }));
    });

    es.addEventListener('price_update', (event) => {
      try {
        const update: PriceUpdate = JSON.parse(event.data);
        setState(prev => {
          const newMap = new Map(prev.priceUpdates);
          newMap.set(update.marketId, update);
          return { ...prev, priceUpdates: newMap };
        });
      } catch (e) {
        // ignore parse errors
      }
    });

    es.addEventListener('correlation', (event) => {
      try {
        const correlation: CorrelationItem = JSON.parse(event.data);
        setState(prev => ({
          ...prev,
          correlations: [correlation, ...prev.correlations].slice(0, 50),
        }));
      } catch (e) {
        // ignore parse errors
      }
    });

    es.addEventListener('heartbeat', () => {
      setState(prev => ({ ...prev, isConnected: true }));
    });

    es.onopen = () => {
      setState(prev => ({ ...prev, isConnected: true }));
    };

    es.onerror = () => {
      setState(prev => ({ ...prev, isConnected: false }));
    };
  }, [url]);

  useEffect(() => {
    connect();
    return () => {
      eventSourceRef.current?.close();
    };
  }, [connect]);

  return state;
}
