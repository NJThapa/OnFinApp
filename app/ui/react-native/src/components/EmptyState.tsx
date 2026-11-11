import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { Icon, Text, useTheme } from 'react-native-paper';

export function EmptyState({ icon, title, body, action }: { icon: string; title: string; body: string; action?: ReactNode }) {
  const theme = useTheme();
  return (
    <View style={styles.container}>
      <Icon source={icon} size={44} color={theme.colors.onSurfaceVariant} />
      <Text variant="titleMedium" style={styles.center}>
        {title}
      </Text>
      <Text variant="bodyMedium" style={[styles.center, { color: theme.colors.onSurfaceVariant }]}>
        {body}
      </Text>
      {action}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { alignItems: 'center', gap: 10, paddingVertical: 56, paddingHorizontal: 24 },
  center: { textAlign: 'center' },
});
