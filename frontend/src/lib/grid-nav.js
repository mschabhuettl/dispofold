// Keyboard navigation in the roster grid (one tab stop, roving focus). Pure, no DOM.

/**
 * Next cell for a key press, or null when the key is not a navigation key.
 * @param {{row: number, col: number}} pos current cell
 * @param {string} key KeyboardEvent.key
 * @param {{rows: number, cols: number, ctrl?: boolean, page?: number}} size grid size; ctrl for Ctrl+Home/End
 */
export function moveCell(pos, key, { rows, cols, ctrl = false, page = 10 }) {
  if (rows <= 0 || cols <= 0) return null;
  const clamp = (r, c) => ({ row: Math.max(0, Math.min(rows - 1, r)), col: Math.max(0, Math.min(cols - 1, c)) });
  const { row, col } = pos;
  switch (key) {
    case 'ArrowLeft': return clamp(row, col - 1);
    case 'ArrowRight': return clamp(row, col + 1);
    case 'ArrowUp': return clamp(row - 1, col);
    case 'ArrowDown': return clamp(row + 1, col);
    case 'Home': return ctrl ? clamp(0, 0) : clamp(row, 0);
    case 'End': return ctrl ? clamp(rows - 1, cols - 1) : clamp(row, cols - 1);
    case 'PageUp': return clamp(row - page, col);
    case 'PageDown': return clamp(row + page, col);
    default: return null;
  }
}
