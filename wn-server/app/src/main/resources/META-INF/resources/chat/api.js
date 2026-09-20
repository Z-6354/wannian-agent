const CONVERSATIONS = "/api/conversations";

/**
 * 对话页唯一的 HTTP 出口。页面模块不得自行 fetch。
 * 当前实现调用已有的建会话与接收回合；Agent Loop 接上后只替换本文件里的路径和响应归一，不改页面。
 */

export function createConversation(title) {
  const body = {};
  if (typeof title === "string" && title !== "") {
    body.title = title;
  }
  return post(CONVERSATIONS, body);
}

export function sendTurn(conversationId, text, clientRequestId) {
  return post(CONVERSATIONS + "/" + encodeURIComponent(conversationId) + "/turns", {
    clientRequestId,
    text,
  });
}

async function post(path, body) {
  let response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  } catch (error) {
    return failed(0, "NETWORK", "无法连接服务");
  }
  const payload = await response.json().catch(() => null);
  return normalize(response, payload);
}

function normalize(response, payload) {
  const result = stringField(payload, "result");
  const code = stringField(payload, "reasonCode");
  const detail = stringField(payload, "detail") || (response.ok ? "" : "请求失败");
  const conversationId = stringField(payload, "conversationId") || null;
  const turnId = stringField(payload, "turnId") || null;
  const reply = stringField(payload, "reply") || null;
  const replayed = Boolean(payload && payload.replayed === true);
  const ok = response.ok && (result === "created" || result === "accepted");
  return {
    ok,
    status: response.status,
    result: result || "error",
    conversationId,
    turnId,
    reply,
    replayed,
    code,
    detail,
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
    replayed: false,
    code,
    detail,
  };
}

function stringField(payload, name) {
  if (!payload || typeof payload[name] !== "string") {
    return "";
  }
  return payload[name];
}
