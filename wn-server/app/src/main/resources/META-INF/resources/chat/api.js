const CONVERSATIONS = "/api/conversations";

/**
 * 对话页唯一的 HTTP 出口。页面模块不得自行 fetch。
 * 2.4.3：列表/最近/详情/历史/搜索/生命周期。
 * 2.4.4：async receive / SSE / stop / turn status。
 * 2.4.5：AbortSignal 透传；SSE 订阅 options 形态。
 * 2.4.6：cancelQueuedTurn 封装（与 stop 同路径；仅 RECEIVED 语义由服务端裁定）。
 */

export function createConversation(title, options) {
  const body = {};
  if (typeof title === "string" && title !== "") {
    body.title = title;
  }
  return post(CONVERSATIONS, body, options);
}

export function listConversations(status, cursor, limit, options) {
  const q = new URLSearchParams();
  if (status) q.set("status", status);
  if (cursor) q.set("cursor", cursor);
  if (limit) q.set("limit", String(limit));
  const suffix = q.toString() ? "?" + q.toString() : "";
  return get(CONVERSATIONS + suffix, options);
}

export function recentConversation(options) {
  return get(CONVERSATIONS + "/recent", options);
}

export function getConversation(conversationId, options) {
  return get(CONVERSATIONS + "/" + encodeURIComponent(conversationId), options);
}

export function listMessages(conversationId, afterSeq, limit, includeToolCalls, options) {
  const q = new URLSearchParams();
  if (afterSeq != null) q.set("afterSeq", String(afterSeq));
  if (limit) q.set("limit", String(limit));
  if (includeToolCalls === false) q.set("includeToolCalls", "false");
  const suffix = q.toString() ? "?" + q.toString() : "";
  return get(
    CONVERSATIONS + "/" + encodeURIComponent(conversationId) + "/messages" + suffix,
    options
  );
}

export function searchConversations(qText, status, cursor, limit, options) {
  const q = new URLSearchParams();
  q.set("q", qText);
  if (status) q.set("status", status);
  if (cursor) q.set("cursor", cursor);
  if (limit) q.set("limit", String(limit));
  return get(CONVERSATIONS + "/search?" + q.toString(), options);
}

export function patchConversation(conversationId, op, expectedRevision, title, options) {
  const body = { op, expectedRevision };
  if (typeof title === "string") {
    body.title = title;
  }
  return patch(CONVERSATIONS + "/" + encodeURIComponent(conversationId), body, options);
}

export function emptyTrash(confirm, batchLimit, options) {
  const body = { confirm: confirm || "EMPTY_TRASH" };
  if (batchLimit) body.batchLimit = batchLimit;
  return post(CONVERSATIONS + "/trash/empty", body, options);
}

/** 永久删除全部归档会话（有界批次；前端可循环至清空）。 */
export function emptyArchive(confirm, batchLimit, options) {
  const body = { confirm: confirm || "EMPTY_ARCHIVE" };
  if (batchLimit) body.batchLimit = batchLimit;
  return post(CONVERSATIONS + "/archive/empty", body, options);
}

/** 2.4.7：清理无消息的空 ACTIVE 会话。 */
export function purgeEmptyConversations(options) {
  return post(CONVERSATIONS + "/empty/purge", {}, options);
}

/** 2.5.10：统一消息中心。 */
export function listNotices(options) {
  return get("/api/notices", options);
}

export function dismissNotice(noticeId, options) {
  return post("/api/notices/" + encodeURIComponent(noticeId) + "/dismiss", {}, options);
}

/**
 * 2.4.7 兼容：归档 HTTP（消息中心撤销仍用 undo）。
 * 旧 notices.js UI 已删除；列表/关闭请走 listNotices / dismissNotice。
 */
export function listArchiveNotices(options) {
  return get(CONVERSATIONS + "/notices/archive", options);
}

export function dismissArchiveNotice(noticeId, options) {
  return post(CONVERSATIONS + "/notices/archive/dismiss", { noticeId }, options);
}

export function undoArchiveNotice(conversationId, expectedRevision, options) {
  return post(
    CONVERSATIONS + "/notices/archive/undo",
    { conversationId, expectedRevision },
    options
  );
}

export function sendTurn(conversationId, text, clientRequestId, options) {
  return post(
    CONVERSATIONS + "/" + encodeURIComponent(conversationId) + "/turns",
    { clientRequestId, text },
    options
  );
}

/** 2.4.4：异步 receive，快速返回 turnId/status，不阻塞等模型。 */
export function sendTurnAsync(conversationId, text, clientRequestId, options) {
  return post(
    CONVERSATIONS + "/" + encodeURIComponent(conversationId) + "/turns/async",
    { clientRequestId, text },
    options
  );
}

export function getTurnStatus(conversationId, turnId, options) {
  return get(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/turns/" +
      encodeURIComponent(turnId),
    options
  );
}

/** 2.5.5：待审后台任务列表。 */
export function listTaskReviews(conversationId, options) {
  return taskApiFetch(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/task-reviews",
    "GET",
    null,
    options
  );
}

export function confirmTaskReview(conversationId, reviewId, options) {
  return taskApiFetch(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/task-reviews/" +
      encodeURIComponent(reviewId) +
      "/confirm",
    "POST",
    {},
    options
  );
}

export function rejectTaskReview(conversationId, reviewId, reason, options) {
  const body = {};
  if (typeof reason === "string" && reason !== "") {
    body.reason = reason;
  }
  return taskApiFetch(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/task-reviews/" +
      encodeURIComponent(reviewId) +
      "/reject",
    "POST",
    body,
    options
  );
}

/** 2.5.9：后台 Task 列表（当前会话）。 */
export async function listBackgroundTasks(conversationId, options) {
  const q = new URLSearchParams();
  if (options && options.activeOnly) {
    q.set("activeOnly", "true");
  }
  const suffix = q.toString() ? "?" + q.toString() : "";
  return taskApiFetch(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/background-tasks" +
      suffix,
    "GET",
    null,
    options
  );
}

export async function getBackgroundTask(conversationId, taskId, options) {
  return taskApiFetch(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/background-tasks/" +
      encodeURIComponent(taskId),
    "GET",
    null,
    options
  );
}

export async function cancelBackgroundTask(conversationId, taskId, options) {
  return taskApiFetch(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/background-tasks/" +
      encodeURIComponent(taskId) +
      "/cancel",
    "POST",
    {},
    options
  );
}

/** 删除终态任务（从列表移除）。 */
export async function deleteBackgroundTask(conversationId, taskId, options) {
  return taskApiFetch(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/background-tasks/" +
      encodeURIComponent(taskId),
    "DELETE",
    null,
    options
  );
}

/** Task API：透传 JSON 字段（normalize 会丢 taskType 等）。 */
async function taskApiFetch(path, method, body, options) {
  let response;
  try {
    const init = {
      method,
      headers: { Accept: "application/json" },
      signal: options && options.signal,
    };
    if (method !== "GET") {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body == null ? {} : body);
    }
    response = await fetch(path, init);
  } catch (error) {
    if (error && error.name === "AbortError") {
      return aborted();
    }
    return failed(0, "NETWORK", "无法连接服务");
  }
  const payload = await response.json().catch(() => null);
  const httpOk = response.status >= 200 && response.status < 300;
  const base = payload && typeof payload === "object" ? payload : {};
  return {
    ok: httpOk,
    status: response.status,
    aborted: false,
    code: typeof base.code === "string" ? base.code : "",
    detail: typeof base.detail === "string" ? base.detail : httpOk ? "" : "请求失败",
    items: Array.isArray(base.items) ? base.items : [],
    task: httpOk ? base : null,
    ...base,
  };
}

export function stopTurn(conversationId, turnId, options) {
  return post(
    CONVERSATIONS +
      "/" +
      encodeURIComponent(conversationId) +
      "/turns/" +
      encodeURIComponent(turnId) +
      "/stop",
    {},
    options
  );
}

/** 撤队：仅 RECEIVED；路径与 stop 相同，由服务端按状态分流。 */
export function cancelQueuedTurn(conversationId, turnId, options) {
  return stopTurn(conversationId, turnId, options);
}

/**
 * 订阅会话 SSE。
 * 形态 A（C）：(id, afterSequence, onEvent) → { close, promise }
 * 形态 B（D）：(id, { cursor, signal, onEvent, onError }) → { close, promise }
 */
export function subscribeConversationEvents(conversationId, afterSequenceOrOpts, onEventMaybe) {
  let afterSequence = null;
  let onEvent = null;
  let onError = null;
  let onOpen = null;
  let externalSignal = null;
  if (
    afterSequenceOrOpts &&
    typeof afterSequenceOrOpts === "object" &&
    !Array.isArray(afterSequenceOrOpts)
  ) {
    const opts = afterSequenceOrOpts;
    afterSequence =
      opts.cursor != null
        ? opts.cursor
        : opts.afterSequence != null
          ? opts.afterSequence
          : null;
    onEvent = opts.onEvent;
    onError = opts.onError;
    onOpen = opts.onOpen;
    externalSignal = opts.signal || null;
  } else {
    afterSequence = afterSequenceOrOpts;
    onEvent = onEventMaybe;
  }

  const q = new URLSearchParams();
  if (afterSequence != null) q.set("afterSequence", String(afterSequence));
  const path =
    CONVERSATIONS +
    "/" +
    encodeURIComponent(conversationId) +
    "/events" +
    (q.toString() ? "?" + q.toString() : "");
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (externalSignal) {
    if (externalSignal.aborted) {
      controller.abort();
    } else {
      externalSignal.addEventListener("abort", onAbort, { once: true });
    }
  }
  const promise = (async () => {
    try {
      let response;
      try {
        response = await fetch(path, {
          method: "GET",
          headers: { Accept: "text/event-stream" },
          signal: controller.signal,
        });
      } catch (error) {
        if (controller.signal.aborted) {
          return { aborted: true };
        }
        const wrapped = new Error("无法连接服务");
        wrapped.code = "NETWORK";
        wrapped.status = 0;
        throw wrapped;
      }
      if (response.status !== 200 || !response.body) {
        const payload = await response.json().catch(() => null);
        const code = stringField(payload, "reasonCode") || "SSE_FAILED";
        const detail =
          (payload && payload.detail) || "SSE 订阅失败 HTTP " + response.status;
        const wrapped = new Error(detail);
        wrapped.code = code;
        wrapped.status = response.status;
        throw wrapped;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = "";
      let eventName = "message";
      let eventId = null;
      let dataLines = [];
      try {
        if (typeof onOpen === "function") onOpen();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let nl;
          while ((nl = buffer.indexOf("\n")) >= 0) {
            let line = buffer.slice(0, nl);
            buffer = buffer.slice(nl + 1);
            if (line.endsWith("\r")) line = line.slice(0, -1);
            if (line === "") {
              if (dataLines.length > 0 && typeof onEvent === "function") {
                onEvent(eventName, dataLines.join("\n"), eventId);
              }
              eventName = "message";
              eventId = null;
              dataLines = [];
              continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
              eventName = line.slice(6).trim();
            } else if (line.startsWith("id:")) {
              eventId = line.slice(3).trim();
            } else if (line.startsWith("data:")) {
              let payload = line.slice(5);
              if (payload.startsWith(" ")) payload = payload.slice(1);
              dataLines.push(payload);
            }
          }
        }
        return { aborted: false, completed: true };
      } catch (error) {
        if (controller.signal.aborted) {
          return { aborted: true };
        }
        const wrapped = new Error("SSE 连接中断");
        wrapped.code = "NETWORK";
        wrapped.status = 0;
        throw wrapped;
      }
    } finally {
      if (externalSignal) {
        externalSignal.removeEventListener("abort", onAbort);
      }
    }
  })();
  return {
    close() {
      controller.abort();
    },
    promise,
  };
}

async function get(path, options) {
  let response;
  try {
    response = await fetch(path, {
      method: "GET",
      headers: { Accept: "application/json" },
      signal: options && options.signal,
    });
  } catch (error) {
    if (error && error.name === "AbortError") {
      return aborted();
    }
    return failed(0, "NETWORK", "无法连接服务");
  }
  if (response.status === 204) {
    return {
      ok: true,
      status: 204,
      result: "empty",
      conversationId: null,
      turnId: null,
      reply: null,
      toolCalls: [],
      replayed: false,
      items: [],
      hits: [],
      messages: [],
      conversation: null,
      code: "",
      detail: "",
      aborted: false,
    };
  }
  const payload = await response.json().catch(() => null);
  return normalize(response, payload);
}

async function patch(path, body, options) {
  let response;
  try {
    response = await fetch(path, {
      method: "PATCH",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: options && options.signal,
    });
  } catch (error) {
    if (error && error.name === "AbortError") {
      return aborted();
    }
    return failed(0, "NETWORK", "无法连接服务");
  }
  const payload = await response.json().catch(() => null);
  return normalize(response, payload);
}

async function post(path, body, options) {
  let response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: options && options.signal,
    });
  } catch (error) {
    if (error && error.name === "AbortError") {
      return aborted();
    }
    return failed(0, "NETWORK", "无法连接服务");
  }
  const payload = await response.json().catch(() => null);
  return normalize(response, payload);
}

function normalize(response, payload) {
  const result = stringField(payload, "result");
  const code =
    stringField(payload, "reasonCode") || stringField(payload, "code");
  const detail = stringField(payload, "detail") || (response.ok ? "" : "请求失败");
  const conversationId = stringField(payload, "conversationId") || null;
  const turnId = stringField(payload, "turnId") || null;
  const reply = stringField(payload, "reply") || null;
  const replayed = Boolean(payload && payload.replayed === true);
  const toolCalls = normalizeToolCalls(payload && payload.toolCalls);
  const turnStatus = stringField(payload, "status") || null;
  const ok =
    response.ok &&
    (result === "created" ||
      result === "accepted" ||
      result === "ok" ||
      result === "already_exists");
  return {
    ok,
    status: response.status,
    result: result || "error",
    conversationId,
    turnId,
    reply,
    toolCalls,
    replayed,
    turnStatus,
    executionId: stringField(payload, "executionId") || null,
    errorCode: stringField(payload, "errorCode") || null,
    outboxCursor:
      payload && Number.isFinite(payload.outboxCursor) ? payload.outboxCursor : null,
    items: Array.isArray(payload && payload.items) ? payload.items : [],
    hits: Array.isArray(payload && payload.hits) ? payload.hits : [],
    messages: Array.isArray(payload && payload.messages) ? payload.messages : [],
    notices: Array.isArray(payload && payload.notices) ? payload.notices : [],
    conversation: payload && payload.conversation ? payload.conversation : null,
    nextCursor: stringField(payload, "nextCursor") || null,
    nextAfterSeq: payload && typeof payload.nextAfterSeq === "number" ? payload.nextAfterSeq : null,
    deletedCount: payload && typeof payload.deletedCount === "number" ? payload.deletedCount : null,
    skippedBusy: payload && typeof payload.skippedBusy === "number" ? payload.skippedBusy : null,
    deleted: payload && typeof payload.deleted === "number" ? payload.deleted : null,
    code,
    detail,
    aborted: false,
  };
}

function normalizeToolCalls(raw) {
  if (!Array.isArray(raw)) {
    return [];
  }
  const out = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") {
      continue;
    }
    const name = typeof item.name === "string" ? item.name : "";
    if (!name) {
      continue;
    }
    out.push({
      name,
      startedAt: typeof item.startedAt === "string" ? item.startedAt : "",
      finishedAt: typeof item.finishedAt === "string" ? item.finishedAt : "",
      argumentsJson: typeof item.argumentsJson === "string" ? item.argumentsJson : "{}",
      status: typeof item.status === "string" ? item.status : "",
      errorCode: typeof item.errorCode === "string" ? item.errorCode : "",
    });
  }
  return out;
}

function aborted() {
  return {
    ok: false,
    status: 0,
    result: "aborted",
    conversationId: null,
    turnId: null,
    reply: null,
    toolCalls: [],
    replayed: false,
    items: [],
    hits: [],
    messages: [],
    conversation: null,
    nextCursor: null,
    nextAfterSeq: null,
    deletedCount: null,
    skippedBusy: null,
    code: "ABORTED",
    detail: "",
    aborted: true,
  };
}

function failed(status, code, detail) {
  return {
    ok: false,
    status,
    result: "error",
    conversationId: null,
    turnId: null,
    reply: null,
    toolCalls: [],
    replayed: false,
    items: [],
    hits: [],
    messages: [],
    conversation: null,
    nextCursor: null,
    nextAfterSeq: null,
    deletedCount: null,
    skippedBusy: null,
    code,
    detail,
    aborted: false,
  };
}

function stringField(payload, name) {
  if (!payload || typeof payload[name] !== "string") {
    return "";
  }
  return payload[name];
}
