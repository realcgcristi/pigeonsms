export interface Wallpaper {
  key: string;
  label: string;
  stops: string[];
}

export const PIGEON_WALLPAPERS: Wallpaper[] = [
  { key: 'none', label: 'none', stops: [] },
  { key: 'aurora', label: 'aurora', stops: ['#0E2A2A', '#13314F', '#2A1E4A'] },
  { key: 'dusk', label: 'dusk', stops: ['#2A1330', '#3A1B36', '#4A2417'] },
  { key: 'ocean', label: 'ocean', stops: ['#07223A', '#0B3350', '#0E2A44'] },
  { key: 'ember', label: 'ember', stops: ['#2A0E12', '#3A1520', '#241026'] },
  { key: 'forest', label: 'forest', stops: ['#0C2417', '#103021', '#0E2A2A'] },
  { key: 'plum', label: 'plum', stops: ['#1B1230', '#2A1840', '#16122A'] },
  { key: 'mono', label: 'mono', stops: ['#121216', '#1A1A20', '#101014'] },
];

const CUSTOM_PREFIX = 'custom:';

export function wallpaperByKey(key: string | null | undefined): Wallpaper | null {
  if (!key || key.startsWith(CUSTOM_PREFIX)) return null;
  const found = PIGEON_WALLPAPERS.find((w) => w.key === key);
  if (!found || found.key === 'none') return null;
  return found;
}

export function gradientFromStops(stops: string[]): string {
  if (stops.length === 0) return 'none';
  if (stops.length === 1) return `linear-gradient(160deg, ${stops[0]} 0%, ${stops[0]} 100%)`;
  const parts = stops.map((c, i) => `${c} ${Math.round((i / (stops.length - 1)) * 100)}%`);
  return `linear-gradient(160deg, ${parts.join(', ')})`;
}

export function wallpaperCss(key: string | null | undefined): string {
  if (key && key.startsWith(CUSTOM_PREFIX)) {
    const url = key.slice(CUSTOM_PREFIX.length);
    if (url) return `url("${url}")`;
  }
  const wp = wallpaperByKey(key);
  if (!wp) return 'none';
  return gradientFromStops(wp.stops);
}

export const WALLPAPER_GRADIENTS: Record<string, string> = PIGEON_WALLPAPERS.reduce<
  Record<string, string>
>((acc, wp) => {
  acc[wp.key] = gradientFromStops(wp.stops);
  return acc;
}, {});
