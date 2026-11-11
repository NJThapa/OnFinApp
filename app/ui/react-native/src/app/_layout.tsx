import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as WebBrowser from 'expo-web-browser';
import { useColorScheme } from 'react-native';
import { PaperProvider } from 'react-native-paper';

import { AuthProvider } from '../auth/AuthContext';
import { darkTheme, lightTheme } from '../theme';
import { WatchlistProvider } from '../watchlist/WatchlistContext';

// On web the identity provider redirects back into a popup; this hands the result to the opener.
WebBrowser.maybeCompleteAuthSession();

export default function RootLayout() {
  const theme = useColorScheme() === 'dark' ? darkTheme : lightTheme;
  return (
    <PaperProvider theme={theme}>
      <AuthProvider>
        <WatchlistProvider>
          <StatusBar style={theme.dark ? 'light' : 'dark'} />
          <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: theme.colors.background } }} />
        </WatchlistProvider>
      </AuthProvider>
    </PaperProvider>
  );
}
