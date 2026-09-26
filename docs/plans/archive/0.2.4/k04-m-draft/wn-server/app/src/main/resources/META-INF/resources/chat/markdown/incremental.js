/**
 * Incremental block-level parse for append-only streams (DSH-aligned).
 * Cache-bust: ?v=20260925m2
 */

import { parseMarkdownRoot } from "/chat/markdown/parse.js?v=20260925m2";

export const UNSTABLE_TAIL_BLOCKS = 2;

/**
 * @typedef {import('./parse.js').Block} Block
 * @typedef {{ node: Block, key: number }} PositionedBlock
 * @typedef {{ frozen: PositionedBlock[], tail: PositionedBlock[], generation: number }} IncrementalBlocks
 */

export class IncrementalMarkdownParser {
  constructor() {
    this.prevText = "";
    this.tailStart = 0;
    /** @type {PositionedBlock[]} */
    this.frozen = [];
    this.generation = 0;
    /** @type {IncrementalBlocks | null} */
    this.cached = null;
  }

  /**
   * @param {string} text
   * @returns {IncrementalBlocks}
   */
  update(text) {
    const source = String(text || "");
    if (this.cached && source === this.prevText) {
      return this.cached;
    }

    if (!source.startsWith(this.prevText) || source.length < this.prevText.length) {
      this.generation += 1;
      this.frozen = [];
      this.tailStart = 0;
      this.openFenceReset();
    } else if (source === this.prevText) {
      return this.cached || { frozen: this.frozen.slice(), tail: [], generation: this.generation };
    }

    this.prevText = source;
    const slice = source.slice(this.tailStart);
    const root = parseMarkdownRoot(slice);
    const positioned = root.children.map((node, index) =>
      positionBlock(node, this.tailStart, index),
    );

    const unstable = Math.min(UNSTABLE_TAIL_BLOCKS, positioned.length);
    let keep = positioned.length - unstable;

    // Never freeze an unclosed fence (even if it would fall in the stable prefix).
    while (keep > 0) {
      const candidate = positioned[keep - 1];
      if (candidate.node.type === "code" && candidate.node.closed === false) {
        keep -= 1;
        continue;
      }
      break;
    }

    // Also: if the last block in the would-be frozen set is code unclosed — already handled.
    // If any unclosed fence appears before keep, pull keep back to that index.
    for (let i = 0; i < keep; i += 1) {
      const n = positioned[i].node;
      if (n.type === "code" && n.closed === false) {
        keep = i;
        break;
      }
    }

    const newlyFrozen = positioned.slice(0, keep);
    if (newlyFrozen.length) {
      this.frozen = this.frozen.concat(newlyFrozen);
      const last = newlyFrozen[newlyFrozen.length - 1];
      this.tailStart = last.node.end;
    }

    const tailSlice = source.slice(this.tailStart);
    const tailRoot = parseMarkdownRoot(tailSlice);
    const tail = tailRoot.children.map((node, index) =>
      positionBlock(node, this.tailStart, index),
    );

    const result = {
      frozen: this.frozen.slice(),
      tail,
      generation: this.generation,
    };
    this.cached = result;
    return result;
  }

  reset() {
    this.prevText = "";
    this.tailStart = 0;
    this.frozen = [];
    this.generation += 1;
    this.cached = null;
    this.openFenceReset();
  }

  openFenceReset() {
    /* reserved for M2 fence frontier; M1 treats open fence as unstable tail only */
  }
}

/**
 * @param {Block} node
 * @param {number} base
 * @param {number} index
 * @returns {PositionedBlock}
 */
function positionBlock(node, base, index) {
  const absolute = {
    ...node,
    start: base + (node.start || 0),
    end: base + (node.end || 0),
  };
  // Re-stamp list item offsets if present
  if (absolute.type === "list" && Array.isArray(absolute.items)) {
    absolute.items = absolute.items.map((item) => ({
      ...item,
      start: base + (item.start || 0),
      end: base + (item.end || 0),
    }));
  }
  const key =
    typeof absolute.start === "number" ? absolute.start : -(index + 1);
  return { node: absolute, key };
}

/**
 * Parse entire document once (settled path).
 * @param {string} text
 * @returns {PositionedBlock[]}
 */
export function parseAllPositioned(text) {
  const root = parseMarkdownRoot(String(text || ""));
  return root.children.map((node, index) => positionBlock(node, 0, index));
}
