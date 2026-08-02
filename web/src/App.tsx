import { Suspense, lazy, useEffect } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import NavBar from '@/components/NavBar';
import { ConnectionStatus } from '@/components/ConnectionStatus';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { LoadingState } from '@/components/ui/Spinner';
import { ToastProvider } from '@/components/ui/Toast';
import { useSession } from '@/store/session';
import { useChat } from '@/store/chat';
import { gateway } from '@/api/gateway';
import { syncPendingDeviceKeys } from '@/lib/e2ee/manager';

const OnboardingScreen = lazy(() => import('@/screens/onboarding/OnboardingScreen'));
const PairingScreen = lazy(() => import('@/screens/onboarding/PairingScreen'));
const MessagesScreen = lazy(() => import('@/screens/home/MessagesScreen'));
const FriendsScreen = lazy(() => import('@/screens/friends/FriendsScreen'));
const SpacesScreen = lazy(() => import('@/screens/spaces/SpacesScreen'));
const SettingsScreen = lazy(() => import('@/screens/settings/SettingsScreen'));
const ChatScreen = lazy(() => import('@/screens/chat/ChatScreen'));
const ForumScreen = lazy(() => import('@/screens/forum/ForumScreen'));
const NestChannelsScreen = lazy(() => import('@/screens/spaces/NestChannelsScreen'));
const NestRolesScreen = lazy(() => import('@/screens/spaces/NestRolesScreen'));
const NestMembersScreen = lazy(() => import('@/screens/spaces/NestMembersScreen'));
const NestEmojiScreen = lazy(() => import('@/screens/spaces/NestEmojiScreen'));
const ProfileScreen = lazy(() => import('@/screens/profile/ProfileScreen'));
const EditProfileScreen = lazy(() => import('@/screens/settings/EditProfileScreen'));
const TrustCenterScreen = lazy(() => import('@/screens/settings/TrustCenterScreen'));
const HistoryScreen = lazy(() => import('@/screens/settings/HistoryScreen'));
const SecurityScreen = lazy(() => import('@/screens/settings/SecurityScreen'));
const KeyTransparencyScreen = lazy(() => import('@/screens/settings/KeyTransparencyScreen'));
const NetworklessScreen = lazy(() => import('@/screens/settings/NetworklessScreen'));
const BlockedScreen = lazy(() => import('@/screens/settings/BlockedScreen'));
const AppearanceScreen = lazy(() => import('@/screens/settings/AppearanceScreen'));
const AppIconScreen = lazy(() => import('@/screens/settings/AppIconScreen'));
const PrivacyScreen = lazy(() => import('@/screens/settings/PrivacyScreen'));
const NotificationSettingsScreen = lazy(() => import('@/screens/settings/NotificationSettingsScreen'));
const DesktopSettingsScreen = lazy(() => import('@/screens/settings/DesktopSettingsScreen'));
const NestSettingsScreen = lazy(() => import('@/screens/settings/NestSettingsScreen'));
const NestManageScreen = lazy(() => import('@/screens/settings/NestManageScreen'));
const TimeMachineScreen = lazy(() => import('@/screens/settings/TimeMachineScreen'));
const ChannelPermissionsScreen = lazy(() => import('@/screens/spaces/ChannelPermissionsScreen'));
const BotsScreen = lazy(() => import('@/screens/settings/BotsScreen'));
const BridgesScreen = lazy(() => import('@/screens/settings/BridgesScreen'));
const AboutScreen = lazy(() => import('@/screens/settings/AboutScreen'));
const ThreadsScreen = lazy(() => import('@/screens/threads/ThreadsScreen'));
const ThreadScreen = lazy(() => import('@/screens/threads/ThreadScreen'));
const SearchScreen = lazy(() => import('@/screens/search/SearchScreen'));
const CallScreen = lazy(() => import('@/screens/call/CallScreen'));
const NotificationsScreen = lazy(() => import('@/screens/notifications/NotificationsScreen'));

function Gate({ children }: { children: React.ReactNode }) {
  const auth = useSession((s) => s.auth);
  const location = useLocation();
  if (!auth) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

export default function App() {
  const restore = useSession((s) => s.restore);
  const restored = useSession((s) => s.restored);
  const auth = useSession((s) => s.auth);
  const user = useSession((s) => s.user);
  const syncOutbox = useChat((s) => s.syncOutbox);

  useEffect(() => {
    void restore();
  }, [restore]);

  useEffect(() => {
    if (!auth) return;
    const sync = () => void syncOutbox();
    window.addEventListener('online', sync);
    sync();
    return () => window.removeEventListener('online', sync);
  }, [syncOutbox, auth]);

  useEffect(() => {
    if (!auth || !user) return;
    const sync = () => void syncPendingDeviceKeys(user.id).catch(() => undefined);
    sync();
    const offDevice = gateway.on('device.key_registered', sync);
    const offResume = gateway.on('gateway.resume', sync);
    return () => {
      offDevice();
      offResume();
    };
  }, [auth, user]);

  if (!restored) return <LoadingState label="opening pigeonsms" />;

  return (
    <ToastProvider>
      <ConnectionStatus active={!!auth} />
      <div className="app-backdrop" aria-hidden="true" />
      <div className={auth ? 'app-frame app-frame--authed' : 'app-frame app-frame--guest'}>
        {auth ? <NavBar /> : null}
        <main className={auth ? 'app-workspace' : 'app-workspace app-workspace--guest'}>
          <ErrorBoundary>
          <Suspense fallback={<LoadingState label="loading" />}>
            <Routes>
              <Route path="/login" element={auth ? <Navigate to="/" replace /> : <OnboardingScreen />} />
              <Route path="/pair" element={<PairingScreen />} />
              <Route path="/" element={<Gate><MessagesScreen /></Gate>} />
              <Route path="/friends" element={<Gate><FriendsScreen /></Gate>} />
              <Route path="/spaces" element={<Gate><SpacesScreen /></Gate>} />
              <Route path="/you" element={<Gate><SettingsScreen /></Gate>} />
              <Route path="/chat/:channelId" element={<Gate><ChatScreen /></Gate>} />
              <Route path="/forum/:channelId" element={<Gate><ForumScreen /></Gate>} />
              <Route path="/nest/:spaceId" element={<Gate><NestChannelsScreen /></Gate>} />
              <Route path="/nest/:spaceId/roles" element={<Gate><NestRolesScreen /></Gate>} />
              <Route path="/nest/:spaceId/members" element={<Gate><NestMembersScreen /></Gate>} />
              <Route path="/nest/:spaceId/emoji" element={<Gate><NestEmojiScreen /></Gate>} />
              <Route path="/profile/:id" element={<Gate><ProfileScreen /></Gate>} />
              <Route path="/settings/editprofile" element={<Gate><EditProfileScreen /></Gate>} />
              <Route path="/settings/devices" element={<Gate><TrustCenterScreen /></Gate>} />
              <Route path="/settings/trust" element={<Gate><TrustCenterScreen /></Gate>} />
              <Route path="/settings/history" element={<Gate><HistoryScreen /></Gate>} />
              <Route path="/settings/security" element={<Gate><SecurityScreen /></Gate>} />
              <Route path="/settings/key-transparency" element={<Gate><KeyTransparencyScreen /></Gate>} />
              <Route path="/settings/key-transparency/:userId" element={<Gate><KeyTransparencyScreen /></Gate>} />
              <Route path="/settings/networkless" element={<Gate><NetworklessScreen /></Gate>} />
              <Route path="/settings/blocked" element={<Gate><BlockedScreen /></Gate>} />
              <Route path="/settings/appearance" element={<Gate><AppearanceScreen /></Gate>} />
              <Route path="/settings/appicon" element={<Gate><AppIconScreen /></Gate>} />
              <Route path="/settings/privacy" element={<Gate><PrivacyScreen /></Gate>} />
              <Route path="/settings/notifications" element={<Gate><NotificationSettingsScreen /></Gate>} />
              <Route path="/settings/desktop" element={<Gate><DesktopSettingsScreen /></Gate>} />
              <Route path="/settings/nests" element={<Gate><NestSettingsScreen /></Gate>} />
              <Route path="/settings/nests/:spaceId" element={<Gate><NestManageScreen /></Gate>} />
              <Route path="/settings/nests/:spaceId/time-machine" element={<Gate><TimeMachineScreen /></Gate>} />
              <Route path="/nest/:spaceId/channel/:channelId/permissions" element={<Gate><ChannelPermissionsScreen /></Gate>} />
              <Route path="/settings/bots" element={<Gate><BotsScreen /></Gate>} />
              <Route path="/settings/nests/:spaceId/bridges" element={<Gate><BridgesScreen /></Gate>} />
              <Route path="/settings/about" element={<Gate><AboutScreen /></Gate>} />
              <Route path="/threads/:channelId" element={<Gate><ThreadsScreen /></Gate>} />
              <Route path="/thread/:threadId" element={<Gate><ThreadScreen /></Gate>} />
              <Route path="/search" element={<Gate><SearchScreen /></Gate>} />
              <Route path="/call/:channelId" element={<Gate><CallScreen /></Gate>} />
              <Route path="/notifications" element={<Gate><NotificationsScreen /></Gate>} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
          </ErrorBoundary>
        </main>
      </div>
    </ToastProvider>
  );
}
