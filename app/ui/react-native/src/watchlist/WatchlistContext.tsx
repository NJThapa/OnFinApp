import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import { ApiError, followInstrument, getWatchlist, unfollowInstrument, type WatchlistItem } from '../api/client';
import { useAuth } from '../auth/AuthContext';

export type FollowResult = 'ok' | 'limit' | 'error';

type WatchlistContextValue = {
  /** Followed instruments with their latest quote, in the order followed. */
  items: WatchlistItem[];
  symbols: string[];
  /** Maximum number of instruments one user can follow; null until the first load. */
  limit: number | null;
  status: 'loading' | 'ready' | 'error';
  isFollowing: (symbol: string) => boolean;
  reload: () => Promise<void>;
  follow: (symbol: string) => Promise<FollowResult>;
  unfollow: (symbol: string) => Promise<FollowResult>;
};

const WatchlistContext = createContext<WatchlistContextValue | null>(null);

/** The signed-in user's watchlist, kept on the API. */
export function WatchlistProvider({ children }: { children: ReactNode }) {
  const { state, authorizedFetch } = useAuth();
  const signedIn = state.status === 'signedIn';
  const [items, setItems] = useState<WatchlistItem[]>([]);
  const [limit, setLimit] = useState<number | null>(null);
  const [status, setStatus] = useState<WatchlistContextValue['status']>('loading');

  const reload = useCallback(async () => {
    try {
      const watchlist = await getWatchlist(authorizedFetch);
      setItems(watchlist.items);
      setLimit(watchlist.limit);
      setStatus('ready');
    } catch {
      setStatus('error');
    }
  }, [authorizedFetch]);

  useEffect(() => {
    if (signedIn) {
      void reload();
    } else {
      setItems([]);
      setStatus('loading');
    }
  }, [signedIn, reload]);

  const follow = useCallback(
    async (symbol: string): Promise<FollowResult> => {
      try {
        await followInstrument(authorizedFetch, symbol);
      } catch (e) {
        return e instanceof ApiError && e.status === 409 ? 'limit' : 'error';
      }
      await reload(); // brings the new row with its quote
      return 'ok';
    },
    [authorizedFetch, reload],
  );

  const unfollow = useCallback(
    async (symbol: string): Promise<FollowResult> => {
      try {
        await unfollowInstrument(authorizedFetch, symbol);
      } catch {
        return 'error';
      }
      setItems((current) => current.filter((item) => item.symbol !== symbol));
      return 'ok';
    },
    [authorizedFetch],
  );

  const value = useMemo<WatchlistContextValue>(() => {
    const symbols = items.map((item) => item.symbol);
    return { items, symbols, limit, status, isFollowing: (symbol) => symbols.includes(symbol), reload, follow, unfollow };
  }, [items, limit, status, reload, follow, unfollow]);
  return <WatchlistContext.Provider value={value}>{children}</WatchlistContext.Provider>;
}

export function useWatchlist(): WatchlistContextValue {
  const value = useContext(WatchlistContext);
  if (!value) throw new Error('useWatchlist must be used inside <WatchlistProvider>');
  return value;
}
