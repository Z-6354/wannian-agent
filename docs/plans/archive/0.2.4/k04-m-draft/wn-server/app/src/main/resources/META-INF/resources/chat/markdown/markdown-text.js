/**
 * Streaming + settled markdown mount. WeakMap per container.
 * Cache-bust: ?v=20260925m2
 */

import {
  IncrementalMarkdownParser,
  parseAllPositioned,
} from "/chat/markdown/incremental.js?v=20260925m2";
import { renderBlock, patchBlock } from "/chat/markdown/render-blocks.js?v=20260925m2";

/** @type {WeakMap<Element, StreamingRenderer>} */
const renderers = new WeakMap();

/**
 * @param {HTMLElement} el
 * @param {string} text
 * @param {{ streaming?: boolean }} [opts]
 */
export function mountAssistantMarkdown(el, text, opts = {}) {
  if (!el) return;
  let renderer = renderers.get(el);
  if (!renderer) {
    renderer = new StreamingRenderer(el);
    renderers.set(el, renderer);
  }
  renderer.render(String(text || ""), Boolean(opts.streaming));
}

class StreamingRenderer {
  /**
   * @param {HTMLElement} container
   */
  constructor(container) {
    this.container = container;
    this.parser = new IncrementalMarkdownParser();
    /** @type {Map<number, HTMLElement>} */
    this.frozenRoots = new Map();
    this.lastText = null;
    this.lastStreaming = null;
    this.lastGeneration = -1;
  }

  /**
   * @param {string} text
   * @param {boolean} streaming
   */
  render(text, streaming) {
    if (text === this.lastText && streaming === this.lastStreaming) {
      return;
    }

    if (streaming) {
      this.container.dataset.streaming = "";
      this.renderStreaming(text);
    } else {
      delete this.container.dataset.streaming;
      this.renderSettled(text);
    }

    this.lastText = text;
    this.lastStreaming = streaming;
  }

  /**
   * @param {string} text
   */
  renderStreaming(text) {
    if (text === "") {
      this.clearAll();
      this.parser.reset();
      this.lastGeneration = this.parser.generation;
      return;
    }

    const { frozen, tail, generation } = this.parser.update(text);
    if (generation !== this.lastGeneration) {
      this.frozenRoots.clear();
      this.container.replaceChildren();
      this.lastGeneration = generation;
    }

    const frozenSet = new Set(frozen.map((f) => f.key));

    // Promote or create frozen nodes (tail node with same key must not duplicate).
    for (const item of frozen) {
      if (this.frozenRoots.has(item.key)) continue;
      let el = this.container.querySelector(
        ':scope > [data-md-key="' + cssEscape(String(item.key)) + '"]',
      );
      if (el) {
        el = patchBlock(el, item.node, { streaming: true });
      } else {
        el = renderBlock(item.node, item.key, { streaming: true });
        this.container.append(el);
      }
      this.frozenRoots.set(item.key, el);
    }

    // Drop children that are neither frozen nor about to be patched as tail.
    const tailKeys = new Set(tail.map((t) => t.key));
    const toRemove = [];
    for (const child of this.container.children) {
      const key = Number(child.dataset.mdKey);
      if (!frozenSet.has(key) && !tailKeys.has(key)) toRemove.push(child);
    }
    for (const child of toRemove) child.remove();

    // Re-append frozen in order if DOM order drifted.
    let anchor = null;
    for (const item of frozen) {
      const el = this.frozenRoots.get(item.key);
      if (!el) continue;
      if (anchor) {
        if (anchor.nextSibling !== el) {
          this.container.insertBefore(el, anchor.nextSibling);
        }
      } else if (this.container.firstChild !== el) {
        this.container.insertBefore(el, this.container.firstChild);
      }
      anchor = el;
    }

    for (const item of tail) {
      const existing = this.container.querySelector(
        ':scope > [data-md-key="' + cssEscape(String(item.key)) + '"]',
      );
      let el;
      if (existing && !this.frozenRoots.has(item.key)) {
        el = patchBlock(existing, item.node, { streaming: true });
      } else if (!this.frozenRoots.has(item.key)) {
        el = renderBlock(item.node, item.key, { streaming: true });
        this.container.append(el);
      } else {
        el = this.frozenRoots.get(item.key);
      }
      if (anchor) {
        if (anchor.nextSibling !== el) {
          this.container.insertBefore(el, anchor.nextSibling);
        }
      } else if (this.container.firstChild !== el) {
        this.container.insertBefore(el, this.container.firstChild);
      }
      anchor = el;
    }
  }

  /**
   * @param {string} text
   */
  renderSettled(text) {
    this.parser.reset();
    this.frozenRoots.clear();
    this.lastGeneration = this.parser.generation;

    if (text === "") {
      this.container.replaceChildren();
      return;
    }

    const blocks = parseAllPositioned(text);
    const frag = document.createDocumentFragment();
    /** @type {Map<number, HTMLElement>} */
    const nextFrozen = new Map();
    for (const item of blocks) {
      const el = renderBlock(item.node, item.key, { streaming: false });
      nextFrozen.set(item.key, el);
      frag.append(el);
    }
    this.container.replaceChildren(frag);
    this.frozenRoots = nextFrozen;
  }

  clearAll() {
    this.frozenRoots.clear();
    this.container.replaceChildren();
  }
}

function cssEscape(value) {
  if (typeof CSS !== "undefined" && CSS.escape) return CSS.escape(value);
  return String(value).replace(/"/g, '\\"');
}
