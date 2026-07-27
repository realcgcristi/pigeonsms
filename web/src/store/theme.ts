import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemeMode = 'system' | 'dark' | 'oled' | 'light';
export type UiSkin = 'classic' | 'nova' | 'galaxy';

export interface ThemeState {
  mode: ThemeMode;
  accent: string;
  wallpaper: string | null;
  wallpaperDim: number;
  reducedMotion: boolean;
  liquidGlass: boolean;
  dynamicColor: boolean;
  uiSkin: UiSkin;
  setMode: (mode: ThemeMode) => void;
  setAccent: (accent: string) => void;
  setWallpaper: (wallpaper: string | null) => void;
  setWallpaperDim: (dim: number) => void;
  setReducedMotion: (value: boolean) => void;
  setLiquidGlass: (value: boolean) => void;
  setDynamicColor: (value: boolean) => void;
  setUiSkin: (skin: UiSkin) => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set) => ({
      mode: 'dark',
      accent: 'peach',
      wallpaper: null,
      wallpaperDim: 0.3,
      reducedMotion: false,
      liquidGlass: false,
      dynamicColor: false,
      uiSkin: 'classic',
      setMode: (mode) => set({ mode }),
      setAccent: (accent) => set({ accent }),
      setWallpaper: (wallpaper) => set({ wallpaper }),
      setWallpaperDim: (wallpaperDim) => set({ wallpaperDim }),
      setReducedMotion: (reducedMotion) => set({ reducedMotion }),
      setLiquidGlass: (liquidGlass) => set({ liquidGlass }),
      setDynamicColor: (dynamicColor) => set({ dynamicColor }),
      setUiSkin: (uiSkin) => set({ uiSkin }),
    }),
    {
      name: 'pigeon.theme',
      version: 1,
      partialize: (s) => ({
        mode: s.mode,
        accent: s.accent,
        wallpaper: s.wallpaper,
        wallpaperDim: s.wallpaperDim,
        reducedMotion: s.reducedMotion,
        liquidGlass: s.liquidGlass,
        dynamicColor: s.dynamicColor,
        uiSkin: s.uiSkin,
      }),
    },
  ),
);
