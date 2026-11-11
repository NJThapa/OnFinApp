import * as AuthSession from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Platform } from 'react-native';

import { ApiError, type AuthorizedFetch, getMe, type User } from '../api/client';
import { config } from '../config';
import { clearSession, loadSession, saveSession, type Session } from './sessionStorage';

export type AuthState =
  | { status: 'loading' }
  | { status: 'signedOut'; error?: string }
  | { status: 'signedIn'; user: User };

type AuthContextValue = {
  state: AuthState;
  /** False until the sign-in service has been reached and the request is prepared. */
  canSignIn: boolean;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  authorizedFetch: AuthorizedFetch;
};

const AuthContext = createContext<AuthContextValue | null>(null);

const SCOPES = ['openid', 'profile', 'email'];
/** Refresh this long before the access token actually expires. */
const EXPIRY_MARGIN_MS = 30_000;

const redirectUri = AuthSession.makeRedirectUri({ scheme: 'onfinapp', path: 'redirect' });

class SignedOutError extends Error {}

function toSession(tokens: AuthSession.TokenResponse, previous?: Session): Session {
  return {
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken ?? previous?.refreshToken ?? null,
    idToken: tokens.idToken ?? previous?.idToken ?? null,
    expiresAt: (tokens.issuedAt + (tokens.expiresIn ?? 60)) * 1000,
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });
  const sessionRef = useRef<Session | null>(null);
  const refreshing = useRef<Promise<Session> | null>(null);

  // Prepared ahead of the button press so the browser opens directly from the user's tap.
  const discovery = AuthSession.useAutoDiscovery(config.oidcIssuer);
  const [request, , promptAsync] = AuthSession.useAuthRequest(
    { clientId: config.oidcClientId, redirectUri, scopes: SCOPES, usePKCE: true },
    discovery,
  );

  const getDiscovery = useCallback(
    async () => discovery ?? (await AuthSession.fetchDiscoveryAsync(config.oidcIssuer)),
    [discovery],
  );

  const dropSession = useCallback(async (error?: string) => {
    sessionRef.current = null;
    await clearSession();
    setState({ status: 'signedOut', error });
  }, []);

  const refresh = useCallback(async (): Promise<Session> => {
    const current = sessionRef.current;
    if (!current?.refreshToken) throw new SignedOutError();
    // One refresh at a time: concurrent callers share the same request.
    refreshing.current ??= (async () => {
      try {
        const tokens = await AuthSession.refreshAsync(
          { clientId: config.oidcClientId, refreshToken: current.refreshToken! },
          await getDiscovery(),
        );
        const next = toSession(tokens, current);
        sessionRef.current = next;
        await saveSession(next);
        return next;
      } catch {
        throw new SignedOutError();
      } finally {
        refreshing.current = null;
      }
    })();
    return refreshing.current;
  }, [getDiscovery]);

  const authorizedFetch = useCallback<AuthorizedFetch>(
    async (path, init) => {
      const send = (token: string) =>
        fetch(config.apiBaseUrl + path, {
          ...init,
          headers: { ...init?.headers, Authorization: `Bearer ${token}` },
        });
      try {
        let session = sessionRef.current;
        if (!session) throw new SignedOutError();
        if (session.expiresAt - EXPIRY_MARGIN_MS < Date.now()) session = await refresh();

        let res = await send(session.accessToken);
        if (res.status === 401) {
          // Refresh once and retry; a second 401 means the session is gone.
          res = await send((await refresh()).accessToken);
          if (res.status === 401) throw new SignedOutError();
        }
        return res;
      } catch (e) {
        if (e instanceof SignedOutError) await dropSession('Your session has expired. Please sign in again.');
        throw e;
      }
    },
    [refresh, dropSession],
  );

  const loadUser = useCallback(async () => {
    try {
      setState({ status: 'signedIn', user: await getMe(authorizedFetch) });
    } catch (e) {
      if (e instanceof SignedOutError) return; // already handled by authorizedFetch
      const reason = e instanceof ApiError ? e.message : `Could not reach the API at ${config.apiBaseUrl}.`;
      setState({ status: 'signedOut', error: reason });
    }
  }, [authorizedFetch]);

  // On start: restore a saved session, if any.
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    (async () => {
      sessionRef.current = await loadSession();
      if (sessionRef.current) await loadUser();
      else setState({ status: 'signedOut' });
    })();
  }, [loadUser]);

  const signIn = useCallback(async () => {
    if (!request || !discovery) return;
    const result = await promptAsync();
    if (result.type === 'cancel' || result.type === 'dismiss') return;
    if (result.type !== 'success') {
      const detail = result.type === 'error' ? result.error?.message : undefined;
      setState({ status: 'signedOut', error: detail ?? 'Sign-in failed.' });
      return;
    }
    setState({ status: 'loading' });
    try {
      const tokens = await AuthSession.exchangeCodeAsync(
        {
          clientId: config.oidcClientId,
          code: result.params.code,
          redirectUri,
          extraParams: { code_verifier: request.codeVerifier ?? '' },
        },
        discovery,
      );
      const session = toSession(tokens);
      sessionRef.current = session;
      await saveSession(session);
    } catch {
      setState({ status: 'signedOut', error: 'Sign-in failed while exchanging the code.' });
      return;
    }
    await loadUser();
  }, [request, discovery, promptAsync, loadUser]);

  const signOut = useCallback(async () => {
    const idToken = sessionRef.current?.idToken;
    await dropSession();

    // Also end the session at the identity provider, so the next sign-in asks for credentials.
    const endSessionEndpoint = (await getDiscovery().catch(() => null))?.endSessionEndpoint;
    if (!endSessionEndpoint || !idToken) return;
    const returnTo = Platform.OS === 'web' ? globalThis.location.origin + '/' : redirectUri;
    const url =
      `${endSessionEndpoint}?id_token_hint=${encodeURIComponent(idToken)}` +
      `&post_logout_redirect_uri=${encodeURIComponent(returnTo)}`;
    if (Platform.OS === 'web') {
      globalThis.location.assign(url);
    } else {
      await WebBrowser.openAuthSessionAsync(url, redirectUri).catch(() => undefined);
    }
  }, [dropSession, getDiscovery]);

  const value = useMemo<AuthContextValue>(
    () => ({ state, canSignIn: !!request && !!discovery, signIn, signOut, authorizedFetch }),
    [state, request, discovery, signIn, signOut, authorizedFetch],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
