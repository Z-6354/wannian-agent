import { createConversation, sendTurn } from "/chat/api.js?v=20260920p";
import { installMobileNavigation, renderAppNavigation } from "/shell/navigation.js?v=20260920p";

const STORAGE_KEY = "wannian.chat.conversationId";

const transcript = document.querySelector("#transcript");
const statusLine = document.querySelector("#chat-status");
const notice = document.querySelector("#chat-notice");
const form = document.querySelector("#composer");
const draft = document.querySelector("#draft");
const sendButton = document.querySelector("#send");
const newButton = document.querySelector("#new-conversation");
const skipLink = document.querySelector(".skip-link");

renderAppNavigation(document.querySelector("#app-nav"), "chat");
installMobileNavigation();

const state = {
  conversationId: sessionStorage.getItem(STORAGE_KEY) || "",
  messages: [],
  pending: false,
  notice: "",
  noticeKind: "",
};

if (skipLink) {
  skipLink.addEventListener("click", (event) => {
    event.preventDefault();
    transcript.focus();
  });
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  submit();
});

draft.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    form.requestSubmit();
  }
});

newButton.addEventListener("click", () => {
  if (state.pending) {
    return;
  }
  rememberConversation("");
  state.messages = [];
  state.notice = "已开始新会话。下一条消息会创建新的服务端会话。";
  state.noticeKind = "";
  render();
  draft.focus();
});

render();

async function submit() {
  if (state.pending) {
    return;
  }
  const text = draft.value;
  if (text.trim() === "") {
    state.notice = "消息不能为空";
    state.noticeKind = "error";
    render();
    draft.focus();
    return;
  }

  state.pending = true;
  state.notice = "正在发送";
  state.noticeKind = "";
  render();

  const clientRequestId = crypto.randomUUID();
  let sent = await ensureConversationAndSend(text, clientRequestId);
  if (!sent.ok && sent.code === "CONVERSATION_NOT_FOUND") {
    rememberConversation("");
    sent = await ensureConversationAndSend(text, clientRequestId);
  }

  let failure = "";
  if (!sent.ok) {
    failure = sent.detail || "发送失败";
  } else if (!state.messages.some((message) => message.turnId && message.turnId === sent.turnId)) {
      state.messages.push({
        role: "user",
        label: "你",
        text,
        turnId: sent.turnId || "",
        replayed: sent.replayed,
      });
      if (sent.reply) {
        state.messages.push({
          role: "assistant",
          label: "万年",
          text: sent.reply,
          turnId: sent.turnId || "",
          replayed: false,
        });
      }
      if (draft.value === text) {
        draft.value = "";
      }
      if (sent.reply) {
        state.notice = "";
        state.noticeKind = "";
      } else {
        state.notice = sent.detail || "没有拿到模型回复";
        state.noticeKind = "error";
      }
  } else {
      state.notice = "";
      state.noticeKind = "";
  }

  state.pending = false;
  if (failure) {
    state.notice = failure;
    state.noticeKind = "error";
  }
  render();
  draft.focus();
}

async function ensureConversationAndSend(text, clientRequestId) {
  if (!state.conversationId) {
    const created = await createConversation("");
    if (!created.ok || !created.conversationId) {
      return created.conversationId ? created : { ...created, detail: created.detail || "无法创建会话" };
    }
    rememberConversation(created.conversationId);
  }
  return sendTurn(state.conversationId, text, clientRequestId);
}

function rememberConversation(id) {
  state.conversationId = id || "";
  if (state.conversationId) {
    sessionStorage.setItem(STORAGE_KEY, state.conversationId);
  } else {
    sessionStorage.removeItem(STORAGE_KEY);
  }
}

function render() {
  statusLine.textContent = state.conversationId
    ? "当前会话 " + state.conversationId
    : "尚未创建会话。发送后会建立。";

  notice.className = "banner chat-notice" + (state.noticeKind ? " " + state.noticeKind : "");
  notice.textContent = state.notice;

  form.setAttribute("aria-busy", state.pending ? "true" : "false");
  draft.disabled = state.pending;
  sendButton.disabled = state.pending;
  newButton.disabled = state.pending;
  sendButton.textContent = state.pending ? "发送中" : "发送";

  transcript.replaceChildren();
  if (state.messages.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    const line = document.createElement("p");
    line.textContent = "还没有消息。发送后会交给当前启用的模型。刷新后本页不回放历史。";
    empty.append(line);
    transcript.append(empty);
    return;
  }

  const list = document.createElement("ol");
  list.className = "chat-list";
  for (const message of state.messages) {
    const item = document.createElement("li");
    item.className = "chat-message";
    item.dataset.role = message.role;
    const role = document.createElement("p");
    role.className = "chat-role";
    const label = message.label || message.role;
    role.textContent = message.replayed ? label + " · 重复提交" : label;
    const body = document.createElement("p");
    body.className = "chat-text";
    body.textContent = message.text;
    item.append(role, body);
    list.append(item);
  }
  transcript.append(list);
  transcript.scrollTop = transcript.scrollHeight;
}
