import { useMemo, useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { Chip, Divider, IconButton, Searchbar, Snackbar, Text, useTheme } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';

import { EmptyState } from '../components/EmptyState';
import { SampleDataNotice } from '../components/SampleDataNotice';
import { type Instrument, instruments } from '../sample/data';
import { useMarketColors } from '../theme';
import { useWatchlist, WATCHLIST_LIMIT } from '../watchlist/WatchlistContext';

const RATING_LABEL: Record<Instrument['rating'], string> = { BUY: 'Buy', HOLD: 'Hold', SELL: 'Sell' };

export function WatchlistScreen() {
  const theme = useTheme();
  const { symbols, isFollowing, follow, unfollow } = useWatchlist();
  const [query, setQuery] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  const searching = query.trim().length > 0;

  // Searching: match ticker or name. Otherwise: the followed instruments, in the order followed.
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q) return instruments.filter((i) => i.symbol.toLowerCase().includes(q) || i.name.toLowerCase().includes(q));
    return symbols.map((s) => instruments.find((i) => i.symbol === s)).filter((i): i is Instrument => !!i);
  }, [query, symbols]);

  const toggle = (instrument: Instrument) => {
    if (isFollowing(instrument.symbol)) {
      unfollow(instrument.symbol);
      setMessage(`Unfollowed ${instrument.symbol}`);
    } else if (follow(instrument.symbol)) {
      setMessage(`Following ${instrument.symbol}`);
    } else {
      setMessage(`You can follow up to ${WATCHLIST_LIMIT} instruments.`);
    }
  };

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.colors.background }]}>
      <FlatList
        data={rows}
        keyExtractor={(i) => i.symbol}
        contentContainerStyle={styles.list}
        keyboardShouldPersistTaps="handled"
        ItemSeparatorComponent={Divider}
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
            <SampleDataNotice />
            <Text variant="labelLarge" style={{ color: theme.colors.onSurfaceVariant }} testID="watchlist-count">
              {searching ? `${rows.length} ${rows.length === 1 ? 'result' : 'results'}` : `Following ${symbols.length}`}
            </Text>
          </View>
        }
        ListEmptyComponent={
          searching ? (
            <EmptyState icon="magnify-close" title="No matches" body={`Nothing found for “${query.trim()}”.`} />
          ) : (
            <EmptyState
              icon="star-plus-outline"
              title="Your watchlist is empty"
              body="Search for a stock or ETF above and tap the star to follow it."
            />
          )
        }
        renderItem={({ item }) => (
          <InstrumentRow instrument={item} following={isFollowing(item.symbol)} onToggle={() => toggle(item)} />
        )}
      />
      <Snackbar visible={message !== null} onDismiss={() => setMessage(null)} duration={2000}>
        {message ?? ''}
      </Snackbar>
    </SafeAreaView>
  );
}

function InstrumentRow({ instrument, following, onToggle }: { instrument: Instrument; following: boolean; onToggle: () => void }) {
  const theme = useTheme();
  const market = useMarketColors();
  const change = instrument.changePercent;
  const changeColor = change > 0 ? market.gain : change < 0 ? market.loss : market.flat;
  const ratingColor = instrument.rating === 'BUY' ? market.gain : instrument.rating === 'SELL' ? market.loss : market.flat;

  return (
    <View style={styles.row} testID={`instrument-${instrument.symbol}`}>
      <View style={styles.rowMain}>
        <View style={styles.rowTitle}>
          <Text variant="titleMedium">{instrument.symbol}</Text>
          {instrument.type === 'ETF' ? (
            <Text variant="labelSmall" style={[styles.badge, { color: theme.colors.onSurfaceVariant, borderColor: theme.colors.outlineVariant }]}>
              ETF
            </Text>
          ) : null}
        </View>
        <Text variant="bodySmall" numberOfLines={1} style={{ color: theme.colors.onSurfaceVariant }}>
          {instrument.name}
        </Text>
        <Chip compact mode="outlined" style={styles.rating} textStyle={{ color: ratingColor }}>
          {RATING_LABEL[instrument.rating]}
        </Chip>
      </View>

      <View style={styles.rowPrice}>
        <Text variant="titleMedium" style={styles.numeric}>
          ${instrument.price.toFixed(2)}
        </Text>
        <Text variant="bodyMedium" style={[styles.numeric, { color: changeColor }]}>
          {change > 0 ? '▲' : change < 0 ? '▼' : ''} {change > 0 ? '+' : ''}
          {change.toFixed(2)}%
        </Text>
      </View>

      <IconButton
        icon={following ? 'star' : 'star-outline'}
        iconColor={following ? theme.colors.primary : theme.colors.onSurfaceVariant}
        accessibilityLabel={following ? `Unfollow ${instrument.symbol}` : `Follow ${instrument.symbol}`}
        testID={`follow-${instrument.symbol}`}
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
  row: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 12 },
  rowMain: { flex: 1, gap: 4 },
  rowTitle: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  badge: { borderWidth: 1, borderRadius: 4, paddingHorizontal: 5, paddingVertical: 1, overflow: 'hidden' },
  rating: { alignSelf: 'flex-start', marginTop: 2 },
  rowPrice: { alignItems: 'flex-end', gap: 2 },
  numeric: { fontVariant: ['tabular-nums'] },
});
