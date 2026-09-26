/**
 * Block / inline → DOM. Only §5 classes. No innerHTML of source.
 * Cache-bust: ?v=20260925m2
 */

import { createCodeBlock, updateCodeBlock } from "/chat/markdown/code-block.js?v=20260925m2";

/**
 * @param {object} block
 * @param {number} key
 * @param {{ streaming?: boolean }} [opts]
 * @returns {HTMLElement}
 */
export function renderBlock(block, key, opts = {}) {
  switch (block.type) {
    case "heading":
      return renderHeading(block, key);
    case "paragraph":
      return renderParagraph(block, key);
    case "code":
      return createCodeBlock({
        key,
        lang: block.lang,
        value: block.value || "",
        closed: block.closed !== false,
        streaming: Boolean(opts.streaming),
      });
    case "list":
      return renderList(block, key);
    case "blockquote":
      return renderQuote(block, key);
    case "rule":
      return renderRule(key);
    default:
      return renderParagraph(
        { type: "paragraph", text: "", inlines: [{ type: "text", value: "" }] },
        key,
      );
  }
}

/**
 * Patch an existing root for the same key (esp. growing fences).
 * @param {HTMLElement} el
 * @param {object} block
 * @param {{ streaming?: boolean }} [opts]
 */
export function patchBlock(el, block, opts = {}) {
  if (block.type === "code" && el.classList.contains("md-code")) {
    updateCodeBlock(el, {
      key: Number(el.dataset.mdKey),
      lang: block.lang,
      value: block.value || "",
      closed: block.closed !== false,
      streaming: Boolean(opts.streaming),
    });
    return el;
  }
  const next = renderBlock(block, Number(el.dataset.mdKey), opts);
  el.replaceWith(next);
  return next;
}

function renderHeading(block, key) {
  const level = Math.min(6, Math.max(1, block.level || 1));
  const el = document.createElement("h" + level);
  el.className = "md-heading";
  el.dataset.mdKey = String(key);
  el.dataset.level = String(level);
  appendInlines(el, block.inlines || [{ type: "text", value: block.text || "" }]);
  return el;
}

function renderParagraph(block, key) {
  const el = document.createElement("p");
  el.className = "md-paragraph";
  el.dataset.mdKey = String(key);
  appendInlines(el, block.inlines || [{ type: "text", value: block.text || "" }]);
  return el;
}

function renderList(block, key) {
  const el = document.createElement(block.ordered ? "ol" : "ul");
  el.className = block.ordered ? "md-list md-list-ordered" : "md-list";
  el.dataset.mdKey = String(key);
  if (block.ordered && block.startNumber && block.startNumber !== 1) {
    el.start = block.startNumber;
  }
  const items = Array.isArray(block.items) ? block.items : [];
  for (const item of items) {
    const li = document.createElement("li");
    li.className = "md-list-item";
    appendInlines(li, item.inlines || [{ type: "text", value: item.text || "" }]);
    el.append(li);
  }
  return el;
}

function renderQuote(block, key) {
  const el = document.createElement("blockquote");
  el.className = "md-quote";
  el.dataset.mdKey = String(key);
  const p = document.createElement("p");
  p.className = "md-paragraph";
  appendInlines(p, block.inlines || [{ type: "text", value: block.text || "" }]);
  el.append(p);
  return el;
}

function renderRule(key) {
  const el = document.createElement("hr");
  el.className = "md-rule";
  el.dataset.mdKey = String(key);
  return el;
}

/**
 * @param {HTMLElement} parent
 * @param {object[]} inlines
 */
function appendInlines(parent, inlines) {
  for (const node of inlines || []) {
    parent.append(renderInline(node));
  }
}

/**
 * @param {object} node
 * @returns {Node}
 */
function renderInline(node) {
  switch (node.type) {
    case "code": {
      const el = document.createElement("code");
      el.className = "md-inline-code";
      el.textContent = node.value || "";
      return el;
    }
    case "strong": {
      const el = document.createElement("strong");
      el.className = "md-strong";
      appendInlines(el, node.children || []);
      return el;
    }
    case "em": {
      const el = document.createElement("em");
      el.className = "md-em";
      appendInlines(el, node.children || []);
      return el;
    }
    case "link": {
      const href = safeUrl(node.href);
      if (!href) {
        return document.createTextNode(inlineText(node));
      }
      const el = document.createElement("a");
      el.className = "md-link";
      el.href = href;
      el.target = "_blank";
      el.rel = "noopener noreferrer";
      appendInlines(el, node.children || []);
      return el;
    }
    case "image": {
      const src = safeUrl(node.href);
      if (!src) {
        return document.createTextNode(node.alt || "");
      }
      const el = document.createElement("img");
      el.className = "md-image";
      el.src = src;
      el.alt = node.alt || "";
      return el;
    }
    case "text":
    default:
      return document.createTextNode(node.value || "");
  }
}

function inlineText(node) {
  if (node.type === "text" || node.type === "code") return node.value || "";
  if (Array.isArray(node.children)) {
    return node.children.map(inlineText).join("");
  }
  return "";
}

/**
 * @param {string} [raw]
 * @returns {string|null}
 */
export function safeUrl(raw) {
  const href = String(raw || "").trim();
  if (!href) return null;
  const lower = href.toLowerCase();
  if (lower.startsWith("https:") || lower.startsWith("http:")) return href;
  if (lower.startsWith("mailto:")) {
    const rest = href.slice("mailto:".length);
    if (rest && !/[\s<>"]/.test(rest)) return href;
  }
  return null;
}
