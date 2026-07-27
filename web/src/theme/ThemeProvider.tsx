import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useThemeStore, type ThemeMode } from '@/store/theme';
import { accentByKey, NOVA_ACCENT } from '@/theme/accents';
import { wallpaperCss } from '@/theme/wallpapers';

function usePrefersDark(): boolean {
  const [dark, setDark] = useState(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return true;
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  });
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (e: MediaQueryListEvent): void => setDark(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);
  return dark;
}

function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return false;
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  });
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = (e: MediaQueryListEvent): void => setReduced(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);
  return reduced;
}

function resolveTheme(mode: ThemeMode, systemDark: boolean): 'dark' | 'oled' | 'light' {
  if (mode === 'system') return systemDark ? 'dark' : 'light';
  return mode;
}

export function ThemeProvider({ children }: { children: ReactNode }): ReactNode {
  const mode = useThemeStore((s) => s.mode);
  const accentKey = useThemeStore((s) => s.accent);
  const wallpaper = useThemeStore((s) => s.wallpaper);
  const wallpaperDim = useThemeStore((s) => s.wallpaperDim);
  const reducedMotion = useThemeStore((s) => s.reducedMotion);
  const liquidGlass = useThemeStore((s) => s.liquidGlass);
  const uiSkin = useThemeStore((s) => s.uiSkin);

  const systemDark = usePrefersDark();
  const systemReduced = usePrefersReducedMotion();
  const theme = resolveTheme(mode, systemDark);

  const accent = useMemo(() => {
    const experimental = uiSkin !== 'classic';
    if (experimental && accentKey === 'peach') return NOVA_ACCENT;
    return accentByKey(accentKey);
  }, [accentKey, uiSkin]);

  const wallpaperValue = useMemo(() => wallpaperCss(wallpaper), [wallpaper]);

  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-skin', uiSkin);
    root.setAttribute('data-glass', liquidGlass ? 'true' : 'false');
    root.setAttribute('data-reduced-motion', reducedMotion || systemReduced ? 'true' : 'false');
    root.style.setProperty('--accent', accent.bright);
    root.style.setProperty('--accent-deep', accent.deep);
    root.style.setProperty('--on-accent', accent.on);
    root.style.setProperty('--wallpaper', wallpaperValue);
    root.style.setProperty('--wallpaper-dim', String(wallpaperDim));
  }, [
    theme,
    uiSkin,
    liquidGlass,
    reducedMotion,
    systemReduced,
    accent,
    wallpaperValue,
    wallpaperDim,
  ]);

  useEffect(() => {
    const meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) return;
    const color = theme === 'light' ? '#FAF7F4' : theme === 'oled' ? '#000000' : '#16131A';
    meta.setAttribute('content', color);
  }, [theme]);

  return children;
}
