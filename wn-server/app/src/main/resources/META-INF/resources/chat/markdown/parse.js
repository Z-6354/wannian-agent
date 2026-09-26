/**
 * Top-level markdown block parse (M1 subset). No raw HTML nodes.
 * Soft breaks inside paragraphs collapse to spaces (CommonMark).
 * Cache-bust: ?v=20260925m2
 */

/**
 * @typedef {'heading'|'paragraph'|'code'|'list'|'blockquote'|'rule'} BlockType
 * @typedef {{
 *   type: BlockType,
 *   start: number,
 *   end: number,
 *   level?: number,
 *   lang?: string,
 *   closed?: boolean,
 *   ordered?: boolean,
 *   startNumber?: number,
 *   value?: string,
 *   items?: Array<{ start: number, end: number, text: string, children?: object[] }>,
 *   text?: string,
 *   inlines?: InlineNode[],
 * }} Block
 * @typedef {{
 *   type: 'text'|'code'|'strong'|'em'|'link'|'image',
 *   value?: string,
 *   href?: string,
 *   alt?: string,
 *   children?: InlineNode[],
 * }} InlineNode
 */

const ATX_RE = /^(#{1,6})[ \t]+(.*?)(?:[ \t]+#+[ \t]*)?$/;
const FENCE_OPEN_RE = /^([ \t]{0,3})(`{3,}|~{3,})([^\n`]*)$/;
const UL_RE = /^([ \t]{0,3})([-*+])[ \t]+(.*)$/;
const OL_RE = /^([ \t]{0,3})(\d{1,9})([.)])[ \t]+(.*)$/;
const QUOTE_RE = /^([ \t]{0,3})>[ \t]?(.*)$/;
const RULE_RE = /^([ \t]{0,3})([-*_])(?:[ \t]*\2){2,}[ \t]*$/;

/**
 * @param {string} source
 * @returns {Block[]}
 */
export function parseBlocks(source) {
  const text = String(source || "");
  const lines = splitLines(text);
  /** @type {Block[]} */
  const blocks = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    if (line.content === "" || /^[ \t]*$/.test(line.content)) {
      i += 1;
      continue;
    }

    const fence = matchFenceOpen(line.content);
    if (fence) {
      const block = parseFence(lines, i, fence);
      blocks.push(block);
      i = block._nextIndex;
      delete block._nextIndex;
      continue;
    }

    if (RULE_RE.test(line.content) && !UL_RE.test(line.content)) {
      blocks.push({
        type: "rule",
        start: line.start,
        end: line.end,
      });
      i += 1;
      continue;
    }

    const atx = ATX_RE.exec(line.content);
    if (atx) {
      const raw = atx[2] || "";
      blocks.push({
        type: "heading",
        start: line.start,
        end: line.end,
        level: atx[1].length,
        text: raw,
        inlines: parseInlines(raw),
      });
      i += 1;
      continue;
    }

    const quote = QUOTE_RE.exec(line.content);
    if (quote) {
      const block = parseQuote(lines, i);
      blocks.push(block);
      i = block._nextIndex;
      delete block._nextIndex;
      continue;
    }

    if (UL_RE.test(line.content) || OL_RE.test(line.content)) {
      const block = parseList(lines, i);
      blocks.push(block);
      i = block._nextIndex;
      delete block._nextIndex;
      continue;
    }

    const para = parseParagraph(lines, i);
    blocks.push(para);
    i = para._nextIndex;
    delete para._nextIndex;
  }

  return blocks;
}

/**
 * Full document as a synthetic root for incremental re-parse of a slice.
 * Offsets in returned blocks are relative to `text` (slice-local); caller adds base.
 * @param {string} text
 * @returns {{ children: Block[] }}
 */
export function parseMarkdownRoot(text) {
  return { children: parseBlocks(text) };
}

function splitLines(text) {
  /** @type {Array<{ start: number, end: number, content: string }>} */
  const lines = [];
  let start = 0;
  for (let i = 0; i <= text.length; i += 1) {
    if (i === text.length) {
      lines.push({ start, end: i, content: text.slice(start, i) });
      break;
    }
    if (text[i] === "\n") {
      lines.push({ start, end: i + 1, content: text.slice(start, i) });
      start = i + 1;
    } else if (text[i] === "\r") {
      const end = text[i + 1] === "\n" ? i + 2 : i + 1;
      lines.push({ start, end, content: text.slice(start, i) });
      start = end;
      if (end === i + 2) i += 1;
    }
  }
  return lines;
}

function matchFenceOpen(content) {
  const m = FENCE_OPEN_RE.exec(content);
  if (!m) return null;
  const marker = m[2][0];
  const markerLength = m[2].length;
  if (marker === "`" && m[3].includes("`")) return null;
  const info = (m[3] || "").trim();
  const lang = info.split(/\s+/)[0] || "";
  return { marker, markerLength, lang, indent: m[1].length };
}

function parseFence(lines, startIndex, fence) {
  const open = lines[startIndex];
  let i = startIndex + 1;
  const valueParts = [];
  let closed = false;
  while (i < lines.length) {
    const line = lines[i];
    if (isClosingFence(line.content, fence.marker, fence.markerLength)) {
      closed = true;
      i += 1;
      break;
    }
    valueParts.push(line.content);
    i += 1;
  }
  const endLine = lines[Math.min(i, lines.length) - 1];
  const end = closed ? endLine.end : lines[lines.length - 1].end;
  const value = valueParts.join("\n");
  return {
    type: "code",
    start: open.start,
    end,
    lang: fence.lang,
    closed,
    value,
    _nextIndex: i,
  };
}

function isClosingFence(content, marker, markerLength) {
  let indent = 0;
  while (indent < 3 && content[indent] === " ") indent += 1;
  let run = indent;
  while (content[run] === marker) run += 1;
  if (run - indent < markerLength) return false;
  return /^[ \t]*$/.test(content.slice(run));
}

function parseQuote(lines, startIndex) {
  const start = lines[startIndex].start;
  const parts = [];
  let i = startIndex;
  while (i < lines.length) {
    const m = QUOTE_RE.exec(lines[i].content);
    if (!m) break;
    parts.push(m[2]);
    i += 1;
  }
  const end = lines[i - 1].end;
  const text = parts.join("\n").replace(/\n/g, " ");
  return {
    type: "blockquote",
    start,
    end,
    text,
    inlines: parseInlines(text),
    _nextIndex: i,
  };
}

function parseList(lines, startIndex) {
  const first = lines[startIndex].content;
  const ol = OL_RE.exec(first);
  const ul = UL_RE.exec(first);
  const ordered = Boolean(ol);
  const startNumber = ordered ? Number(ol[2]) : undefined;
  const start = lines[startIndex].start;
  /** @type {Array<{ start: number, end: number, text: string, inlines: InlineNode[] }>} */
  const items = [];
  let i = startIndex;
  let depthGuard = 0;

  while (i < lines.length) {
    const line = lines[i];
    const itemOl = OL_RE.exec(line.content);
    const itemUl = UL_RE.exec(line.content);
    if (!itemOl && !itemUl) {
      if (/^[ \t]{2,}\S/.test(line.content) && items.length) {
        const cont = line.content.replace(/^[ \t]+/, "");
        const last = items[items.length - 1];
        last.text = (last.text + " " + cont).replace(/\s+/g, " ").trim();
        last.inlines = parseInlines(last.text);
        last.end = line.end;
        i += 1;
        continue;
      }
      break;
    }
    const indent = (itemOl || itemUl)[1].length;
    if (indent >= 4) {
      // depth>2-ish: swallow into previous item text
      if (items.length) {
        const raw = itemOl ? itemOl[4] : itemUl[3];
        const last = items[items.length - 1];
        last.text = (last.text + " " + raw).trim();
        last.inlines = parseInlines(last.text);
        last.end = line.end;
        i += 1;
        depthGuard += 1;
        if (depthGuard > 50) break;
        continue;
      }
    }
    const rawText = itemOl ? itemOl[4] : itemUl[3];
    items.push({
      start: line.start,
      end: line.end,
      text: rawText,
      inlines: parseInlines(rawText),
    });
    i += 1;
  }

  return {
    type: "list",
    start,
    end: lines[i - 1].end,
    ordered,
    startNumber,
    items,
    _nextIndex: i,
  };
}

function parseParagraph(lines, startIndex) {
  const start = lines[startIndex].start;
  const parts = [];
  let i = startIndex;
  while (i < lines.length) {
    const line = lines[i];
    if (line.content === "" || /^[ \t]*$/.test(line.content)) break;
    if (matchFenceOpen(line.content)) break;
    if (ATX_RE.test(line.content)) break;
    if (QUOTE_RE.test(line.content)) break;
    if (UL_RE.test(line.content) || OL_RE.test(line.content)) break;
    if (RULE_RE.test(line.content) && !UL_RE.test(line.content)) break;
    parts.push(line.content.trim());
    i += 1;
  }
  const text = parts.join(" ").replace(/[ \t]+/g, " ");
  return {
    type: "paragraph",
    start,
    end: lines[i - 1].end,
    text,
    inlines: parseInlines(text),
    _nextIndex: i,
  };
}

/**
 * Inline subset: `code`, **strong**, *em*, [text](url), ![alt](url).
 * No raw HTML.
 * @param {string} input
 * @returns {InlineNode[]}
 */
export function parseInlines(input) {
  const text = String(input || "");
  /** @type {InlineNode[]} */
  const nodes = [];
  let i = 0;
  let buf = "";

  const flush = () => {
    if (buf) {
      nodes.push({ type: "text", value: buf });
      buf = "";
    }
  };

  while (i < text.length) {
    if (text[i] === "`") {
      const end = text.indexOf("`", i + 1);
      if (end > i) {
        flush();
        nodes.push({ type: "code", value: text.slice(i + 1, end) });
        i = end + 1;
        continue;
      }
    }

    if (text[i] === "!" && text[i + 1] === "[") {
      const img = parseLinkLike(text, i + 1, true);
      if (img) {
        flush();
        nodes.push(img.node);
        i = img.end;
        continue;
      }
    }

    if (text[i] === "[") {
      const link = parseLinkLike(text, i, false);
      if (link) {
        flush();
        nodes.push(link.node);
        i = link.end;
        continue;
      }
    }

    if (text.startsWith("**", i)) {
      const end = text.indexOf("**", i + 2);
      if (end > i) {
        flush();
        nodes.push({
          type: "strong",
          children: parseInlines(text.slice(i + 2, end)),
        });
        i = end + 2;
        continue;
      }
    }

    if (text[i] === "*" && text[i + 1] !== "*") {
      const end = text.indexOf("*", i + 1);
      if (end > i) {
        flush();
        nodes.push({
          type: "em",
          children: parseInlines(text.slice(i + 1, end)),
        });
        i = end + 1;
        continue;
      }
    }

    buf += text[i];
    i += 1;
  }
  flush();
  return nodes;
}

function parseLinkLike(text, startBracket, isImage) {
  if (text[startBracket] !== "[") return null;
  const closeBracket = findClosing(text, startBracket, "[", "]");
  if (closeBracket < 0) return null;
  if (text[closeBracket + 1] !== "(") return null;
  const closeParen = findClosing(text, closeBracket + 1, "(", ")");
  if (closeParen < 0) return null;
  const label = text.slice(startBracket + 1, closeBracket);
  const href = text.slice(closeBracket + 2, closeParen).trim();
  if (isImage) {
    return {
      end: closeParen + 1,
      node: { type: "image", href, alt: label },
    };
  }
  return {
    end: closeParen + 1,
    node: {
      type: "link",
      href,
      children: parseInlines(label),
    },
  };
}

function findClosing(text, openIndex, openCh, closeCh) {
  let depth = 0;
  for (let i = openIndex; i < text.length; i += 1) {
    if (text[i] === openCh) depth += 1;
    else if (text[i] === closeCh) {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}
