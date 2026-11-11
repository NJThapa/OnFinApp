import { StyleSheet } from 'react-native';
import { Text, useTheme } from 'react-native-paper';

/** Shown on screens that are not yet connected to the API. Remove with the sample data. */
export function SampleDataNotice() {
  const theme = useTheme();
  return (
    <Text
      variant="labelMedium"
      style={[styles.notice, { backgroundColor: theme.colors.secondaryContainer, color: theme.colors.onSecondaryContainer }]}
    >
      Sample data — not real prices, ratings or news
    </Text>
  );
}

const styles = StyleSheet.create({
  notice: { alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 8, overflow: 'hidden' },
});
