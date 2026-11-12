import { Redirect, useLocalSearchParams } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { ActivityIndicator, useTheme } from 'react-native-paper';

import { useAuth } from '../../auth/AuthContext';
import { StockScreen } from '../../screens/StockScreen';

/** Stock page. Waits for the saved session on a reload or deep link; redirects to the entry route without one. */
export default function StockRoute() {
  const { symbol } = useLocalSearchParams<{ symbol: string }>();
  const { state } = useAuth();
  const theme = useTheme();
  if (state.status === 'loading') {
    return (
      <View style={[styles.center, { backgroundColor: theme.colors.background }]}>
        <ActivityIndicator size="large" />
      </View>
    );
  }
  if (state.status !== 'signedIn') return <Redirect href="/" />;
  return <StockScreen symbol={symbol.toUpperCase()} />;
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
