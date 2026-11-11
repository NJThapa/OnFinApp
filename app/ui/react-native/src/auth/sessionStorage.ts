import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

export type Session = {
  accessToken: string;
  refreshToken: string | null;
  idToken: string | null;
  /** Epoch milliseconds at which the access token expires. */
  expiresAt: number;
};

const KEY = 'onfinapp.session';

// iOS: Keychain. Android: Keystore-encrypted storage. Web has no equivalent, so localStorage.
const isWeb = Platform.OS === 'web';

export async function loadSession(): Promise<Session | null> {
  try {
    const raw = isWeb ? globalThis.localStorage?.getItem(KEY) : await SecureStore.getItemAsync(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export async function saveSession(session: Session): Promise<void> {
  const raw = JSON.stringify(session);
  if (isWeb) {
    globalThis.localStorage?.setItem(KEY, raw);
  } else {
    await SecureStore.setItemAsync(KEY, raw);
  }
}

export async function clearSession(): Promise<void> {
  if (isWeb) {
    globalThis.localStorage?.removeItem(KEY);
  } else {
    await SecureStore.deleteItemAsync(KEY);
  }
}
