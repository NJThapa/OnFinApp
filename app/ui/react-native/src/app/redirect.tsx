import { Redirect } from 'expo-router';

// Landing route for the sign-in redirect (onfinapp://redirect, or /redirect in the web popup).
// The auth session picks up the result; anything that lands here just goes home.
export default function AuthRedirect() {
  return <Redirect href="/" />;
}
