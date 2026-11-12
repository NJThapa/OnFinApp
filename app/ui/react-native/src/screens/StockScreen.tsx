import { useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, IconButton, SegmentedButtons, Snackbar, Text, useTheme } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiError, getInstrument, getPrices, type InstrumentDetail, type PricePoint, type PriceRange } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { EmptyState } from '../components/EmptyState';
import { PriceChart } from '../components/PriceChart';
import { direction, formatChange, formatChangePercent, formatPrice } from '../format/money';
import { useMarketColors } from '../theme';
import { useWatchlist } from '../watchlist/WatchlistContext';

const RANGES: PriceRange[] = ['1D', '1W', '1M', '1Y', '5Y'];

type Loadable<T> = { status: 'loading' } | { status: 'ready'; data: T } | { status: 'error'; message: string };

export function StockScreen({ symbol }: { symbol: string }) {
  const theme = useTheme();
  const market = useMarketColors();
  const router = useRouter();
  const { authorizedFetch } = useAuth();
  const { isFollowing, follow, unfollow, limit } = useWatchlist();

  const [instrument, setInstrument] = useState<Loadable<InstrumentDetail>>({ status: 'loading' });
  const [range, setRange] = useState<PriceRange>('1M');
  const [prices, setPrices] = useState<Loadable<PricePoint[]>>({ status: 'loading' });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const loadInstrument = useCallback(async () => {
    setInstrument({ status: 'loading' });
    try {
      setInstrument({ status: 'ready', data: await getInstrument(authorizedFetch, symbol) });
    } catch (e) {
      const notFound = e instanceof ApiError && e.status === 404;
      setInstrument({ status: 'error', message: notFound ? `There is no stock or ETF with the ticker ${symbol}.` : 'Could not load this instrument.' });
    }
  }, [authorizedFetch, symbol]);

  // Only the answer to the latest request is shown, so a slow earlier range cannot overwrite a newer one.
  const latestRequest = useRef(0);
  const loadPrices = useCallback(
    async (selected: PriceRange) => {
      const request = ++latestRequest.current;
      setPrices({ status: 'loading' });
      try {
        const series = await getPrices(authorizedFetch, symbol, selected);
        if (request === latestRequest.current) setPrices({ status: 'ready', data: series.points });
      } catch {
        if (request === latestRequest.current) setPrices({ status: 'error', message: 'The price chart is unavailable right now.' });
      }
    },
    [authorizedFetch, symbol],
  );

  useEffect(() => {
    void loadInstrument();
  }, [loadInstrument]);
  useEffect(() => {
    void loadPrices(range);
  }, [loadPrices, range]);

  const back = () => (router.canGoBack() ? router.back() : router.navigate('/watchlist'));

  const header = (
    <View style={styles.bar}>
      <IconButton icon="arrow-left" accessibilityLabel="Back" testID="stock-back" onPress={back} />
    </View>
  );

  if (instrument.status !== 'ready') {
    return (
      <SafeAreaView style={[styles.screen, { backgroundColor: theme.colors.background }]}>
        {header}
        {instrument.status === 'loading' ? (
          <ActivityIndicator style={styles.loading} />
        ) : (
          <EmptyState
            icon="alert-circle-outline"
            title="Not available"
            body={instrument.message}
            action={
              <Button mode="outlined" onPress={loadInstrument}>
                Try again
              </Button>
            }
          />
        )}
      </SafeAreaView>
    );
  }

  const { data } = instrument;
  const following = isFollowing(data.symbol);
  const quote = data.quote;
  const changeColor = quote ? market[direction(quote.change)] : market.flat;

  const toggleFollow = async () => {
    setBusy(true);
    const result = following ? await unfollow(data.symbol) : await follow(data.symbol);
    setBusy(false);
    if (result === 'ok') setMessage(following ? `Unfollowed ${data.symbol}` : `Following ${data.symbol}`);
    else if (result === 'limit') setMessage(`You can follow up to ${limit ?? 50} instruments.`);
    else setMessage('Something went wrong. Please try again.');
  };

  // Change across the visible range, first point to last.
  const points = prices.status === 'ready' ? prices.data : null;
  const rangeChange = points && points.length > 1 ? points[points.length - 1].close - points[0].close : null;
  const rangePercent = rangeChange !== null && points ? (rangeChange / points[0].close) * 100 : null;

  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: theme.colors.background }]}>
      {header}
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.titleRow}>
          <View style={styles.titleText}>
            <Text variant="headlineMedium" style={styles.title} testID="stock-symbol">
              {data.symbol}
            </Text>
            <Text variant="bodyLarge" testID="stock-name">
              {data.name}
            </Text>
            <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
              {data.type === 'ETF' ? 'ETF' : 'Stock'} · {data.exchange}
            </Text>
          </View>
          <Button
            mode={following ? 'outlined' : 'contained'}
            icon={following ? 'star' : 'star-outline'}
            loading={busy}
            disabled={busy}
            testID="stock-follow"
            onPress={toggleFollow}
          >
            {following ? 'Following' : 'Follow'}
          </Button>
        </View>

        {quote ? (
          <View style={styles.quote}>
            <Text variant="displaySmall" style={styles.numeric} testID="stock-price">
              {formatPrice(quote.price)}
            </Text>
            <Text variant="titleMedium" style={[styles.numeric, { color: changeColor }]} testID="stock-change">
              {formatChange(quote.change)} ({formatChangePercent(quote.changePercent)}) today
            </Text>
          </View>
        ) : (
          <Text variant="titleMedium" style={{ color: theme.colors.onSurfaceVariant }} testID="stock-price-unavailable">
            Price unavailable
          </Text>
        )}

        <SegmentedButtons
          value={range}
          onValueChange={(value) => setRange(value as PriceRange)}
          density="small"
          buttons={RANGES.map((r) => ({ value: r, label: r, testID: `range-${r}` }))}
        />

        <View style={styles.chartArea}>
          {prices.status === 'loading' ? (
            <ActivityIndicator style={styles.loading} />
          ) : prices.status === 'error' ? (
            <EmptyState
              icon="chart-line"
              title="Chart unavailable"
              body={prices.message}
              action={
                <Button mode="outlined" onPress={() => loadPrices(range)}>
                  Try again
                </Button>
              }
            />
          ) : prices.data.length === 0 ? (
            <EmptyState icon="chart-line" title="No prices" body="There is no price history for this range." />
          ) : (
            <>
              <PriceChart points={prices.data} range={range} />
              {rangeChange !== null && rangePercent !== null ? (
                <Text variant="bodyMedium" style={[styles.numeric, { color: theme.colors.onSurfaceVariant }]} testID="stock-range-change">
                  {formatChange(rangeChange)} ({formatChangePercent(rangePercent)}) over {range}
                </Text>
              ) : null}
            </>
          )}
        </View>
      </ScrollView>
      <Snackbar visible={message !== null} onDismiss={() => setMessage(null)} duration={2000}>
        {message ?? ''}
      </Snackbar>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  bar: { flexDirection: 'row', width: '100%', maxWidth: 640, alignSelf: 'center', paddingHorizontal: 4 },
  content: { padding: 16, paddingTop: 0, gap: 18, width: '100%', maxWidth: 640, alignSelf: 'center' },
  titleRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  titleText: { flex: 1, gap: 2 },
  title: { fontWeight: '700' },
  quote: { gap: 2 },
  chartArea: { minHeight: 280, gap: 10 },
  loading: { marginTop: 96 },
  numeric: { fontVariant: ['tabular-nums'] },
});
