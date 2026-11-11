import { useAuth } from '../../auth/AuthContext';
import { ProfileScreen } from '../../screens/ProfileScreen';

export default function ProfileRoute() {
  const { state } = useAuth();
  return state.status === 'signedIn' ? <ProfileScreen user={state.user} /> : null;
}
