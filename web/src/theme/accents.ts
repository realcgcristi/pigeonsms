export interface Accent {
  key: string;
  label: string;
  bright: string;
  deep: string;
  on: string;
}

export const PIGEON_ACCENTS: Accent[] = [
  { key: 'peach', label: 'peach', bright: '#FF9D76', deep: '#E87F55', on: '#2A150C' },
  { key: 'rose', label: 'rose', bright: '#FF8FB0', deep: '#E86F92', on: '#2A0C16' },
  { key: 'coral', label: 'coral', bright: '#FF7E6B', deep: '#E85F4D', on: '#2A0E08' },
  { key: 'amber', label: 'amber', bright: '#FFC46B', deep: '#E8A94D', on: '#2A1E06' },
  { key: 'mint', label: 'mint', bright: '#7FD8A4', deep: '#5FBF88', on: '#06251A' },
  { key: 'sky', label: 'sky', bright: '#76BEFF', deep: '#55A0E8', on: '#0A1B2A' },
  { key: 'iris', label: 'iris', bright: '#9D8CFF', deep: '#7E6BE8', on: '#15102A' },
  { key: 'lavender', label: 'lavender', bright: '#B8A7F5', deep: '#9B86EC', on: '#1B1230' },
];

export const NOVA_ACCENT: Accent = {
  key: 'iris',
  label: 'iris',
  bright: '#B388FF',
  deep: '#8B5CF6',
  on: '#150A2E',
};

const CUSTOM_PREFIX = 'custom:';
const DEEP_FACTOR = 0.82;
const ON_LIGHT = '#201018';
const ON_DARK = '#FFFFFF';

function parseHex(input: string): [number, number, number] | null {
  const raw = input.trim().replace(/^#/, '');
  if (raw.length === 3) {
    const r = raw[0];
    const g = raw[1];
    const b = raw[2];
    if (!/^[0-9a-fA-F]{3}$/.test(raw)) return null;
    return [parseInt(r + r, 16), parseInt(g + g, 16), parseInt(b + b, 16)];
  }
  if (raw.length === 6 || raw.length === 8) {
    if (!/^[0-9a-fA-F]+$/.test(raw)) return null;
    const off = raw.length === 8 ? 2 : 0;
    return [
      parseInt(raw.slice(off, off + 2), 16),
      parseInt(raw.slice(off + 2, off + 4), 16),
      parseInt(raw.slice(off + 4, off + 6), 16),
    ];
  }
  return null;
}

function toHex(r: number, g: number, b: number): string {
  const part = (v: number): string => {
    const clamped = Math.max(0, Math.min(255, Math.round(v)));
    return clamped.toString(16).padStart(2, '0');
  };
  return `#${part(r)}${part(g)}${part(b)}`.toUpperCase();
}

function channelToLinear(v: number): number {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

export function luminance(r: number, g: number, b: number): number {
  return (
    0.2126 * channelToLinear(r) + 0.7152 * channelToLinear(g) + 0.0722 * channelToLinear(b)
  );
}

export function accentByKey(key: string): Accent {
  if (key.startsWith(CUSTOM_PREFIX)) {
    const parsed = parseHex(key.slice(CUSTOM_PREFIX.length));
    if (parsed) {
      const [r, g, b] = parsed;
      return {
        key,
        label: 'custom',
        bright: toHex(r, g, b),
        deep: toHex(r * DEEP_FACTOR, g * DEEP_FACTOR, b * DEEP_FACTOR),
        on: luminance(r, g, b) > 0.5 ? ON_LIGHT : ON_DARK,
      };
    }
  }
  return PIGEON_ACCENTS.find((a) => a.key === key) ?? PIGEON_ACCENTS[0];
}

export const AVATAR_PALETTE = [
  '#FF9D76',
  '#B8A7F5',
  '#7FD8A4',
  '#76BEFF',
  '#FF8FB0',
  '#FFC46B',
];

export const ON_AVATAR = '#201018';
