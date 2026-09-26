import { activeTranscript } from "/chat/state.js?v=20260924e";
import { mountAssistantMarkdown } from "/chat/markdown/markdown-text.js?v=20260925m2";

const NEAR_BOTTOM_PX = 96;

/**
 * transcript 增量 DOM：双层可展开详情、底部附近才跟随滚动。
 * 安全：助手正文经 mountAssistantMarkdown；用户气泡与工具输出仅 textContent；
 * 禁止 innerHTML 直插不可信内容。
 */
export function createRenderer({ transcriptEl, state, onScrollPinChange }) {
  let listEl = null;

  transcriptEl.addEventListener("scroll", () => {
    const distance =
      transcriptEl.scrollHeight - transcriptEl.scrollTop - transcriptEl.clientHeight;
    const pinned = distance <= NEAR_BOTTOM_PX;
    if (state.scrollPinnedBottom !== pinned) {
      state.scrollPinnedBottom = pinned;
      if (typeof onScrollPinChange === "function") {
        onScrollPinChange(pinned);
      }
    }
  });

  function render(options = {}) {
    const items = activeTranscript(state);
    if (items.length === 0) {
      listEl = null;
      transcriptEl.replaceChildren();
      const empty = document.createElement("div");
      empty.className = "empty-state";
      const line = document.createElement("p");
      line.textContent = state.activeConversationId
        ? "此会话还没有消息。写下一句开始对话。"
        : "恢复最近会话或新建。打开本页不会调用模型。";
      empty.append(line);
      transcriptEl.append(empty);
      return;
    }

    if (!listEl || !transcriptEl.contains(listEl)) {
      transcriptEl.replaceChildren();
      listEl = document.createElement("ol");
      listEl.className = "chat-list";
      transcriptEl.append(listEl);
    }

    const existing = new Map();
    for (const node of listEl.children) {
      const key = node.dataset.itemKey;
      if (key) existing.set(key, node);
    }

    const used = new Set();
    let anchor = null;
    for (const item of items) {
      const key = domKey(item);
      used.add(key);
      let node = existing.get(key);
      if (!node) {
        node = document.createElement("li");
        node.className = "chat-message";
        node.dataset.itemKey = key;
        patchItem(node, item, state, true);
        if (anchor && anchor.nextSibling) {
          listEl.insertBefore(node, anchor.nextSibling);
        } else if (!anchor && listEl.firstChild) {
          listEl.insertBefore(node, listEl.firstChild);
        } else {
          listEl.append(node);
        }
      } else {
        patchItem(node, item, state, false);
        if (anchor && node.previousSibling !== anchor) {
          listEl.insertBefore(node, anchor.nextSibling);
        } else if (!anchor && node !== listEl.firstChild) {
          listEl.insertBefore(node, listEl.firstChild);
        }
      }
      anchor = node;
    }

    for (const [key, node] of existing) {
      if (!used.has(key)) {
        node.remove();
      }
    }

    if (options.forceScroll || state.scrollPinnedBottom) {
      transcriptEl.scrollTop = transcriptEl.scrollHeight;
      state.scrollPinnedBottom = true;
    }
  }

  function scrollToMessage(messageId) {
    if (!messageId || !listEl) return;
    const node = listEl.querySelector('[data-message-id="' + cssEscape(messageId) + '"]');
    if (node) {
      state.scrollPinnedBottom = false;
      node.scrollIntoView({ block: "center" });
    }
  }

  return { render, scrollToMessage };
}

function domKey(item) {
  if (item.kind === "user") {
    return "user:" + (item.messageId || item.turnId);
  }
  return "turn:" + (item.turnId || item.messageId);
}

function patchItem(node, item, state, isNew) {
  if (item.kind === "user") {
    node.dataset.role = "user";
    if (item.messageId) node.dataset.messageId = item.messageId;
    let role = node.querySelector(":scope > .chat-role");
    let body = node.querySelector(":scope > .chat-text");
    if (!role) {
      role = document.createElement("p");
      role.className = "chat-role";
      node.append(role);
    }
    if (!body) {
      body = document.createElement("p");
      body.className = "chat-text";
      node.append(body);
    }
    role.textContent = "你";
    body.textContent = item.text || "";
    let queueBadge = node.querySelector(":scope > .chat-queue-badge");
    if (item.queueStatus === "queued") {
      if (!queueBadge) {
        queueBadge = document.createElement("p");
        queueBadge.className = "chat-queue-badge";
        queueBadge.dataset.turnStatus = "queued";
        body.after(queueBadge);
      }
      queueBadge.textContent = "已排队";
    } else if (queueBadge) {
      queueBadge.remove();
    }
    const process = node.querySelector(":scope > .chat-turn-process");
    if (process) process.remove();
    const actions = node.querySelector(":scope > .chat-actions");
    if (actions) actions.remove();
    return;
  }

  node.dataset.role = "assistant";
  if (item.turnId) node.dataset.turnId = item.turnId;
  if (item.messageId) node.dataset.messageId = item.messageId;

  let role = node.querySelector(":scope > .chat-role");
  if (!role) {
    role = document.createElement("p");
    role.className = "chat-role";
    node.prepend(role);
  }
  role.textContent = labelForAssistant(item);

  let status = node.querySelector(":scope > .chat-turn-status");
  if (item.unfinished && item.status === "failed") {
    if (!status) {
      status = document.createElement("p");
      status.className = "chat-turn-status";
      role.after(status);
    }
    status.dataset.turnStatus = "failed";
    status.textContent = "生成失败" + (item.errorCode ? " · " + item.errorCode : "");
    delete status.dataset.active;
  } else if (item.status === "queued") {
    if (!status) {
      status = document.createElement("p");
      status.className = "chat-turn-status";
      status.dataset.turnStatus = "queued";
      role.after(status);
    }
    status.textContent = "已排队";
    delete status.dataset.active;
  } else if (item.status === "running" || (item.temporary && !item.unfinished)) {
    if (!status) {
      status = document.createElement("p");
      status.className = "chat-turn-status";
      status.dataset.active = "";
      role.after(status);
    }
    status.textContent = "生成中";
    delete status.dataset.turnStatus;
  } else if (status) {
    status.remove();
  }

  let body = node.querySelector(":scope > .chat-text");
  if (!body || body.tagName !== "DIV" || !body.classList.contains("chat-md")) {
    const next = document.createElement("div");
    next.className = "chat-text chat-md";
    if (body) {
      if (body.dataset.unfinished) next.dataset.unfinished = body.dataset.unfinished;
      body.replaceWith(next);
    } else {
      const after = status || role;
      after.after(next);
    }
    body = next;
  }
  const streaming = !!(item.temporary || item.status === "running");
  mountAssistantMarkdown(body, item.text || "", { streaming });

  if (item.unfinished && item.status !== "completed") {
    body.dataset.unfinished = item.status || "failed";
  } else {
    delete body.dataset.unfinished;
  }

  ensureTurnProcess(node, item, state);
  ensureTurnActions(node, item);
}

function labelForAssistant(item) {
  if (item.unfinished && item.status === "failed") return "万年 · 未完成";
  if (item.unfinished && item.status === "cancelled") return "万年 · 已取消";
  if (item.temporary || item.status === "running") return "万年";
  return "万年";
}

function ensureTurnProcess(node, item, state) {
  const turnId = item.turnId || "";
  let details = node.querySelector(":scope > .chat-turn-process");
  const tools = Array.isArray(item.toolCalls) ? item.toolCalls : [];
  // 仅在真实出现 toolCalls（SSE tool.started / 历史投影）时展示过程面板。
  // 纯正文流式仍用上方「生成中」，避免无工具时也像「工具正在调用」。
  const hasProcess = tools.length > 0;
  if (!hasProcess) {
    if (details) details.remove();
    return;
  }
  if (!details) {
    details = document.createElement("details");
    details.className = "chat-turn-process";
    const summary = document.createElement("summary");
    summary.className = "chat-turn-process-summary";
    details.append(summary);
    const panel = document.createElement("div");
    panel.className = "chat-turn-process-panel";
    details.append(panel);
    const actions = node.querySelector(":scope > .chat-actions");
    if (actions) node.insertBefore(details, actions);
    else node.append(details);
    details.addEventListener("toggle", () => {
      if (!turnId) return;
      if (details.open) state.expandedTurnIds[turnId] = true;
      else delete state.expandedTurnIds[turnId];
      summary.setAttribute("aria-expanded", details.open ? "true" : "false");
    });
  }
  details.dataset.status = processStatusFromTools(tools, item);
  details.open = Boolean(state.expandedTurnIds[turnId]);
  const summary = details.querySelector(".chat-turn-process-summary");
  summary.setAttribute("aria-expanded", details.open ? "true" : "false");
  const runningTools = tools.some(
    (t) => String((t && t.status) || "").toUpperCase() === "RUNNING"
  );
  // 摘要只反映工具调用，不把整轮正文生成写成「进行中」。
  summary.textContent =
    "回合过程 · " + tools.length + " 个工具" + (runningTools ? " · 调用中" : "");

  const panel = details.querySelector(".chat-turn-process-panel");
  const staleTimeline = panel.querySelector(".chat-turn-timeline");
  if (staleTimeline) staleTimeline.remove();

  let toolsHost = panel.querySelector(".chat-tool-list");
  if (!toolsHost) {
    toolsHost = document.createElement("div");
    toolsHost.className = "chat-tool-list";
    panel.append(toolsHost);
  }

  const existingTools = new Map();
  for (const child of toolsHost.children) {
    if (child.dataset.toolKey) existingTools.set(child.dataset.toolKey, child);
  }
  const used = new Set();
  for (const tool of tools) {
    const toolKey = turnId + ":" + (tool.callId || tool.name);
    used.add(toolKey);
    let card = existingTools.get(toolKey);
    if (!card) {
      card = document.createElement("details");
      card.className = "chat-tool-card";
      card.dataset.toolKey = toolKey;
      const sum = document.createElement("summary");
      sum.className = "chat-tool-summary";
      card.append(sum);
      const body = document.createElement("pre");
      body.className = "chat-tool-body";
      body.tabIndex = 0;
      card.append(body);
      toolsHost.append(card);
      card.addEventListener("toggle", () => {
        if (card.open) state.expandedToolIds[toolKey] = true;
        else delete state.expandedToolIds[toolKey];
        sum.setAttribute("aria-expanded", card.open ? "true" : "false");
      });
    }
    card.dataset.toolStatus = toolStatusState(tool.status);
    card.open = Boolean(state.expandedToolIds[toolKey]);
    const sum = card.querySelector(".chat-tool-summary");
    sum.setAttribute("aria-expanded", card.open ? "true" : "false");
    sum.textContent = toolSummaryLabel(tool);
    const body = card.querySelector(".chat-tool-body");
    body.textContent = formatToolBody(tool);
  }
  for (const [key, child] of existingTools) {
    if (!used.has(key)) child.remove();
  }
}

/** 过程面板动效/着色只跟工具态，不跟整轮正文流式。 */
function processStatusFromTools(tools, item) {
  const states = tools.map((t) => toolStatusState(t && t.status));
  if (states.some((s) => s === "running")) return "running";
  if (states.some((s) => s === "failed" || s === "rejected")) return "failed";
  if (states.some((s) => s === "cancelled")) return "cancelled";
  if (item.status === "failed") return "failed";
  if (item.status === "cancelled") return "cancelled";
  if (states.length && states.every((s) => s === "completed")) return "completed";
  if (item.status === "completed") return "completed";
  return "unknown";
}

function toolStatusState(status) {
  switch (String(status || "").toUpperCase()) {
    case "RUNNING":
      return "running";
    case "SUCCEEDED":
    case "COMPLETED":
      return "completed";
    case "FAILED":
      return "failed";
    case "CANCELLED":
    case "CANCELED":
      return "cancelled";
    case "REJECTED":
      return "rejected";
    default:
      return "unknown";
  }
}

function ensureTurnActions(node, item) {
  let actions = node.querySelector(":scope > .chat-actions");
  if (!actions) {
    actions = document.createElement("div");
    actions.className = "chat-actions";
    node.append(actions);
  }
  if (item.turnId) {
    actions.dataset.turnId = item.turnId;
  }
  /* E 挂 Stop；D 仅预留空挂点 */
}

function toolSummaryLabel(tool) {
  const bits = ["工具 · " + (tool.name || "tool")];
  if (tool.status) bits.push(tool.status);
  if (tool.errorCode) bits.push(tool.errorCode);
  return bits.join(" · ");
}

function formatToolBody(tool) {
  const lines = [];
  const when = formatToolTime(tool.startedAt, tool.finishedAt);
  if (when) lines.push("时间 " + when);
  if (tool.status) {
    lines.push("状态 " + tool.status + (tool.errorCode ? " · " + tool.errorCode : ""));
  }
  lines.push("参数");
  lines.push(prettyJson(tool.argumentsJson));
  if (tool.resultSummary) {
    lines.push("结果摘要");
    lines.push(tool.resultSummary);
  }
  return lines.join("\n");
}

function formatToolTime(startedAt, finishedAt) {
  const start = formatInstant(startedAt);
  if (!start) return "";
  const end = formatInstant(finishedAt);
  if (!end || end === start) return start;
  return start + " → " + end;
}

function formatInstant(iso) {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (n) => String(n).padStart(2, "0");
  return (
    date.getFullYear() +
    "-" +
    pad(date.getMonth() + 1) +
    "-" +
    pad(date.getDate()) +
    " " +
    pad(date.getHours()) +
    ":" +
    pad(date.getMinutes()) +
    ":" +
    pad(date.getSeconds())
  );
}

function prettyJson(raw) {
  if (!raw) return "{}";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch (_error) {
    return raw;
  }
}

function cssEscape(value) {
  if (typeof CSS !== "undefined" && CSS.escape) return CSS.escape(value);
  return String(value).replace(/"/g, '\\"');
}
