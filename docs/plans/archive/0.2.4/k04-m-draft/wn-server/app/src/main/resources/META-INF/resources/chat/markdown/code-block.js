/**
 * Fenced code block DOM (M1 shell + M2 incremental highlight).
 * Cache-bust: ?v=20260925m2
 */

import { StreamingHighlightSession } from "/chat/markdown/highlight.js?v=20260925m2";

const LABEL_COPY = "复制";
const LABEL_COPIED = "已复制";
const COPIED_MS = 1500;

/** @type {WeakMap<HTMLElement, StreamingHighlightSession>} */
const sessions = new WeakMap();

/**
 * @param {object} opts
 * @param {number} opts.key
 * @param {string} [opts.lang]
 * @param {string} [opts.value]
 * @param {boolean} [opts.streaming]
 * @param {boolean} [opts.closed]
 * @returns {HTMLElement}
 */
export function createCodeBlock(opts) {
  const root = document.createElement("div");
  root.className = "md-code";
  root.dataset.mdKey = String(opts.key);
  updateCodeBlock(root, opts);
  return root;
}

/**
 * @param {HTMLElement} root
 * @param {object} opts
 */
export function updateCodeBlock(root, opts) {
  const lang = opts.lang || "";
  const value = opts.value || "";
  const streaming = Boolean(opts.streaming) && opts.closed === false;

  if (lang) root.dataset.lang = lang;
  else delete root.dataset.lang;

  if (streaming) root.dataset.streaming = "";
  else delete root.dataset.streaming;

  let header = root.querySelector(":scope > .md-code-header");
  if (!header) {
    header = document.createElement("div");
    header.className = "md-code-header";
    const langEl = document.createElement("span");
    langEl.className = "md-code-lang";
    const copy = document.createElement("button");
    copy.type = "button";
    copy.className = "md-code-copy";
    copy.dataset.labelCopy = LABEL_COPY;
    copy.dataset.labelCopied = LABEL_COPIED;
    copy.textContent = LABEL_COPY;
    header.append(langEl, copy);
    root.append(header);
    bindCopyOnce(root, copy);
  }

  const langEl = header.querySelector(".md-code-lang");
  if (langEl && langEl.textContent !== lang) langEl.textContent = lang;

  let pre = root.querySelector(":scope > .md-code-pre");
  if (!pre) {
    pre = document.createElement("pre");
    pre.className = "md-code-pre";
    const code = document.createElement("code");
    code.className = "md-code-body";
    pre.append(code);
    root.append(pre);
  }
  const code = pre.querySelector(".md-code-body");
  if (!code) return;

  let session = sessions.get(root);
  if (!session) {
    session = new StreamingHighlightSession();
    sessions.set(root, session);
  }

  if (streaming) {
    session.updateFrame(code, value, lang);
  } else {
    session.renderSettled(code, value, lang);
  }
}

/**
 * @param {HTMLElement} root
 * @param {HTMLButtonElement} button
 */
function bindCopyOnce(root, button) {
  if (button.dataset.mdCopyBound) return;
  button.dataset.mdCopyBound = "1";
  button.addEventListener("click", async (event) => {
    event.preventDefault();
    event.stopPropagation();
    const body = root.querySelector(".md-code-body");
    const text = body ? body.textContent || "" : "";
    const labelCopy = button.dataset.labelCopy || LABEL_COPY;
    const labelCopied = button.dataset.labelCopied || LABEL_COPIED;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
        button.textContent = labelCopied;
        window.setTimeout(() => {
          button.textContent = labelCopy;
        }, COPIED_MS);
      }
    } catch (_err) {
      button.textContent = labelCopy;
    }
  });
}
