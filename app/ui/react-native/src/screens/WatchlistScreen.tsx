import { useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { FlatList, Pressable, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Chip, Divider, IconButton, Searchbar, Snackbar, Text, useTheme } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';

import { type Instrument, type Quote, type Rating, searchInstruments } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { EmptyState } from '../components/EmptyState';
import { direction, formatChangePercent, formatPrice } from '../format/money';
import { useMarketColors } from '../theme';
import { useWatchlist } from '../watchlist/WatchlistContext';

const RATING_LABEL: Record<Rating, string> = { BUY: 'Buy', HOLD: 'Hold', SELL: 'Sell' };
const SEARCH_DELAY_MS = 300;

/** A watchlist row carries a quote and rating; a search result is the instrument alone. */
type Row = Instrument & { quote?: Quote | null; rating?: Rating | null };

type Search = {
  status: 'loading' | 'ready' | 'error';
  items: Instrument[];
  nextCursor: string | null;
  loadingMore: boolean;
};

const EMPTY_SEARCH: Search = { status: 'loading', items: [], nextCursor: null, loadingMore: false };

export function WatchlistScreen() {
  const theme = useTheme();
  const router = useRouter();
  const { authorizedFetch } = useAuth();
  const watchlist = useWatchlist();
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState<Search>(EMPTY_SEARCH);
  const [refreshing, setRefreshing] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const term = query.trim();
  const searching = term.length > 0;

  // Search the API shortly after typing stops. Answers to an older query are ignored.
  const latestSearch = useRef(0);
  const runSearch = useCallback(async () => {
    const request = ++latestSearch.current;
    try {
      const page = await searchInstruments(authorizedFetch, term);
      if (request === latestSearch.current) setSearch({ status: 'ready', items: page.items, nextCursor: page.nextCursor, loadingMore: false });
    } catch {
      if (request === latestSearch.current) setSearch({ ...EMPTY_SEARCH, status: 'error' });
    }
  }, [authorizedFetch, term]);

  useEffect(() => {
    if (!searching) return;
    setSearch(EMPTY_SEARCH);
    const timer = setTimeout(runSearch, SEARCH_DELAY_MS);
    return () => {
      clearTimeout(timer);
      latestSearch.current++; // drop an answer that is still on its way
    };
  }, [searching, runSearch]);

  const loadMore = async () => {
    if (!searching || search.status !== 'ready' || !search.nextCursor || search.loadingMore) return;
    const request = latestSearch.current;
    setSearch((current) => ({ ...current, loadingMore: true }));
    try {
      const page = await searchInstruments(authorizedFetch, term, search.nextCursor);
      if (request !== latestSearch.current) return;
      setSearch((current) => ({ status: 'ready', items: [...current.items, ...page.items], nextCursor: page.nextCursor, loadingMore: false }));
    } catch {
      if (request === latestSearch.current) setSearch((current) => ({ ...current, loadingMore: false }));
    }
  };

  const refresh = async () => {
    setRefreshing(true);
    await watchlist.reload();
    setRefreshing(false);
  };

  const toggle = async (instrument: Instrument) => {
    const following = watchlist.isFollowing(instrument.symbol);
    const result = following ? await watchlist.unfollow(instrument.symbol) : await watchlist.follow(instrument.symbol);
    if (result === 'ok') setMessage(following ? `Unfollowed ${instrument.symbol}` : `Following ${instrument.symbol}`);
    else if (result === 'limit') setMessage(`You can follow up to ${watchlist.limit ?? 50} instruments.`);
    else setMessage('Something went wrong. Please try again.');
  };

  const rows: Row[] = searching ? search.items : watchlist.items;
  const status = searching ? search.status : watchlist.status;

  const count = searching
    ? status === 'ready'
      ? `${rows.length}${search.nextCursor ? '+' : ''} ${rows.length === 1 && !search.nextCursor ? 'result' : 'results'}`
      : ''
    : status === 'ready'
      ? `Following ${rows.length}${watchlist.limit ? ` of ${watchlist.limit}` : ''}`
      : '';

  const empty =
    status === 'loading' ? (
      <ActivityIndicator style={styles.loading} />
    ) : status === 'error' ? (
      <EmptyState
        icon="alert-circle-outline"
        title={searching ? 'Search is unavailable' : 'Could not load your watchlist'}
        body="Check that the API is running, then try again."
        action={
          <Button mode="outlined" onPress={searching ? runSearch : watchlist.reload}>
            Try again
          </Button>
        }
      />
    ) : searching ? (
      <EmptyState icon="magnify-close" title="No matches" body={`Nothing found for “${term}”.`} />
    ) : (
      <EmptyState
        icon="star-plus-outline"
        title="Your watchlist is empty"
        body="Search for a stock or ETF above and tap the star to follow it."
      />
    );

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.colors.background }]}>
      <FlatList
        data={status === 'ready' ? rows : []}
        keyExtractor={(row) => row.symbol}
        contentContainerStyle={styles.list}
        keyboardShouldPersistTaps="handled"
        ItemSeparatorComponent={Divider}
        refreshing={refreshing}
        onRefresh={searching ? undefined : refresh}
        onEndReached={loadMore}
        onEndReachedThreshold={0.5}
        ListHeaderComponent={
          <View style={styles.header}>
            <Text variant="headlineMedium" style={styles.title}>
              Watchlist
            </Text>
            <Searchbar
              placeholder="Search stocks and ETFs"
              value={query}
              onChangeText={setQuery}
              testID="watchlist-search"
              autoCapitalize="characters"
              autoCorrect={false}
            />
            <Text variant="labelLarge" style={{ color: theme.colors.onSurfaceVariant }} testID="watchlist-count">
              {count}
            </Text>
          </View>
        }
        ListEmptyComponent={empty}
        ListFooterComponent={search.loadingMore ? <ActivityIndicator style={styles.more} /> : undefined}
        renderItem={({ item }) => (
          <InstrumentRow
            row={item}
            following={watchlist.isFollowing(item.symbol)}
            onOpen={() => router.push(`/stocks/${encodeURIComponent(item.symbol)}`)}
            onToggle={() => toggle(item)}
          />
        )}
      />
      <Snackbar visible={message !== null} onDismiss={() => setMessage(null)} duration={2000}>
        {message ?? ''}
      </Snackbar>
    </SafeAreaView>
  );
}

function InstrumentRow({ row, following, onOpen, onToggle }: { row: Row; following: boolean; onOpen: () => void; onToggle: () => void }) {
  const theme = useTheme();
  const market = useMarketColors();
  const { quote, rating } = row;
  const ratingColor = rating === 'BUY' ? market.gain : rating === 'SELL' ? market.loss : market.flat;

  return (
    <View style={styles.row} testID={`instrument-${row.symbol}`}>
      <Pressable
        style={styles.rowOpen}
        accessibilityRole="button"
        accessibilityLabel={`Open ${row.symbol}, ${row.name}`}
        testID={`open-${row.symbol}`}
        onPress={onOpen}
      >
        <View style={styles.rowMain}>
          <View style={styles.rowTitle}>
            <Text variant="titleMedium">{row.symbol}</Text>
            {row.type === 'ETF' ? (
              <Text variant="labelSmall" style={[styles.badge, { color: theme.colors.onSurfaceVariant, borderColor: theme.colors.outlineVariant }]}>
                ETF
              </Text>
            ) : null}
          </View>
          <Text variant="bodySmall" numberOfLines={1} style={{ color: theme.colors.onSurfaceVariant }}>
            {row.name}
          </Text>
          {rating ? (
            <Chip compact mode="outlined" style={styles.rating} textStyle={{ color: ratingColor }}>
              {RATING_LABEL[rating]}
            </Chip>
          ) : null}
        </View>

        {quote ? (
          <View style={styles.rowPrice}>
            <Text variant="titleMedium" style={styles.numeric}>
              {formatPrice(quote.price)}
            </Text>
            <Text variant="bodyMedium" style={[styles.numeric, { color: market[direction(quote.change)] }]}>
              {formatChangePercent(quote.changePercent)}
            </Text>
          </View>
        ) : quote === null ? (
          <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
            Price unavailable
          </Text>
        ) : null}
      </Pressable>

      <IconButton
        icon={following ? 'star' : 'star-outline'}
        iconColor={following ? theme.colors.primary : theme.colors.onSurfaceVariant}
        accessibilityLabel={following ? `Unfollow ${row.symbol}` : `Follow ${row.symbol}`}
        testID={`follow-${row.symbol}`}
        onPress={onToggle}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  list: { padding: 16, width: '100%', maxWidth: 640, alignSelf: 'center' },
  header: { gap: 14, marginBottom: 8 },
  title: { fontWeight: '700', marginTop: 8 },
  loading: { marginTop: 56 },
  more: { marginVertical: 16 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  rowOpen: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 12 },
  rowMain: { flex: 1, gap: 4 },
  rowTitle: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  badge: { borderWidth: 1, borderRadius: 4, paddingHorizontal: 5, paddingVertical: 1, overflow: 'hidden' },
  rating: { alignSelf: 'flex-start', marginTop: 2 },
  rowPrice: { alignItems: 'flex-end', gap: 2 },
  numeric: { fontVariant: ['tabular-nums'] },
});
