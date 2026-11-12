import { useRouter } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useCallback, useMemo, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';
import { Button, Card, Chip, SegmentedButtons, Text, useTheme } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';

import { EmptyState } from '../components/EmptyState';
import { SampleDataNotice } from '../components/SampleDataNotice';
import { news, type NewsItem } from '../sample/data';
import { useWatchlist } from '../watchlist/WatchlistContext';

type Tab = 'market' | 'mine';

function timeAgo(iso: string): string {
  const minutes = Math.max(1, Math.round((Date.now() - new Date(iso).getTime()) / 60_000));
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function NewsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const { symbols } = useWatchlist();
  const [tab, setTab] = useState<Tab>('market');
  const [refreshing, setRefreshing] = useState(false);

  // Market: general market news. My stocks: news for followed instruments only. Newest first.
  const items = useMemo(() => {
    const selected =
      tab === 'market'
        ? news.filter((n) => n.scope === 'MARKET')
        : news.filter((n) => n.tickers.some((t) => symbols.includes(t)));
    return [...selected].sort((a, b) => b.publishedAt.localeCompare(a.publishedAt));
  }, [tab, symbols]);

  const refresh = useCallback(() => {
    setRefreshing(true);
    setTimeout(() => setRefreshing(false), 600); // sample data: nothing to fetch yet
  }, []);

  const followsNothing = tab === 'mine' && symbols.length === 0;

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.colors.background }]}>
      <FlatList
        data={items}
        keyExtractor={(n) => n.id}
        contentContainerStyle={styles.list}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={theme.colors.primary} />}
        ListHeaderComponent={
          <View style={styles.header}>
            <Text variant="headlineMedium" style={styles.title}>
              News
            </Text>
            <SegmentedButtons
              value={tab}
              onValueChange={(value) => setTab(value as Tab)}
              buttons={[
                { value: 'market', label: 'Market', testID: 'news-tab-market' },
                { value: 'mine', label: 'My stocks', testID: 'news-tab-mine' },
              ]}
            />
            <SampleDataNotice />
          </View>
        }
        ListEmptyComponent={
          followsNothing ? (
            <EmptyState
              icon="star-plus-outline"
              title="You are not following any stocks"
              body="Follow stocks or ETFs to see their news here."
              action={
                <Button mode="contained" testID="news-add-stocks" onPress={() => router.navigate('/watchlist')}>
                  Add stocks
                </Button>
              }
            />
          ) : (
            <EmptyState icon="newspaper-variant-outline" title="No news yet" body="Pull down to refresh." />
          )
        }
        renderItem={({ item }) => <NewsCard item={item} />}
      />
    </SafeAreaView>
  );
}

function NewsCard({ item }: { item: NewsItem }) {
  const theme = useTheme();
  const router = useRouter();
  return (
    <Card
      mode="outlined"
      style={{ borderColor: theme.colors.outlineVariant }}
      testID={`news-item-${item.id}`}
      accessibilityRole="link"
      onPress={() => WebBrowser.openBrowserAsync(item.url)}
    >
      <Card.Content style={styles.card}>
        <Text variant="titleMedium">{item.headline}</Text>
        <Text variant="bodyMedium" style={{ color: theme.colors.onSurfaceVariant }}>
          {item.summary}
        </Text>
        {item.tickers.length > 0 ? (
          <View style={styles.chips}>
            {item.tickers.map((ticker) => (
              <Chip
                key={ticker}
                compact
                mode="flat"
                accessibilityLabel={`Open ${ticker}`}
                onPress={() => router.push(`/stocks/${encodeURIComponent(ticker)}`)}
              >
                {ticker}
              </Chip>
            ))}
          </View>
        ) : null}
        <Text variant="labelMedium" style={{ color: theme.colors.onSurfaceVariant }}>
          {item.sourceName} · {timeAgo(item.publishedAt)}
        </Text>
      </Card.Content>
    </Card>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  list: { padding: 16, gap: 12, width: '100%', maxWidth: 640, alignSelf: 'center' },
  header: { gap: 14, marginBottom: 4 },
  title: { fontWeight: '700', marginTop: 8 },
  card: { gap: 8, paddingVertical: 14 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
