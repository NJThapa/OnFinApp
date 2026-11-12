import { useMemo, useState } from 'react';
import { type GestureResponderEvent, type LayoutChangeEvent, StyleSheet, View } from 'react-native';
import { Text, useTheme } from 'react-native-paper';
import Svg, { Circle, Line, Path } from 'react-native-svg';

import type { PricePoint, PriceRange } from '../api/client';
import { formatPrice } from '../format/money';

const HEIGHT = 220;
const PAD_TOP = 8;
const PAD_BOTTOM = 8;
/** Room on the right for the price labels. */
const AXIS_WIDTH = 64;

function formatTime(iso: string, range: PriceRange): string {
  const date = new Date(iso);
  if (range === '1D') return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  if (range === '1W') {
    return date.toLocaleString(undefined, { weekday: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  }
  if (range === '1M') return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

/**
 * Line chart of closing prices. Points are spaced evenly, so nights and weekends take no room.
 * Touch and drag (or move the mouse) to read the price at a point.
 */
export function PriceChart({ points, range }: { points: PricePoint[]; range: PriceRange }) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);
  const [active, setActive] = useState<number | null>(null);

  const plotWidth = Math.max(width - AXIS_WIDTH, 0);
  const plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM;

  const scale = useMemo(() => {
    const closes = points.map((p) => p.close);
    const low = Math.min(...closes);
    const high = Math.max(...closes);
    // Head room so the line never touches the edges; a flat series still gets a visible band.
    const pad = (high - low) * 0.08 || high * 0.01 || 1;
    const min = low - pad;
    const max = high + pad;
    const x = (index: number) => (points.length === 1 ? plotWidth / 2 : (index / (points.length - 1)) * plotWidth);
    const y = (close: number) => PAD_TOP + (1 - (close - min) / (max - min)) * plotHeight;
    return { low, high, x, y, ticks: [high, (high + low) / 2, low] };
  }, [points, plotWidth, plotHeight]);

  const path = useMemo(
    () => points.map((p, i) => `${i === 0 ? 'M' : 'L'}${scale.x(i).toFixed(1)} ${scale.y(p.close).toFixed(1)}`).join(' '),
    [points, scale],
  );

  const onLayout = (e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width);

  const select = (x: number) => {
    if (plotWidth <= 0 || points.length === 0) return;
    const index = Math.round((x / plotWidth) * (points.length - 1));
    setActive(Math.min(Math.max(index, 0), points.length - 1));
  };
  const onTouch = (e: GestureResponderEvent) => select(e.nativeEvent.locationX);

  const first = points[0];
  const last = points[points.length - 1];
  const shown = active !== null ? points[active] : last;

  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={`Price chart for ${range}. From ${formatPrice(first.close)} to ${formatPrice(last.close)}. Low ${formatPrice(scale.low)}, high ${formatPrice(scale.high)}.`}
    >
      {/* Readout: the touched point, or the latest one. */}
      <View style={styles.readout}>
        <Text variant="titleSmall" style={styles.numeric} testID="chart-readout-price">
          {formatPrice(shown.close)}
        </Text>
        <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }} testID="chart-readout-time">
          {formatTime(shown.time, range)}
        </Text>
      </View>

      <View style={styles.chart} onLayout={onLayout} testID="price-chart">
        {width > 0 ? (
          <>
            <Svg width={width} height={HEIGHT}>
              {scale.ticks.map((tick) => (
                <Line
                  key={tick}
                  x1={0}
                  x2={plotWidth}
                  y1={scale.y(tick)}
                  y2={scale.y(tick)}
                  stroke={theme.colors.outlineVariant}
                  strokeWidth={1}
                />
              ))}
              <Path d={path} fill="none" stroke={theme.colors.primary} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
              {active !== null ? (
                <>
                  <Line
                    x1={scale.x(active)}
                    x2={scale.x(active)}
                    y1={PAD_TOP}
                    y2={HEIGHT - PAD_BOTTOM}
                    stroke={theme.colors.outline}
                    strokeWidth={1}
                  />
                  <Circle
                    cx={scale.x(active)}
                    cy={scale.y(points[active].close)}
                    r={5}
                    fill={theme.colors.primary}
                    stroke={theme.colors.surface}
                    strokeWidth={2}
                  />
                </>
              ) : null}
            </Svg>

            {scale.ticks.map((tick) => (
              <Text
                key={tick}
                variant="labelSmall"
                style={[styles.tick, styles.numeric, { top: scale.y(tick) - 8, color: theme.colors.onSurfaceVariant }]}
              >
                {formatPrice(tick)}
              </Text>
            ))}

            {/* Sits on top of the drawing so touch positions are measured from the plot's left edge. */}
            <View
              style={[styles.touch, { width: plotWidth }]}
              onStartShouldSetResponder={() => true}
              onMoveShouldSetResponder={() => true}
              onResponderGrant={onTouch}
              onResponderMove={onTouch}
              onResponderRelease={() => setActive(null)}
              onResponderTerminate={() => setActive(null)}
              onPointerMove={(e) => select(e.nativeEvent.offsetX)}
              onPointerLeave={() => setActive(null)}
            />
          </>
        ) : null}
      </View>

      <View style={[styles.times, { marginRight: AXIS_WIDTH }]}>
        <Text variant="labelSmall" style={{ color: theme.colors.onSurfaceVariant }}>
          {formatTime(first.time, range)}
        </Text>
        <Text variant="labelSmall" style={{ color: theme.colors.onSurfaceVariant }}>
          {formatTime(last.time, range)}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  readout: { flexDirection: 'row', alignItems: 'baseline', gap: 8, marginBottom: 4 },
  chart: { height: HEIGHT },
  tick: { position: 'absolute', right: 0, width: AXIS_WIDTH - 8, textAlign: 'right' },
  touch: { position: 'absolute', left: 0, top: 0, height: HEIGHT },
  times: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 },
  numeric: { fontVariant: ['tabular-nums'] },
});
