import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Card, Divider, List, Text, useTheme } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';

import { getHealth, type User } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { config } from '../config';

export function ProfileScreen({ user }: { user: User }) {
  const { signOut } = useAuth();
  const theme = useTheme();
  const [stack, setStack] = useState<string | null>(null);

  useEffect(() => {
    getHealth()
      .then((health) => setStack(health.stack))
      .catch(() => setStack('unavailable'));
  }, []);

  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: theme.colors.background }]}>
      <View style={styles.content}>
        <Text variant="headlineMedium" style={styles.title}>
          Profile
        </Text>

        <Card mode="outlined" style={{ borderColor: theme.colors.outlineVariant }}>
          <Row icon="account-outline" label="Name" value={user.name ?? '—'} testID="profile-name" />
          <Divider />
          <Row icon="email-outline" label="Email" value={user.email ?? '—'} testID="profile-email" />
          <Divider />
          <Row icon="identifier" label="User ID" value={user.id} testID="profile-id" />
          <Divider />
          <Row icon="server-outline" label="API" value={`${stack ?? '…'} (${config.apiBaseUrl})`} testID="profile-api" />
        </Card>

        <Button
          mode="outlined"
          icon="logout"
          textColor={theme.colors.error}
          style={{ borderColor: theme.colors.outlineVariant }}
          testID="sign-out-button"
          onPress={signOut}
        >
          Sign out
        </Button>
      </View>
    </SafeAreaView>
  );
}

function Row({ icon, label, value, testID }: { icon: string; label: string; value: string; testID: string }) {
  return (
    <List.Item
      title={label}
      titleStyle={styles.label}
      description={value}
      descriptionStyle={styles.value}
      descriptionNumberOfLines={2}
      left={(props) => <List.Icon {...props} icon={icon} />}
      testID={testID}
    />
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { flex: 1, padding: 24, gap: 20, width: '100%', maxWidth: 520, alignSelf: 'center' },
  title: { fontWeight: '700', marginTop: 12 },
  label: { fontSize: 12, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.4, opacity: 0.7 },
  value: { fontSize: 16 },
});
