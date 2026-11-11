import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import { Redirect } from 'expo-router';
import { Tabs } from 'expo-router/js-tabs';
import type { ComponentProps } from 'react';
import { useTheme } from 'react-native-paper';

import { useAuth } from '../../auth/AuthContext';

type IconName = ComponentProps<typeof MaterialCommunityIcons>['name'];

/** Signed-in area. Anything here redirects to the entry route when there is no session. */
export default function TabsLayout() {
  const { state } = useAuth();
  const theme = useTheme();
  if (state.status !== 'signedIn') return <Redirect href="/" />;

  const icon =
    (name: IconName) =>
    ({ focused, size }: { focused: boolean; size: number }) => (
      <MaterialCommunityIcons
        name={name}
        size={size}
        color={focused ? theme.colors.primary : theme.colors.onSurfaceVariant}
      />
    );

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: theme.colors.primary,
        tabBarInactiveTintColor: theme.colors.onSurfaceVariant,
        tabBarStyle: { backgroundColor: theme.colors.surface, borderTopColor: theme.colors.outlineVariant },
        sceneStyle: { backgroundColor: theme.colors.background },
      }}
    >
      <Tabs.Screen name="news" options={{ title: 'News', tabBarIcon: icon('newspaper-variant-outline') }} />
      <Tabs.Screen name="watchlist" options={{ title: 'Watchlist', tabBarIcon: icon('star-outline') }} />
      <Tabs.Screen name="profile" options={{ title: 'Profile', tabBarIcon: icon('account-outline') }} />
    </Tabs>
  );
}
