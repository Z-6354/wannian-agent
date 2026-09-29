/**
 * 2.4.5 页面状态：selectionEpoch、按会话缓存、流式合并 reducer。
 */

export const STORAGE_KEY = "wannian.chat.conversationId";

export function createChatState() {
  return {
    selectionEpoch: 0,
    activeConversationId: "",
    header: emptyHeader(),
    conversationsById: Object.create(null),
    listStatus: "ACTIVE",
    listItems: [],
    searchQuery: "",
    searchHits: [],
    committedByConversation: Object.create(null),
    inflightByTurnId: Object.create(null),
    seenRunKeys: Object.create(null),
    seenOutboxSequences: Object.create(null),
    expandedTurnIds: Object.create(null),
    expandedToolIds: Object.create(null),
    scrollPinnedBottom: true,
    notice: "",
    noticeKind: "",
    streamNotice: "",
    outboxCursor: 0,
    queue: [],
    submitting: false,
    supersededMessageIds: Object.create(null),
  };
}

export function emptyHeader() {
  return {
    id: "",
    title: "",
    titleSource: "",
    revision: 0,
    status: "",
  };
}

export function selectConversation(state, conversationId) {
  state.selectionEpoch += 1;
  state.activeConversationId = conversationId || "";
  state.inflightByTurnId = Object.create(null);
  state.seenRunKeys = Object.create(null);
  state.seenOutboxSequences = Object.create(null);
  state.outboxCursor = 0;
  state.streamNotice = "";
  state.queue = [];
  if (!conversationId) {
    state.header = emptyHeader();
  }
  return state.selectionEpoch;
}

export function rememberConversationId(id) {
  const value = id || "";
  try {
    if (value) {
      localStorage.setItem(STORAGE_KEY, value);
      sessionStorage.setItem(STORAGE_KEY, value);
    } else {
      localStorage.removeItem(STORAGE_KEY);
      sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch (_error) {
    /* ignore quota / private mode */
  }
}

export function readRememberedConversationId() {
  try {
    return localStorage.getItem(STORAGE_KEY) || sessionStorage.getItem(STORAGE_KEY) || "";
  } catch (_error) {
    return sessionStorage.getItem(STORAGE_KEY) || "";
  }
}

export function upsertConversation(state, summary) {
  if (!summary || !summary.id) {
    return;
  }
  const prev = state.conversationsById[summary.id] || {};
  state.conversationsById[summary.id] = {
    ...prev,
    ...summary,
    id: summary.id,
  };
}

export function setConversationHeader(state, summary) {
  if (!summary) {
    state.header = emptyHeader();
    return;
  }
  state.header = {
    id: summary.id || "",
    title: summary.title || "",
    titleSource: summary.titleSource || "",
    revision: typeof summary.revision === "number" ? summary.revision : 0,
    status: summary.status || "",
  };
  upsertConversation(state, summary);
}

export function getCommitted(state, conversationId) {
  const id = conversationId || state.activeConversationId;
  if (!id) {
    return { items: [], lastSeq: 0 };
  }
  if (!state.committedByConversation[id]) {
    state.committedByConversation[id] = { items: [], lastSeq: 0 };
  }
  return state.committedByConversation[id];
}

export function applyHistoryPage(state, conversationId, messages, { replace } = {}) {
  const bucket = getCommitted(state, conversationId);
  if (replace) {
    bucket.items = [];
    bucket.lastSeq = 0;
  }
  const byKey = Object.create(null);
  for (const item of bucket.items) {
    byKey[itemKey(item)] = item;
  }
  for (const raw of messages || []) {
    const mapped = mapHistoryMessage(raw);
    if (!mapped) continue;
    byKey[itemKey(mapped)] = mapped;
    if (typeof raw.sequenceNo === "number" && raw.sequenceNo > bucket.lastSeq) {
      bucket.lastSeq = raw.sequenceNo;
    }
  }
  bucket.items = Object.values(byKey).sort(compareItems);
  return bucket;
}

export function applyStreamEvent(state, conversationId, type, data, eventId) {
  if (!conversationId || conversationId !== state.activeConversationId) {
    return { changed: false };
  }
  let payload = data;
  if (typeof data === "string") {
    try {
      payload = JSON.parse(data);
    } catch (_error) {
      payload = {};
    }
  }
  if (!payload || typeof payload !== "object") {
    payload = {};
  }

  if (eventId != null && eventId !== "") {
    const seq = Number(eventId);
    if (Number.isFinite(seq)) {
      if (state.seenOutboxSequences[String(seq)]) {
        return { changed: false };
      }
      state.seenOutboxSequences[String(seq)] = true;
      if (seq > state.outboxCursor) {
        state.outboxCursor = seq;
      }
    }
  }

  const executionId = stringOf(payload.executionId);
  const runSeq = typeof payload.runSeq === "number" ? payload.runSeq : null;
  if (executionId && runSeq != null) {
    const key = executionId + ":" + runSeq;
    if (state.seenRunKeys[key]) {
      return { changed: false };
    }
    state.seenRunKeys[key] = true;
  }

  switch (type) {
    case "turn.started":
      return applyTurnStarted(state, payload);
    case "reply.delta":
      return applyReplyDelta(state, payload);
    case "tool.started":
      return applyToolStarted(state, payload);
    case "tool.updated":
      return applyToolUpdated(state, payload);
    case "message.committed":
      return applyCommittedMessage(state, conversationId, payload);
    case "turn.completed":
      return applyTurnTerminal(state, payload, "completed");
    case "turn.failed":
      return applyTurnTerminal(state, payload, "failed");
    case "turn.cancelled":
      return applyTurnTerminal(state, payload, "cancelled");
    case "conversation.titleChanged":
      return applyTitleChanged(state, conversationId, payload);
    default:
      console.debug("[chat] ignore sse event", type);
      return { changed: false };
  }
}

export function applyTitleChanged(state, conversationId, payload) {
  const id = stringOf(payload.conversationId) || conversationId;
  if (!id) return { changed: false };
  const title = stringOf(payload.title);
  if (!title) return { changed: false };
  const evtSource = stringOf(payload.titleSource).toUpperCase() || "AUTO";
  const evtRevision = typeof payload.revision === "number" ? payload.revision : -1;

  const local =
    (state.header && state.header.id === id && state.header) ||
    state.conversationsById[id] ||
    null;
  const localSource = local ? String(local.titleSource || "").toUpperCase() : "";
  const localRevision = local && typeof local.revision === "number" ? local.revision : 0;

  if (localSource === "MANUAL") {
    if (evtSource === "AUTO") return { changed: false };
    if (evtRevision >= 0 && evtRevision <= localRevision) return { changed: false };
  }

  const summary = {
    id,
    title,
    titleSource: evtSource,
    revision: evtRevision >= 0 ? evtRevision : localRevision + 1,
    status: (local && local.status) || "ACTIVE",
  };
  upsertConversation(state, summary);
  if (state.activeConversationId === id) {
    setConversationHeader(state, summary);
  }
  return { changed: true, titleChanged: true, summary };
}

export function applyCommittedMessage(state, conversationId, payload) {
  const turnId = stringOf(payload.turnId);
  const messageId = stringOf(payload.messageId);
  const role = stringOf(payload.role).toUpperCase();
  const preview = stringOf(payload.textPreview);
  const fullText = stringOf(payload.text);
  const bucket = getCommitted(state, conversationId);
  const isTaskDelivery =
    payload.taskDelivery === true ||
    fullText.startsWith("───") ||
    preview.startsWith("───");

  // 同 turn 主回复已落地时忽略重复 ASSISTANT；Busy 虚线附带（2.5.6）除外
  if (role === "ASSISTANT" && !isTaskDelivery && hasOfficialAssistantTurn(bucket, turnId)) {
    return { changed: false };
  }

  if (role === "USER") {
    const text = fullText || preview;
    const item = {
      kind: "user",
      messageId,
      turnId,
      text,
      sequenceNo: null,
      temporary: false,
      idleWake: text.startsWith("[后台任务完成]"),
    };
    mergeItem(bucket, item);
    return { changed: true };
  }

  if (isTaskDelivery) {
    mergeItem(bucket, {
      kind: "assistant-turn",
      turnId,
      messageId,
      text: fullText || preview,
      toolCalls: [],
      status: "completed",
      temporary: false,
      sequenceNo: null,
      taskDelivery: true,
      executionId: "",
    });
    return { changed: true };
  }

  const inflight = turnId ? state.inflightByTurnId[turnId] : null;
  const text =
    inflight && inflight.text && inflight.text.length >= preview.length
      ? inflight.text
      : preview || (inflight && inflight.text) || "";
  const tools = inflight && Array.isArray(inflight.toolCalls) ? inflight.toolCalls.slice() : [];
  const item = {
    kind: "assistant-turn",
    turnId,
    messageId,
    text,
    toolCalls: tools,
    status: "completed",
    temporary: false,
    sequenceNo: null,
    executionId: inflight ? inflight.executionId : "",
  };
  mergeItem(bucket, item);
  if (turnId && state.inflightByTurnId[turnId]) {
    delete state.inflightByTurnId[turnId];
  }
  return { changed: true };
}

export function appendOptimisticUser(state, conversationId, text, turnId, status) {
  const bucket = getCommitted(state, conversationId);
  const turnStatus = String(status || "running").toLowerCase();
  const queued = turnStatus === "received" || turnStatus === "queued";
  const item = {
    kind: "user",
    messageId: "local-user-" + turnId,
    turnId,
    text,
    sequenceNo: null,
    temporary: true,
    optimistic: true,
    queueStatus: queued ? "queued" : "",
  };
  mergeItem(bucket, item);
  state.inflightByTurnId[turnId] = {
    turnId,
    executionId: "",
    text: "",
    toolCalls: [],
    status: queued ? "queued" : "running",
    temporary: true,
  };
  mergeItem(bucket, {
    kind: "assistant-turn",
    turnId,
    messageId: "local-assistant-" + turnId,
    text: "",
    toolCalls: [],
    status: queued ? "queued" : "running",
    temporary: true,
    executionId: "",
  });
}

export function markTurnQueueStatus(state, turnId, status) {
  if (!turnId) return;
  const st = String(status || "").toLowerCase();
  const inflight = state.inflightByTurnId[turnId];
  if (inflight) {
    inflight.status = st;
  }
  const bucket = getCommitted(state, state.activeConversationId);
  for (const item of bucket.items) {
    if (item.turnId !== turnId) continue;
    if (item.kind === "user") {
      item.queueStatus = st === "queued" || st === "received" ? "queued" : "";
    }
    if (item.kind === "assistant-turn" && item.temporary) {
      item.status = st === "queued" || st === "received" ? "queued" : item.status;
    }
  }
}

export function activeTranscript(state) {
  if (!state.activeConversationId) {
    return [];
  }
  return getCommitted(state, state.activeConversationId).items.slice();
}

function applyTurnStarted(state, payload) {
  const turnId = stringOf(payload.turnId);
  if (!turnId) return { changed: false };
  const bucket = getCommitted(state, state.activeConversationId);
  if (hasOfficialAssistantTurn(bucket, turnId)) return { changed: false };
  const executionId = stringOf(payload.executionId);
  const existing = state.inflightByTurnId[turnId] || {
    turnId,
    text: "",
    toolCalls: [],
    status: "running",
    temporary: true,
  };
  existing.executionId = executionId;
  existing.status = "running";
  state.inflightByTurnId[turnId] = existing;
  const user = bucket.items.find((item) => item.kind === "user" && item.turnId === turnId);
  if (user) {
    user.queueStatus = "";
  }
  mergeItem(bucket, {
    kind: "assistant-turn",
    turnId,
    messageId: existing.messageId || "local-assistant-" + turnId,
    text: existing.text,
    toolCalls: existing.toolCalls,
    status: "running",
    temporary: true,
    executionId,
  });
  return { changed: true, turnStarted: true, turnId };
}

function applyReplyDelta(state, payload) {
  const turnId = stringOf(payload.turnId);
  if (!turnId) return { changed: false };
  const bucket = getCommitted(state, state.activeConversationId);
  if (hasOfficialAssistantTurn(bucket, turnId)) return { changed: false };
  const chunk = stringOf(payload.text);
  const inflight = state.inflightByTurnId[turnId] || {
    turnId,
    executionId: stringOf(payload.executionId),
    text: "",
    toolCalls: [],
    status: "running",
    temporary: true,
  };
  inflight.text = (inflight.text || "") + chunk;
  inflight.executionId = stringOf(payload.executionId) || inflight.executionId;
  state.inflightByTurnId[turnId] = inflight;
  mergeItem(bucket, {
    kind: "assistant-turn",
    turnId,
    messageId: inflight.messageId || "local-assistant-" + turnId,
    text: inflight.text,
    toolCalls: inflight.toolCalls,
    status: "running",
    temporary: true,
    executionId: inflight.executionId,
  });
  return { changed: true };
}

function applyToolStarted(state, payload) {
  return upsertTool(state, payload, {
    status: "RUNNING",
    argumentsJson: stringOf(payload.argumentsSummary) || "{}",
    startedAt: stringOf(payload.at),
  });
}

function applyToolUpdated(state, payload) {
  return upsertTool(state, payload, {
    status: stringOf(payload.status) || "UNKNOWN",
    errorCode: stringOf(payload.errorCode),
    finishedAt: stringOf(payload.at),
    resultSummary: stringOf(payload.resultSummary),
    argumentsJson: stringOf(payload.argumentsSummary) || undefined,
  });
}

function upsertTool(state, payload, patch) {
  const turnId = stringOf(payload.turnId);
  const callId = stringOf(payload.callId) || stringOf(payload.operationId);
  if (!turnId || !callId) return { changed: false };
  const bucket = getCommitted(state, state.activeConversationId);
  if (hasOfficialAssistantTurn(bucket, turnId)) return { changed: false };
  const inflight = state.inflightByTurnId[turnId] || {
    turnId,
    executionId: stringOf(payload.executionId),
    text: "",
    toolCalls: [],
    status: "running",
    temporary: true,
  };
  const tools = Array.isArray(inflight.toolCalls) ? inflight.toolCalls.slice() : [];
  const idx = tools.findIndex((t) => t.callId === callId);
  const base =
    idx >= 0
      ? tools[idx]
      : {
          callId,
          name: stringOf(payload.name) || "tool",
          startedAt: "",
          finishedAt: "",
          argumentsJson: "{}",
          status: "",
          errorCode: "",
          resultSummary: "",
        };
  const next = {
    ...base,
    name: stringOf(payload.name) || base.name,
    ...Object.fromEntries(Object.entries(patch).filter(([, v]) => v !== undefined)),
  };
  if (idx >= 0) tools[idx] = next;
  else tools.push(next);
  inflight.toolCalls = tools;
  state.inflightByTurnId[turnId] = inflight;
  mergeItem(bucket, {
    kind: "assistant-turn",
    turnId,
    messageId: inflight.messageId || "local-assistant-" + turnId,
    text: inflight.text,
    toolCalls: tools,
    status: "running",
    temporary: true,
    executionId: inflight.executionId,
  });
  return { changed: true };
}

function applyTurnTerminal(state, payload, status) {
  const turnId = stringOf(payload.turnId);
  if (!turnId) return { changed: false };
  const inflight = state.inflightByTurnId[turnId];
  const bucket = getCommitted(state, state.activeConversationId);
  const existing = bucket.items.find(
    (item) => item.kind === "assistant-turn" && item.turnId === turnId
  );
  if (status === "completed") {
    if (inflight) {
      delete state.inflightByTurnId[turnId];
    }
    if (existing && existing.temporary) {
      existing.status = "completed";
      existing.temporary = false;
    }
    return {
      changed: true,
      refetch: true,
      turnTerminal: true,
      turnId,
      terminalStatus: status,
    };
  }
  if (inflight) {
    delete state.inflightByTurnId[turnId];
  }
  // 失败/取消：保留为临时未完成投影，不得写入已提交历史语义；随后 refetch 校准
  mergeItem(bucket, {
    kind: "assistant-turn",
    turnId,
    messageId: (existing && existing.messageId) || "local-assistant-" + turnId,
    text: (inflight && inflight.text) || (existing && existing.text) || "",
    toolCalls: (inflight && inflight.toolCalls) || (existing && existing.toolCalls) || [],
    status,
    temporary: true,
    unfinished: true,
    errorCode: stringOf(payload.errorCode) || stringOf(payload.code) || "",
    executionId: (inflight && inflight.executionId) || (existing && existing.executionId) || "",
  });
  return {
    changed: true,
    refetch: true,
    turnTerminal: true,
    turnId,
    terminalStatus: status,
  };
}

function mapHistoryMessage(raw) {
  if (!raw || typeof raw !== "object") return null;
  const role = stringOf(raw.role).toUpperCase();
  const turnId = stringOf(raw.turnId);
  const messageId = stringOf(raw.id) || stringOf(raw.messageId);
  if (role === "USER") {
    const text = stringOf(raw.text);
    return {
      kind: "user",
      messageId,
      turnId,
      text,
      sequenceNo: typeof raw.sequenceNo === "number" ? raw.sequenceNo : null,
      temporary: false,
      // 2.5.7：Idle 唤模合成触发句，不展示为用户气泡
      idleWake: text.startsWith("[后台任务完成]"),
    };
  }
  if (role === "ASSISTANT") {
    const text = stringOf(raw.text);
    const taskDelivery = text.startsWith("───");
    const toolCalls = taskDelivery
      ? []
      : (Array.isArray(raw.toolCalls) ? raw.toolCalls : []).map((tool, index) =>
          mapTool(tool, turnId, index)
        );
    return {
      kind: "assistant-turn",
      turnId,
      messageId,
      text,
      toolCalls,
      status: "completed",
      temporary: false,
      sequenceNo: typeof raw.sequenceNo === "number" ? raw.sequenceNo : null,
      taskDelivery,
    };
  }
  return null;
}

function mapTool(tool, turnId, index) {
  const callId =
    extractCallId(tool && tool.argumentsJson) || turnId + ":tool:" + index;
  return {
    callId,
    name: (tool && tool.name) || "tool",
    startedAt: (tool && tool.startedAt) || "",
    finishedAt: (tool && tool.finishedAt) || "",
    argumentsJson: (tool && tool.argumentsJson) || "{}",
    status: (tool && tool.status) || "",
    errorCode: (tool && tool.errorCode) || "",
    resultSummary: "",
  };
}

function extractCallId(argumentsJson) {
  if (!argumentsJson || typeof argumentsJson !== "string") return "";
  try {
    const parsed = JSON.parse(argumentsJson);
    if (parsed && typeof parsed.callId === "string") return parsed.callId;
    if (parsed && typeof parsed.operationId === "string") return parsed.operationId;
  } catch (_error) {
    /* ignore */
  }
  return "";
}

function mergeItem(bucket, item) {
  const key = itemKey(item);
  const idx = bucket.items.findIndex((existing) => itemKey(existing) === key);
  if (idx >= 0) {
    const prev = bucket.items[idx];
    bucket.items[idx] = {
      ...prev,
      ...item,
      toolCalls: item.toolCalls != null ? item.toolCalls : prev.toolCalls,
      text: item.text != null && item.text !== "" ? item.text : prev.text || item.text,
      messageId:
        item.messageId && !String(item.messageId).startsWith("local-")
          ? item.messageId
          : prev.messageId && !String(prev.messageId).startsWith("local-")
            ? prev.messageId
            : item.messageId || prev.messageId,
      temporary: item.temporary === false ? false : item.temporary ?? prev.temporary,
      optimistic: item.optimistic === false ? false : undefined,
    };
  } else {
    bucket.items.push(item);
  }
  bucket.items.sort(compareItems);
}

function itemKey(item) {
  if (item.kind === "user") {
    return "u:" + (item.turnId || item.messageId);
  }
  if (item.kind === "assistant-turn") {
    // Busy 虚线附带与主回复可同 turnId，必须按 messageId 分键
    if (item.taskDelivery) {
      return "d:" + (item.messageId || item.turnId);
    }
    return "t:" + (item.turnId || item.messageId);
  }
  if (item.messageId) {
    return "m:" + item.messageId;
  }
  return "x:" + String(item.turnId || Math.random());
}

function hasOfficialAssistantTurn(bucket, turnId) {
  if (!turnId) return false;
  return bucket.items.some(
    (item) =>
      item.kind === "assistant-turn" &&
      item.turnId === turnId &&
      item.temporary === false
  );
}

/**
 * 打开会话后：找出「有用户句、尚无正式助手句」的 turnId，供拉状态恢复进行中投影。
 */
export function findRecoverableTurnIds(state) {
  const bucket = getCommitted(state, state.activeConversationId);
  const out = [];
  const seen = Object.create(null);
  for (let i = bucket.items.length - 1; i >= 0 && out.length < 8; i--) {
    const item = bucket.items[i];
    if (!item || item.kind !== "user" || !item.turnId) continue;
    const turnId = String(item.turnId);
    if (seen[turnId]) continue;
    seen[turnId] = true;
    if (hasOfficialAssistantTurn(bucket, turnId)) continue;
    out.push(turnId);
  }
  return out;
}

/**
 * 为仍在服务端进行中的 turn 补助手临时气泡与 inflight（不重复插用户句）。
 * @returns {boolean} 是否新建或刷新了临时助手块
 */
export function ensureInflightAssistant(state, turnId, turnStatus) {
  if (!turnId) return false;
  const bucket = getCommitted(state, state.activeConversationId);
  if (hasOfficialAssistantTurn(bucket, turnId)) return false;
  const st = String(turnStatus || "RUNNING").toUpperCase();
  const queued = st === "RECEIVED" || st === "CLAIMED" || st === "QUEUED";
  const status = queued ? "queued" : "running";
  const prev = state.inflightByTurnId[turnId];
  state.inflightByTurnId[turnId] = {
    turnId,
    executionId: (prev && prev.executionId) || "",
    text: (prev && prev.text) || "",
    toolCalls: (prev && prev.toolCalls) || [],
    status,
    temporary: true,
  };
  mergeItem(bucket, {
    kind: "assistant-turn",
    turnId,
    messageId: "local-assistant-" + turnId,
    text: state.inflightByTurnId[turnId].text,
    toolCalls: state.inflightByTurnId[turnId].toolCalls,
    status,
    temporary: true,
    executionId: state.inflightByTurnId[turnId].executionId,
  });
  return true;
}

function compareItems(a, b) {
  const as = a.sequenceNo;
  const bs = b.sequenceNo;
  if (typeof as === "number" && typeof bs === "number" && as !== bs) {
    return as - bs;
  }
  if (typeof as === "number" && typeof bs !== "number") return -1;
  if (typeof bs === "number" && typeof as !== "number") return 1;
  return String(a.turnId || "").localeCompare(String(b.turnId || ""));
}

function stringOf(value) {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}
