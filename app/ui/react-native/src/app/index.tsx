import { Redirect } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { ActivityIndicator, useTheme } from 'react-native-paper';

import { useAuth } from '../auth/AuthContext';
import { SignInScreen } from '../screens/SignInScreen';

/** Entry route: sign-in when signed out, the tabs when signed in. */
export default function Index() {
  const { state } = useAuth();
  const theme = useTheme();
  if (state.status === 'loading') {
    return (
      <View style={[styles.center, { backgroundColor: theme.colors.background }]}>
        <ActivityIndicator size="large" />
      </View>
    );
  }
  if (state.status === 'signedIn') return <Redirect href="/news" />;
  return <SignInScreen error={state.error} />;
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
