import { StyleSheet, View } from 'react-native';
import { Button, Surface, Text, useTheme } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useAuth } from '../auth/AuthContext';

export function SignInScreen({ error }: { error?: string }) {
  const { signIn, canSignIn } = useAuth();
  const theme = useTheme();
  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: theme.colors.background }]}>
      <View style={styles.content}>
        <Text variant="displaySmall" style={styles.title}>
          OnFinApp
        </Text>
        <Text variant="bodyLarge" style={{ color: theme.colors.onSurfaceVariant }}>
          Finance news and AI analysis for the stocks you follow.
        </Text>

        {error ? (
          <Surface
            elevation={0}
            accessibilityRole="alert"
            style={[styles.error, { backgroundColor: theme.colors.errorContainer, borderRadius: theme.roundness * 4 }]}
          >
            <Text variant="bodyMedium" style={{ color: theme.colors.onErrorContainer }}>
              {error}
            </Text>
          </Surface>
        ) : null}

        <Button
          mode="contained"
          icon="login"
          testID="sign-in-button"
          disabled={!canSignIn}
          onPress={signIn}
          style={styles.button}
          contentStyle={styles.buttonContent}
        >
          Sign in
        </Button>
        {!canSignIn ? (
          <Text variant="bodySmall" style={[styles.hint, { color: theme.colors.onSurfaceVariant }]}>
            Connecting to the sign-in service…
          </Text>
        ) : null}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { flex: 1, justifyContent: 'center', padding: 24, gap: 16, width: '100%', maxWidth: 420, alignSelf: 'center' },
  title: { fontWeight: '700' },
  error: { padding: 12 },
  button: { marginTop: 8 },
  buttonContent: { paddingVertical: 6 },
  hint: { textAlign: 'center' },
});
