import { createContext, type ReactNode, useCallback, useContext, useMemo, useState } from 'react';

import { defaultWatchlist } from '../sample/data';

/** Followed-instrument limit per user (SPEC 5.1). */
export const WATCHLIST_LIMIT = 50;

type WatchlistContextValue = {
  symbols: string[];
  isFollowing: (symbol: string) => boolean;
  /** Returns false if the limit is reached. */
  follow: (symbol: string) => boolean;
  unfollow: (symbol: string) => void;
};

const WatchlistContext = createContext<WatchlistContextValue | null>(null);

// Sample-data stage: follows live in memory and reset on reload. This will be backed by the API.
export function WatchlistProvider({ children }: { children: ReactNode }) {
  const [symbols, setSymbols] = useState<string[]>(defaultWatchlist);

  const follow = useCallback(
    (symbol: string) => {
      if (symbols.includes(symbol)) return true;
      if (symbols.length >= WATCHLIST_LIMIT) return false;
      setSymbols([...symbols, symbol]);
      return true;
    },
    [symbols],
  );
  const unfollow = useCallback((symbol: string) => setSymbols((current) => current.filter((s) => s !== symbol)), []);

  const value = useMemo<WatchlistContextValue>(
    () => ({ symbols, isFollowing: (symbol) => symbols.includes(symbol), follow, unfollow }),
    [symbols, follow, unfollow],
  );
  return <WatchlistContext.Provider value={value}>{children}</WatchlistContext.Provider>;
}

export function useWatchlist(): WatchlistContextValue {
  const value = useContext(WatchlistContext);
  if (!value) throw new Error('useWatchlist must be used inside <WatchlistProvider>');
  return value;
}
