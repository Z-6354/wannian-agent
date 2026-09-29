import { activeTranscript } from "/chat/state.js?v=20260928d";
import { mountAssistantMarkdown } from "/chat/markdown/markdown-text.js?v=20260925n";

const NEAR_BOTTOM_PX = 96;

/**
 * transcript 增量 DOM：双层可展开详情、底部附近才跟随滚动。
 * 安全：助手正文经 mountAssistantMarkdown；用户气泡与工具输出仅 textContent；
 * 禁止 innerHTML 直插不可信内容。
 */
export function createRenderer({
  transcriptEl,
  state,
  onScrollPinChange,
  onTaskDeliveryClick,
}) {
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

  transcriptEl.addEventListener("click", (ev) => {
    const node = ev.target.closest(".chat-message.chat-task-delivery");
    if (!node || !transcriptEl.contains(node)) return;
    const taskId = node.dataset.taskId || "";
    if (!taskId || typeof onTaskDeliveryClick !== "function") return;
    ev.preventDefault();
    onTaskDeliveryClick(taskId);
  });

  function render(options = {}) {
    const items = activeTranscript(state);
    if (items.length === 0) {
      listEl = null;
      transcriptEl.replaceChildren();
      const empty = document.createElement("div");
      empty.className = "empty-state";
      if (state.activeConversationId) {
        const voice = document.createElement("p");
        voice.className = "empty-state-voice";
        voice.textContent = "嗨，我是杜小洛。想聊就聊，有事直接说；累了发一句也行。";
        const hint = document.createElement("p");
        hint.className = "empty-state-hint";
        hint.textContent = "这是开场提示，还没调用模型；你发第一句后我才开始回。";
        empty.append(voice, hint);
      } else {
        const line = document.createElement("p");
        line.textContent = "恢复最近会话或新建。打开本页不会调用模型。";
        empty.append(line);
      }
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
  if (item.taskDelivery) {
    return "delivery:" + (item.messageId || item.turnId);
  }
  return "turn:" + (item.turnId || item.messageId);
}

function patchItem(node, item, state, isNew) {
  if (item.kind === "user") {
    if (item.idleWake) {
      node.hidden = true;
      node.dataset.idleWake = "";
      return;
    }
    node.hidden = false;
    delete node.dataset.idleWake;
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
  if (item.taskDelivery) {
    node.dataset.taskDelivery = "";
    node.classList.add("chat-task-delivery");
    if (item.taskId) {
      node.dataset.taskId = item.taskId;
      node.setAttribute("role", "button");
      node.tabIndex = 0;
      node.title = "查看关联任务";
    } else {
      delete node.dataset.taskId;
      node.removeAttribute("role");
      node.removeAttribute("tabindex");
      node.removeAttribute("title");
    }
  } else {
    delete node.dataset.taskDelivery;
    delete node.dataset.taskId;
    node.classList.remove("chat-task-delivery");
    node.removeAttribute("role");
    node.removeAttribute("tabindex");
    node.removeAttribute("title");
  }

  let role = node.querySelector(":scope > .chat-role");
  if (!role) {
    role = document.createElement("p");
    role.className = "chat-role";
    node.prepend(role);
  }
  role.textContent = labelForAssistant(item);

  let status = node.querySelector(":scope > .chat-turn-status");
  // 任一非空字符（含空白）即关占位，避免只来空白 delta 时卡住
  const hasAssistantText = Boolean(item.text);
  const hasTools = Array.isArray(item.toolCalls) && item.toolCalls.length > 0;
  // 「生成中」仅作空态占位：首字或工具一开始就关掉，避免整轮结束后整行消失导致正文上跳。
  const showGenerating =
    (item.status === "running" || (item.temporary && !item.unfinished)) &&
    !hasAssistantText &&
    !hasTools;
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
  } else if (showGenerating) {
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
    status = null;
  }

  // 进行中且尚无正文：不挂空 chat-md，避免工具面板插入时把空壳顶来顶去
  const showBody =
    hasAssistantText ||
    item.status === "completed" ||
    Boolean(item.unfinished) ||
    !(item.temporary || item.status === "running");

  let body = node.querySelector(":scope > .chat-text");
  if (!showBody) {
    if (body) body.remove();
    body = null;
  } else if (!body || body.tagName !== "DIV" || !body.classList.contains("chat-md")) {
    const next = document.createElement("div");
    next.className = "chat-text chat-md";
    if (body) {
      if (body.dataset.unfinished) next.dataset.unfinished = body.dataset.unfinished;
      body.replaceWith(next);
    } else {
      const process = node.querySelector(":scope > .chat-turn-process");
      const after = process || status || role;
      after.after(next);
    }
    body = next;
  }
  if (body) {
    const streaming = !!(item.temporary || item.status === "running");
    mountAssistantMarkdown(body, item.text || "", { streaming });

    if (item.unfinished && item.status !== "completed") {
      body.dataset.unfinished = item.status || "failed";
    } else {
      delete body.dataset.unfinished;
    }
  }

  ensureTurnProcess(node, item, state);
}

function labelForAssistant(item) {
  if (item.taskDelivery) return "杜小洛 · 后台任务";
  if (item.unfinished && item.status === "failed") return "杜小洛 · 未完成";
  if (item.unfinished && item.status === "cancelled") return "杜小洛 · 已取消";
  if (item.temporary || item.status === "running") return "杜小洛";
  return "杜小洛";
}

function ensureTurnProcess(node, item, state) {
  const turnId = item.turnId || "";
  let details = node.querySelector(":scope > .chat-turn-process");
  const tools = Array.isArray(item.toolCalls) ? item.toolCalls : [];
  // 仅在真实出现 toolCalls（SSE tool.started / 历史投影）时展示过程面板。
  // 「生成中」占位在首个工具出现时已由 patchItem 关掉。
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
    // 过程在正文前：role → process → body，工具先出时不把空正文顶下去
    const bodyEl = node.querySelector(":scope > .chat-text");
    const actions = node.querySelector(":scope > .chat-actions");
    if (bodyEl) node.insertBefore(details, bodyEl);
    else if (actions) node.insertBefore(details, actions);
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
