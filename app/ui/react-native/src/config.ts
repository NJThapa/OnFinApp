/** Build-time configuration. Override with EXPO_PUBLIC_* variables (see .env.example). */
export type AppConfig = {
  apiBaseUrl: string;
  oidcIssuer: string;
  oidcClientId: string;
};

export const config: AppConfig = {
  apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8080',
  oidcIssuer: process.env.EXPO_PUBLIC_OIDC_ISSUER ?? 'http://localhost:8082/realms/onfinapp',
  oidcClientId: process.env.EXPO_PUBLIC_OIDC_CLIENT_ID ?? 'onfinapp-app',
};
