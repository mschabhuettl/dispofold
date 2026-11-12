// Colour helpers.

/** Relative luminance (WCAG) of '#RRGGBB'. */
export function luminance(hex) {
  const n = parseInt(hex.slice(1), 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

/** WCAG contrast ratio of two '#RRGGBB' colours. */
export function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const isHex = (hex) => /^#[0-9a-f]{6}$/i.test(hex ?? '');

/** Black or white text, whichever has more contrast on the background. */
export function textOn(hex) {
  if (!isHex(hex)) return '#000000';
  const l = luminance(hex);
  return (l + 0.05) / 0.05 >= 1.05 / (l + 0.05) ? '#000000' : '#FFFFFF';
}

/** Surface the dark theme mixes data colours towards (same as --surface in app.css). */
export const DARK_SURFACE = '#162129';
const DARK_MIX = 0.35;

function mix(hex, other, share) {
  const a = parseInt(hex.slice(1), 16);
  const b = parseInt(other.slice(1), 16);
  const ch = [16, 8, 0].map((s) => Math.round(((a >> s) & 255) * (1 - share) + ((b >> s) & 255) * share));
  return `#${ch.map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase()}`;
}

/**
 * Background and text colour for a data colour (shift, area, absence type) in both themes.
 * Dark mode dims the colour towards the dark surface so light tints do not glare in a dimmed room.
 */
export function tint(hex, dark = false) {
  if (!isHex(hex)) return null;
  const bg = dark ? mix(hex, DARK_SURFACE, DARK_MIX) : hex.toUpperCase();
  return { bg, fg: textOn(bg) };
}

/** Inline style with both variants as custom properties; the class "tinted" in app.css picks one per theme. */
export function tintStyle(hex) {
  const l = tint(hex, false);
  const d = tint(hex, true);
  if (!l) return '';
  return `--c-bg:${l.bg};--c-fg:${l.fg};--c-bg-d:${d.bg};--c-fg-d:${d.fg}`;
}
