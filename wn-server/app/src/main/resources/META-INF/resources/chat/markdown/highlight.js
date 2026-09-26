/**
 * Minimal incremental highlighter (M2). No CDN / no Shiki.
 * Languages: js|ts|javascript|typescript|json|shell|bash|sh
 * Tokens via class md-tok-* only (colors in CSS variables).
 * Cache-bust: ?v=20260925m2
 */

export const HIGHLIGHT_LANGS = new Set([
  "js",
  "ts",
  "javascript",
  "typescript",
  "json",
  "shell",
  "bash",
  "sh",
]);

/** Locked §5 token classes */
export const TOK = {
  keyword: "md-tok-keyword",
  string: "md-tok-string",
  comment: "md-tok-comment",
  number: "md-tok-number",
  punct: "md-tok-punct",
  property: "md-tok-property",
  plain: "md-tok-plain",
};

const GROUP_LINES = 32;

const JS_KEYWORDS = new Set([
  "break",
  "case",
  "catch",
  "class",
  "const",
  "continue",
  "debugger",
  "default",
  "delete",
  "do",
  "else",
  "export",
  "extends",
  "false",
  "finally",
  "for",
  "function",
  "if",
  "import",
  "in",
  "instanceof",
  "let",
  "new",
  "null",
  "return",
  "super",
  "switch",
  "this",
  "throw",
  "true",
  "try",
  "typeof",
  "undefined",
  "var",
  "void",
  "while",
  "with",
  "yield",
  "async",
  "await",
  "of",
  "from",
  "as",
  "type",
  "interface",
  "enum",
  "implements",
  "readonly",
  "public",
  "private",
  "protected",
  "static",
  "abstract",
  "declare",
  "namespace",
  "module",
  "keyof",
  "infer",
  "satisfies",
]);

const SHELL_KEYWORDS = new Set([
  "if",
  "then",
  "else",
  "elif",
  "fi",
  "for",
  "while",
  "do",
  "done",
  "case",
  "esac",
  "in",
  "function",
  "select",
  "until",
  "time",
  "coproc",
  "export",
  "local",
  "return",
  "exit",
  "set",
  "unset",
  "shift",
  "readonly",
  "declare",
  "typeset",
  "alias",
  "unalias",
  "source",
  "true",
  "false",
]);

/**
 * @param {string} [lang]
 * @returns {string|null}
 */
export function normalizeLang(lang) {
  const raw = String(lang || "")
    .trim()
    .toLowerCase();
  if (!raw) return null;
  if (raw === "javascript" || raw === "js" || raw === "jsx") return "js";
  if (raw === "typescript" || raw === "ts" || raw === "tsx") return "ts";
  if (raw === "json") return "json";
  if (raw === "shell" || raw === "bash" || raw === "sh" || raw === "zsh") return "shell";
  return HIGHLIGHT_LANGS.has(raw) ? raw : null;
}

/**
 * @typedef {{ type: string, value: string }} Token
 */

/**
 * @param {string} source
 * @param {string|null} family
 * @returns {Token[]}
 */
export function tokenize(source, family) {
  if (!family) return [{ type: TOK.plain, value: source }];
  if (family === "json") return tokenizeJson(source);
  if (family === "shell") return tokenizeShell(source);
  return tokenizeJsLike(source);
}

/**
 * Incremental highlight: completed lines grouped (32); only append re-tokenized.
 */
export class StreamingHighlightSession {
  constructor() {
    this.prevCode = "";
    this.lang = "";
    this.family = null;
    /** @type {DocumentFragment[]} */
    this.groupFrags = [];
    /** @type {string[]} */
    this.pendingLines = [];
    this.pendingText = "";
  }

  /**
   * @param {HTMLElement} codeEl
   * @param {string} code
   * @param {string} [lang]
   */
  updateFrame(codeEl, code, lang) {
    const source = String(code || "");
    const nextLang = String(lang || "");
    const family = normalizeLang(nextLang);

    if (!family) {
      this.reset();
      if (codeEl.textContent !== source) codeEl.textContent = source;
      return;
    }

    if (
      nextLang !== this.lang ||
      !source.startsWith(this.prevCode) ||
      source.length < this.prevCode.length
    ) {
      this.reset();
      this.lang = nextLang;
      this.family = family;
      this.ingest(source);
      this.paint(codeEl);
      this.prevCode = source;
      return;
    }

    if (source === this.prevCode) return;

    this.family = family;
    this.appendChunk(source.slice(this.prevCode.length));
    this.paint(codeEl);
    this.prevCode = source;
  }

  /**
   * @param {HTMLElement} codeEl
   * @param {string} code
   * @param {string} [lang]
   */
  renderSettled(codeEl, code, lang) {
    this.reset();
    const source = String(code || "");
    const family = normalizeLang(lang);
    this.lang = String(lang || "");
    this.family = family;
    if (!family) {
      codeEl.textContent = source;
      this.prevCode = source;
      return;
    }
    this.ingest(source);
    this.paint(codeEl);
    this.prevCode = source;
  }

  reset() {
    this.prevCode = "";
    this.lang = "";
    this.family = null;
    this.groupFrags = [];
    this.pendingLines = [];
    this.pendingText = "";
  }

  /** @param {string} source */
  ingest(source) {
    this.groupFrags = [];
    this.pendingLines = [];
    this.pendingText = "";
    this.appendChunk(source);
  }

  /** @param {string} appended */
  appendChunk(appended) {
    const buf = this.pendingText + appended;
    const { committed, pending } = splitCommittedPending(buf);
    for (const line of committed) {
      this.pendingLines.push(line);
      if (this.pendingLines.length >= GROUP_LINES) this.flushGroup();
    }
    this.pendingText = pending;
  }

  flushGroup() {
    const text = this.pendingLines.join("");
    this.pendingLines = [];
    this.groupFrags.push(tokensToFragment(tokenize(text, this.family)));
  }

  /** @param {HTMLElement} codeEl */
  paint(codeEl) {
    const frag = document.createDocumentFragment();
    for (const g of this.groupFrags) {
      frag.append(g.cloneNode(true));
    }
    if (this.pendingLines.length) {
      frag.append(tokensToFragment(tokenize(this.pendingLines.join(""), this.family)));
    }
    if (this.pendingText) {
      frag.append(tokensToFragment(tokenize(this.pendingText, this.family)));
    }
    codeEl.replaceChildren(frag);
  }
}

/**
 * @param {string} source
 * @returns {{ committed: string[], pending: string }}
 */
function splitCommittedPending(source) {
  if (!source) return { committed: [], pending: "" };
  const committed = [];
  let start = 0;
  for (let i = 0; i < source.length; i += 1) {
    if (source[i] === "\n") {
      committed.push(source.slice(start, i + 1));
      start = i + 1;
    }
  }
  return { committed, pending: source.slice(start) };
}

/**
 * @param {Token[]} tokens
 * @returns {DocumentFragment}
 */
function tokensToFragment(tokens) {
  const frag = document.createDocumentFragment();
  for (const tok of tokens) {
    if (!tok.value) continue;
    if (tok.type === TOK.plain) {
      frag.append(document.createTextNode(tok.value));
    } else {
      const span = document.createElement("span");
      span.className = tok.type;
      span.textContent = tok.value;
      frag.append(span);
    }
  }
  return frag;
}

function tokenizeJsLike(source) {
  /** @type {Token[]} */
  const out = [];
  let i = 0;
  while (i < source.length) {
    const ch = source[i];
    if (ch === "/" && source[i + 1] === "/") {
      let j = i + 2;
      while (j < source.length && source[j] !== "\n") j += 1;
      out.push({ type: TOK.comment, value: source.slice(i, j) });
      i = j;
      continue;
    }
    if (ch === "/" && source[i + 1] === "*") {
      let j = i + 2;
      while (j < source.length && !(source[j] === "*" && source[j + 1] === "/")) j += 1;
      j = Math.min(source.length, j + 2);
      out.push({ type: TOK.comment, value: source.slice(i, j) });
      i = j;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") {
      const q = ch;
      let j = i + 1;
      while (j < source.length) {
        if (source[j] === "\\") {
          j += 2;
          continue;
        }
        if (source[j] === q) {
          j += 1;
          break;
        }
        j += 1;
      }
      out.push({ type: TOK.string, value: source.slice(i, j) });
      i = j;
      continue;
    }
    if (/[0-9]/.test(ch) || (ch === "." && /[0-9]/.test(source[i + 1] || ""))) {
      let j = i + 1;
      while (j < source.length && /[0-9_a-fxo.]/i.test(source[j])) j += 1;
      out.push({ type: TOK.number, value: source.slice(i, j) });
      i = j;
      continue;
    }
    if (/[A-Za-z_$]/.test(ch)) {
      let j = i + 1;
      while (j < source.length && /[A-Za-z0-9_$]/.test(source[j])) j += 1;
      const word = source.slice(i, j);
      const prev = lastNonWs(out);
      const isProp = prev && prev.value === "." && !JS_KEYWORDS.has(word);
      out.push({
        type: isProp ? TOK.property : JS_KEYWORDS.has(word) ? TOK.keyword : TOK.plain,
        value: word,
      });
      i = j;
      continue;
    }
    if ("(){}[];,.:=<>!&|?+-*%~^".includes(ch)) {
      out.push({ type: TOK.punct, value: ch });
      i += 1;
      continue;
    }
    out.push({ type: TOK.plain, value: ch });
    i += 1;
  }
  return mergePlain(out);
}

function tokenizeJson(source) {
  /** @type {Token[]} */
  const out = [];
  let i = 0;
  while (i < source.length) {
    const ch = source[i];
    if (ch === '"') {
      let j = i + 1;
      while (j < source.length) {
        if (source[j] === "\\") {
          j += 2;
          continue;
        }
        if (source[j] === '"') {
          j += 1;
          break;
        }
        j += 1;
      }
      const str = source.slice(i, j);
      let k = j;
      while (k < source.length && /\s/.test(source[k])) k += 1;
      out.push({ type: source[k] === ":" ? TOK.property : TOK.string, value: str });
      i = j;
      continue;
    }
    if (/[0-9\-]/.test(ch)) {
      let j = i + 1;
      while (j < source.length && /[0-9.eE+-]/.test(source[j])) j += 1;
      out.push({ type: TOK.number, value: source.slice(i, j) });
      i = j;
      continue;
    }
    if (/[a-z]/.test(ch)) {
      let j = i + 1;
      while (j < source.length && /[a-z]/.test(source[j])) j += 1;
      const word = source.slice(i, j);
      out.push({
        type: word === "true" || word === "false" || word === "null" ? TOK.keyword : TOK.plain,
        value: word,
      });
      i = j;
      continue;
    }
    if ("{}[]:,".includes(ch)) {
      out.push({ type: TOK.punct, value: ch });
      i += 1;
      continue;
    }
    out.push({ type: TOK.plain, value: ch });
    i += 1;
  }
  return mergePlain(out);
}

function tokenizeShell(source) {
  /** @type {Token[]} */
  const out = [];
  let i = 0;
  while (i < source.length) {
    const ch = source[i];
    if (ch === "#") {
      let j = i + 1;
      while (j < source.length && source[j] !== "\n") j += 1;
      out.push({ type: TOK.comment, value: source.slice(i, j) });
      i = j;
      continue;
    }
    if (ch === '"' || ch === "'") {
      const q = ch;
      let j = i + 1;
      while (j < source.length) {
        if (q === '"' && source[j] === "\\") {
          j += 2;
          continue;
        }
        if (source[j] === q) {
          j += 1;
          break;
        }
        j += 1;
      }
      out.push({ type: TOK.string, value: source.slice(i, j) });
      i = j;
      continue;
    }
    if (/[A-Za-z_]/.test(ch)) {
      let j = i + 1;
      while (j < source.length && /[A-Za-z0-9_\-]/.test(source[j])) j += 1;
      const word = source.slice(i, j);
      out.push({
        type: SHELL_KEYWORDS.has(word) ? TOK.keyword : TOK.plain,
        value: word,
      });
      i = j;
      continue;
    }
    if (/[0-9]/.test(ch)) {
      let j = i + 1;
      while (j < source.length && /[0-9]/.test(source[j])) j += 1;
      out.push({ type: TOK.number, value: source.slice(i, j) });
      i = j;
      continue;
    }
    out.push({ type: TOK.plain, value: ch });
    i += 1;
  }
  return mergePlain(out);
}

function lastNonWs(tokens) {
  for (let i = tokens.length - 1; i >= 0; i -= 1) {
    if (tokens[i].value.trim()) return tokens[i];
  }
  return null;
}

function mergePlain(tokens) {
  /** @type {Token[]} */
  const out = [];
  for (const t of tokens) {
    const prev = out[out.length - 1];
    if (prev && prev.type === TOK.plain && t.type === TOK.plain) {
      prev.value += t.value;
    } else {
      out.push({ type: t.type, value: t.value });
    }
  }
  return out;
}
