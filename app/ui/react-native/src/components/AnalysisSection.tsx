import * as WebBrowser from 'expo-web-browser';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Divider, Icon, Surface, Text, useTheme } from 'react-native-paper';

import {
  type Analysis,
  type AnalysisStatus,
  ApiError,
  getAnalysis,
  getRatings,
  type RatingEntry,
  refreshAnalysis,
} from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { CONFIDENCE_LABEL, formatDate, formatDateTime, RATING_LABEL } from '../format/rating';
import { useMarketColors } from '../theme';
import { EmptyState } from './EmptyState';

const POLL_MS = 2000;
const NOTICE = 'AI-generated. It may be wrong. This is not financial advice.';

type State =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'loaded'; status: AnalysisStatus['status']; analysis: Analysis | null };

type History = { items: RatingEntry[]; nextCursor: string | null; loadingMore: boolean };

/**
 * The AI rating, analysis and rating history of one instrument.
 * While the API is generating an analysis, it asks again every two seconds until the status changes.
 */
export function AnalysisSection({
  symbol,
  onMessage,
  onRatingChanged,
}: {
  symbol: string;
  onMessage: (message: string) => void;
  /** Called when a new analysis arrives, so other screens can show the new rating. */
  onRatingChanged?: () => void;
}) {
  const theme = useTheme();
  const { authorizedFetch } = useAuth();
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [history, setHistory] = useState<History | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  // Latest callback without restarting the polling effect when the parent re-renders.
  const ratingChanged = useRef(onRatingChanged);
  ratingChanged.current = onRatingChanged;

  const loadHistory = useCallback(async () => {
    try {
      const page = await getRatings(authorizedFetch, symbol);
      setHistory({ items: page.items, nextCursor: page.nextCursor, loadingMore: false });
    } catch {
      // The history is secondary: leave what is shown.
    }
  }, [authorizedFetch, symbol]);

  const apply = useCallback(
    (next: AnalysisStatus, previous: State) => {
      setState({ kind: 'loaded', status: next.status, analysis: next.analysis });
      const before = previous.kind === 'loaded' ? previous.analysis?.generatedAt : undefined;
      if (next.analysis && next.analysis.generatedAt !== before) {
        void loadHistory();
        if (previous.kind === 'loaded') ratingChanged.current?.();
      }
    },
    [loadHistory],
  );

  const stateRef = useRef(state);
  stateRef.current = state;

  const load = useCallback(async () => {
    try {
      apply(await getAnalysis(authorizedFetch, symbol), stateRef.current);
    } catch {
      // Keep an analysis that is already on screen; a later poll or "Try again" recovers.
      if (stateRef.current.kind !== 'loaded') setState({ kind: 'error' });
    }
  }, [apply, authorizedFetch, symbol]);

  useEffect(() => {
    setState({ kind: 'loading' });
    setHistory(null);
    void load();
  }, [load]);

  const generating = state.kind === 'loaded' && state.status === 'GENERATING';
  useEffect(() => {
    if (!generating) return;
    const timer = setInterval(() => void load(), POLL_MS);
    return () => clearInterval(timer);
  }, [generating, load]);

  const refresh = async () => {
    setRefreshing(true);
    try {
      const result = await refreshAnalysis(authorizedFetch, symbol);
      apply(result, stateRef.current);
      if (result.status === 'READY') onMessage('This analysis is recent. You can refresh it once it is an hour old.');
    } catch (e) {
      onMessage(e instanceof ApiError && e.status === 429 ? e.message : 'Could not refresh the analysis. Please try again.');
    } finally {
      setRefreshing(false);
    }
  };

  const loadMoreHistory = async () => {
    if (!history?.nextCursor) return;
    setHistory({ ...history, loadingMore: true });
    try {
      const page = await getRatings(authorizedFetch, symbol, history.nextCursor);
      setHistory({ items: [...history.items, ...page.items], nextCursor: page.nextCursor, loadingMore: false });
    } catch {
      setHistory({ ...history, loadingMore: false });
      onMessage('Could not load more ratings. Please try again.');
    }
  };

  const heading = (
    <Text variant="titleLarge" style={styles.heading}>
      AI analysis
    </Text>
  );

  if (state.kind === 'loading') {
    return (
      <View style={styles.section}>
        {heading}
        <ActivityIndicator style={styles.loading} />
      </View>
    );
  }

  if (state.kind === 'error') {
    return (
      <View style={styles.section}>
        {heading}
        <EmptyState
          icon="alert-circle-outline"
          title="Analysis unavailable"
          body="Could not load the analysis."
          action={
            <Button mode="outlined" onPress={load}>
              Try again
            </Button>
          }
        />
      </View>
    );
  }

  const { status, analysis } = state;

  if (!analysis) {
    return (
      <View style={styles.section} testID="analysis-section">
        {heading}
        {status === 'GENERATING' ? (
          <View style={styles.generating} testID="analysis-generating">
            <ActivityIndicator />
            <Text variant="titleMedium">Generating analysis…</Text>
            <Text variant="bodyMedium" style={[styles.center, { color: theme.colors.onSurfaceVariant }]}>
              This usually takes a few seconds. The page updates by itself.
            </Text>
          </View>
        ) : (
          <EmptyState
            icon="alert-circle-outline"
            title="Analysis unavailable"
            body="The analysis could not be generated. Another attempt is made a few minutes after a failure."
            action={
              <Button mode="outlined" onPress={load} testID="analysis-retry">
                Check again
              </Button>
            }
          />
        )}
      </View>
    );
  }

  return (
    <View style={styles.section} testID="analysis-section">
      <View style={styles.headingRow}>
        {heading}
        <Button
          mode="text"
          icon="refresh"
          compact
          loading={refreshing}
          disabled={refreshing || status === 'GENERATING'}
          testID="analysis-refresh"
          onPress={refresh}
        >
          Refresh
        </Button>
      </View>

      {status === 'GENERATING' ? (
        <Banner icon="progress-clock" text="Generating a new analysis. The one below stays until it is ready." testID="analysis-updating" />
      ) : status === 'FAILED' ? (
        <Banner
          icon="alert-circle-outline"
          text={`The latest update failed. This is the analysis from ${formatDateTime(analysis.generatedAt)}.`}
          testID="analysis-failed"
        />
      ) : null}

      <RatingCard analysis={analysis} />

      <Surface style={styles.card} elevation={1}>
        <Text variant="bodyLarge" testID="analysis-summary">
          {analysis.summary}
        </Text>
        <Points title="Bull points" items={analysis.bullPoints} />
        <Points title="Bear points" items={analysis.bearPoints} />
        <Points title="Key risks" items={analysis.risks} />

        {analysis.keyFigures.length > 0 ? (
          <View style={styles.block}>
            <Text variant="titleSmall">Key figures</Text>
            {analysis.keyFigures.map((figure) => (
              <View key={figure.label} style={styles.figure}>
                <View style={styles.figureLabel}>
                  <Text variant="bodyMedium">{figure.label}</Text>
                  <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
                    as of {formatDate(figure.asOf)}
                  </Text>
                </View>
                <Text variant="titleMedium" style={styles.numeric}>
                  {figure.value}
                </Text>
              </View>
            ))}
          </View>
        ) : null}

        <View style={styles.block}>
          <Text variant="titleSmall">Sources</Text>
          {analysis.sources.map((source) => (
            <Pressable
              key={source.url}
              accessibilityRole="link"
              accessibilityLabel={`Open source: ${source.title}`}
              style={styles.source}
              onPress={() => void WebBrowser.openBrowserAsync(source.url)}
            >
              <Icon source="open-in-new" size={16} color={theme.colors.primary} />
              <Text variant="bodyMedium" style={[styles.sourceText, { color: theme.colors.primary }]}>
                {source.title}
              </Text>
            </Pressable>
          ))}
        </View>

        <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
          {NOTICE}
        </Text>
      </Surface>

      {history && history.items.length > 0 ? (
        <View style={styles.block} testID="rating-history">
          <Text variant="titleMedium">Rating history</Text>
          {history.items.map((entry, index) => (
            <View key={`${entry.createdAt}-${index}`}>
              {index > 0 ? <Divider style={styles.divider} /> : null}
              <View style={styles.historyRow}>
                <RatingBadge rating={entry.rating} small />
                <View style={styles.historyText}>
                  <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
                    {formatDateTime(entry.createdAt)} · {CONFIDENCE_LABEL[entry.confidence]} confidence
                  </Text>
                  <Text variant="bodyMedium">{entry.rationale}</Text>
                </View>
              </View>
            </View>
          ))}
          {history.nextCursor ? (
            <Button mode="text" loading={history.loadingMore} disabled={history.loadingMore} onPress={loadMoreHistory}>
              Show older ratings
            </Button>
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

function RatingCard({ analysis }: { analysis: Analysis }) {
  const theme = useTheme();
  return (
    <Surface style={styles.card} elevation={1} testID="rating-card">
      <View style={styles.ratingRow}>
        <RatingBadge rating={analysis.rating} />
        <View style={styles.ratingMeta}>
          <Text variant="titleMedium" testID="rating-confidence">
            {CONFIDENCE_LABEL[analysis.confidence]} confidence
          </Text>
          <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }} testID="rating-generated">
            Generated {formatDateTime(analysis.generatedAt)}
          </Text>
        </View>
      </View>
      <Text variant="bodyLarge" testID="rating-rationale">
        {analysis.rationale}
      </Text>
      <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
        Based on {analysis.sources.length} {analysis.sources.length === 1 ? 'source' : 'sources'}, listed below. {NOTICE}
      </Text>
    </Surface>
  );
}

function RatingBadge({ rating, small }: { rating: Analysis['rating']; small?: boolean }) {
  const market = useMarketColors();
  const color = rating === 'BUY' ? market.gain : rating === 'SELL' ? market.loss : market.flat;
  return (
    <View style={[styles.badge, small && styles.badgeSmall, { borderColor: color }]}>
      <Text variant={small ? 'labelLarge' : 'headlineSmall'} style={[styles.badgeText, { color }]} testID={small ? undefined : 'rating-value'}>
        {RATING_LABEL[rating]}
      </Text>
    </View>
  );
}

function Points({ title, items }: { title: string; items: string[] }) {
  return (
    <View style={styles.block}>
      <Text variant="titleSmall">{title}</Text>
      {items.map((item) => (
        <View key={item} style={styles.point}>
          <Text variant="bodyMedium">•</Text>
          <Text variant="bodyMedium" style={styles.pointText}>
            {item}
          </Text>
        </View>
      ))}
    </View>
  );
}

function Banner({ icon, text, testID }: { icon: string; text: string; testID: string }) {
  const theme = useTheme();
  return (
    <View style={[styles.banner, { backgroundColor: theme.colors.secondaryContainer }]} testID={testID}>
      <Icon source={icon} size={18} color={theme.colors.onSecondaryContainer} />
      <Text variant="bodyMedium" style={[styles.bannerText, { color: theme.colors.onSecondaryContainer }]}>
        {text}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { gap: 14 },
  heading: { fontWeight: '700' },
  headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  loading: { marginVertical: 40 },
  generating: { alignItems: 'center', gap: 10, paddingVertical: 40, paddingHorizontal: 24 },
  center: { textAlign: 'center' },
  card: { borderRadius: 12, padding: 16, gap: 14 },
  ratingRow: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  ratingMeta: { flex: 1, gap: 2 },
  badge: { borderWidth: 2, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 6, minWidth: 88, alignItems: 'center' },
  badgeSmall: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 3, minWidth: 52 },
  badgeText: { fontWeight: '700' },
  block: { gap: 8 },
  point: { flexDirection: 'row', gap: 8 },
  pointText: { flex: 1 },
  figure: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  figureLabel: { flex: 1 },
  numeric: { fontVariant: ['tabular-nums'] },
  source: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 4 },
  sourceText: { flex: 1 },
  divider: { marginVertical: 8 },
  historyRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  historyText: { flex: 1, gap: 2 },
  banner: { flexDirection: 'row', alignItems: 'center', gap: 10, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10 },
  bannerText: { flex: 1 },
});
