import { test } from 'node:test';
import assert from 'node:assert/strict';
import { moveCell } from '../src/lib/grid-nav.js';

const size = { rows: 3, cols: 31 };

test('arrow keys move one cell and stop at the edges', () => {
  assert.deepEqual(moveCell({ row: 1, col: 5 }, 'ArrowRight', size), { row: 1, col: 6 });
  assert.deepEqual(moveCell({ row: 1, col: 5 }, 'ArrowLeft', size), { row: 1, col: 4 });
  assert.deepEqual(moveCell({ row: 1, col: 5 }, 'ArrowUp', size), { row: 0, col: 5 });
  assert.deepEqual(moveCell({ row: 1, col: 5 }, 'ArrowDown', size), { row: 2, col: 5 });
  assert.deepEqual(moveCell({ row: 0, col: 0 }, 'ArrowLeft', size), { row: 0, col: 0 });
  assert.deepEqual(moveCell({ row: 0, col: 0 }, 'ArrowUp', size), { row: 0, col: 0 });
  assert.deepEqual(moveCell({ row: 2, col: 30 }, 'ArrowRight', size), { row: 2, col: 30 });
  assert.deepEqual(moveCell({ row: 2, col: 30 }, 'ArrowDown', size), { row: 2, col: 30 });
});

test('Home and End go to the first and last day, with Ctrl to the first and last cell', () => {
  assert.deepEqual(moveCell({ row: 1, col: 7 }, 'Home', size), { row: 1, col: 0 });
  assert.deepEqual(moveCell({ row: 1, col: 7 }, 'End', size), { row: 1, col: 30 });
  assert.deepEqual(moveCell({ row: 1, col: 7 }, 'Home', { ...size, ctrl: true }), { row: 0, col: 0 });
  assert.deepEqual(moveCell({ row: 1, col: 7 }, 'End', { ...size, ctrl: true }), { row: 2, col: 30 });
});

test('page keys jump rows, other keys are not navigation', () => {
  assert.deepEqual(moveCell({ row: 2, col: 3 }, 'PageUp', size), { row: 0, col: 3 });
  assert.deepEqual(moveCell({ row: 0, col: 3 }, 'PageDown', size), { row: 2, col: 3 });
  assert.equal(moveCell({ row: 0, col: 0 }, 'Enter', size), null);
  assert.equal(moveCell({ row: 0, col: 0 }, 'a', size), null);
  assert.equal(moveCell({ row: 0, col: 0 }, 'ArrowDown', { rows: 0, cols: 0 }), null);
});
