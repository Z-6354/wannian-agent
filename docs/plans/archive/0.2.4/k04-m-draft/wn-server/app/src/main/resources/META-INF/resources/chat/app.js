import {
  createConversation,
  sendTurnAsync,
} from "/chat/api.js?v=20260924g2";
import {
  createChatState,
  selectConversation,
  rememberConversationId,
  readRememberedConversationId,
  setConversationHeader,
  appendOptimisticUser,
  upsertConversation,
} from "/chat/state.js?v=20260925e";
import { bootstrapRecent, openConversation, refreshHistoryTail } from "/chat/history.js?v=20260924e";
import { createStreamController } from "/chat/stream.js?v=20260925e";
import { createRenderer } from "/chat/render.js?v=20260925m2";
import { createSidebar } from "/chat/sidebar.js?v=20260924g3";
import { createQueueController } from "/chat/queue.js?v=20260924e";
import { createComposerController } from "/chat/composer.js?v=20260924e";
import { createTitleController } from "/chat/title.js?v=20260924e";
import { createArchiveNoticeController } from "/chat/notices.js?v=20260924g2";

/**
 * 对话面板：由 /shell/app.js 挂载；侧栏会话操作会 ensureView("chat")。
 * @param {{ ensureView?: (id: string) => void }} [options]
 */
export function startChatApp(options = {}) {
  const ensureView = typeof options.ensureView === "function" ? options.ensureView : () => {};

const state = createChatState();
let fetchAbort = null;
let streamAbort = null;

const els = {
  transcript: document.querySelector("#transcript"),
  title: document.querySelector("#conversation-title"),
  subtitle: document.querySelector("#conversation-subtitle"),
  notice: document.querySelector("#chat-notice"),
  form: document.querySelector("#composer"),
  draft: document.querySelector("#draft"),
  sendButton: document.querySelector("#send"),
  newButton: document.querySelector("#new-conversation"),
  skipLink: document.querySelector(".skip-link"),
  conversationList: document.querySelector("#conversation-list"),
  searchInput: document.querySelector("#conversation-search"),
  listStatusActive: document.querySelector("#list-status-active"),
  listStatusArchived: document.querySelector("#list-status-archived"),
  listStatusTrashed: document.querySelector("#list-status-trashed"),
  emptyTrashBtn: document.querySelector("#empty-trash"),
  renameBtn: document.querySelector("#rename-conversation"),
  archiveBtn: document.querySelector("#archive-conversation"),
  unarchiveBtn: document.querySelector("#unarchive-conversation"),
  trashBtn: document.querySelector("#trash-conversation"),
  restoreBtn: document.querySelector("#restore-conversation"),
  composerToolbar: document.querySelector("#composer-toolbar"),
  archiveNotices: document.querySelector("#archive-notices"),
};

if (!els.transcript || !els.form || !els.draft) {
  return;
}

const queue = createQueueController();

let sidebar;

const titleCtrl = createTitleController({
  getState: () => state,
  isEpochCurrent,
  onTitleApplied(summary) {
    setConversationHeader(state, summary);
    upsertConversation(state, summary);
    if (Array.isArray(state.listItems)) {
      const idx = state.listItems.findIndex((item) => item.id === summary.id);
      if (idx >= 0) {
        state.listItems[idx] = { ...state.listItems[idx], ...summary };
      }
    }
    if (sidebar) sidebar.renderList();
    paintChrome();
  },
  setNotice,
});

const renderer = createRenderer({
  transcriptEl: els.transcript,
  state,
});

const composer = createComposerController({
  els,
  getState: () => state,
  queue,
  isEpochCurrent,
  setNotice,
  requestRender: () => paint(),
});

const stream = createStreamController({
  getState: () => state,
  isEpochCurrent,
  queue,
  title: titleCtrl,
  onComposerPaint: () => composer.paint(),
  onChange() {
    paint({ fromStream: true });
  },
  onRefetch(conversationId, epoch) {
    if (!isEpochCurrent(epoch, conversationId)) return;
    refreshHistoryTail(state, conversationId, streamAbort ? streamAbort.signal : undefined).then(
      () => {
        if (!isEpochCurrent(epoch, conversationId)) return;
        paint();
        composer.paint();
      }
    );
  },
});

sidebar = createSidebar({
  state,
  els,
  beginSelection,
  openSelected,
  requestRender: () => paint(),
  setNotice,
  titleCtrl,
  busyHint: "请先停止或等待队列完成",
});

const archiveNotices = createArchiveNoticeController({
  root: els.archiveNotices,
  setNotice,
  onChanged: () => sidebar.refreshList().then(() => paint()),
});

els.form.addEventListener("submit", (event) => {
  event.preventDefault();
  submit();
});

els.draft.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    els.form.requestSubmit();
  }
});

els.newButton.addEventListener("click", async () => {
  ensureView("chat");
  await sidebar.createNew();
  els.draft.focus();
});

bootstrap();

async function bootstrap() {
  paint();
  composer.paint();
  await archiveNotices.refresh();
  const epochAtStart = state.selectionEpoch;
  await sidebar.refreshList();
  // 列表刷新期间用户若已点选会话，勿再强制 remembered/recent 覆盖
  if (state.selectionEpoch !== epochAtStart || state.activeConversationId) {
    paint();
    return;
  }
  const remembered = readRememberedConversationId();
  if (remembered) {
    const opened = await openSelected(remembered);
    if (opened) return;
    if (state.selectionEpoch !== epochAtStart || state.activeConversationId) {
      paint();
      return;
    }
  }
  const recent = await withSelectionFetch((signal, epoch) => bootstrapRecent(state, signal));
  if (state.selectionEpoch !== epochAtStart || state.activeConversationId) {
    paint();
    return;
  }
  if (!recent || recent.aborted) {
    paint();
    return;
  }
  if (recent.empty) {
    beginSelection("");
    rememberConversationId("");
    setNotice("");
    paint();
    return;
  }
  if (!recent.ok) {
    setNotice(recent.detail || "无法恢复最近会话", "error");
    paint();
    return;
  }
  const id = recent.conversation.id;
  const epoch = beginSelection(id);
  rememberConversationId(id);
  setConversationHeader(state, recent.conversation);
  state.outboxCursor = safeOutboxCursor(recent.outboxCursor);
  startStream(id, epoch);
  setNotice("");
  paint({ forceScroll: true });
  composer.paint();
  sidebar.renderList();
  sidebar.updateHeaderActions();
}

async function openSelected(conversationId, opts = {}) {
  ensureView("chat");
  if (!conversationId) {
    beginSelection("");
    rememberConversationId("");
    paint();
    composer.paint();
    return false;
  }
  const epoch = beginSelection(conversationId);
  rememberConversationId(conversationId);
  const result = await withSelectionFetch((signal) => openConversation(state, conversationId, signal));
  if (!isEpochCurrent(epoch, conversationId)) {
    return false;
  }
  if (!result || result.aborted) {
    return false;
  }
  if (!result.ok) {
    setNotice(result.detail || "无法打开会话", "error");
    beginSelection("");
    rememberConversationId("");
    paint();
    composer.paint();
    sidebar.renderList();
    return false;
  }
  state.outboxCursor = safeOutboxCursor(result.outboxCursor);
  startStream(conversationId, epoch);
  setNotice("");
  paint({ forceScroll: true });
  composer.paint();
  sidebar.renderList();
  sidebar.updateHeaderActions();
  if (opts.messageId) {
    renderer.scrollToMessage(opts.messageId);
  }
  return true;
}

function beginSelection(conversationId) {
  teardownNetwork();
  const epoch = selectConversation(state, conversationId);
  queue.reset(conversationId || "");
  if (!conversationId) {
    setConversationHeader(state, null);
  }
  composer.paint();
  return epoch;
}

function teardownNetwork() {
  stream.stop();
  if (fetchAbort) {
    fetchAbort.abort();
    fetchAbort = null;
  }
  if (streamAbort) {
    streamAbort.abort();
    streamAbort = null;
  }
}

function startStream(conversationId, epoch) {
  if (streamAbort) streamAbort.abort();
  streamAbort = new AbortController();
  stream.start(conversationId, epoch, streamAbort.signal);
}

async function withSelectionFetch(fn) {
  if (fetchAbort) fetchAbort.abort();
  fetchAbort = new AbortController();
  const signal = fetchAbort.signal;
  const epoch = state.selectionEpoch;
  const conversationId = state.activeConversationId;
  const result = await fn(signal, epoch);
  if (signal.aborted) {
    return { aborted: true };
  }
  if (conversationId && !isEpochCurrent(epoch, conversationId) && conversationId !== "") {
    /* selection changed mid-flight; caller checks epoch */
  }
  return result;
}

function isEpochCurrent(epoch, conversationId) {
  return state.selectionEpoch === epoch && state.activeConversationId === conversationId;
}

function safeOutboxCursor(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

async function submit() {
  ensureView("chat");
  const text = els.draft.value;
  if (text.trim() === "") {
    setNotice("消息不能为空", "error");
    paint();
    els.draft.focus();
    return;
  }
  if (state.submitting) {
    return;
  }
  state.submitting = true;
  setNotice("已发送", "");
  paintChrome();

  const clientRequestId = crypto.randomUUID();
  const epochAtSend = state.selectionEpoch;
  let conversationId = state.activeConversationId;

  try {
    if (!conversationId) {
      const created = await createConversation("");
      if (!created.ok || !created.conversationId) {
        setNotice(created.detail || "无法创建会话", "error");
        return;
      }
      conversationId = created.conversationId;
      if (state.selectionEpoch !== epochAtSend) return;
      beginSelection(conversationId);
      rememberConversationId(conversationId);
      setConversationHeader(state, {
        id: conversationId,
        title: "新会话",
        titleSource: "AUTO",
        revision: 0,
        status: "ACTIVE",
      });
      await sidebar.refreshList();
      startStream(conversationId, state.selectionEpoch);
    }

    let sent = await sendTurnAsync(conversationId, text, clientRequestId);
    if (!sent.ok && sent.code === "CONVERSATION_NOT_FOUND") {
      rememberConversationId("");
      const created = await createConversation("");
      if (!created.ok || !created.conversationId) {
        setNotice(created.detail || "无法创建会话", "error");
        return;
      }
      conversationId = created.conversationId;
      beginSelection(conversationId);
      rememberConversationId(conversationId);
      startStream(conversationId, state.selectionEpoch);
      sent = await sendTurnAsync(conversationId, text, clientRequestId);
    }

    if (!sent.ok) {
      if (sent.code === "CONVERSATION_BUSY") {
        setNotice("请先停止或等待队列完成", "error");
      } else {
        setNotice(sent.detail || "发送失败", "error");
      }
      return;
    }
    if (state.activeConversationId !== conversationId) {
      return;
    }

    const turnStatus = String(sent.turnStatus || sent.status || "RECEIVED").toUpperCase();
    const turnId = sent.turnId || clientRequestId;
    queue.onTurnAccepted({
      turnId,
      status: turnStatus,
      replayed: sent.replayed,
      conversationId,
    });

    // 幂等回放：不重复插 optimistic
    if (sent.replayed) {
      if (!streamAbort) {
        startStream(conversationId, state.selectionEpoch);
      }
      setNotice("已恢复同一请求", "");
      return;
    }

    const hasRunning = Boolean(queue.getRunningTurnId()) && queue.getRunningTurnId() !== turnId;
    const showQueued = queue.getQueuedTurnIds().includes(turnId) || (hasRunning && (turnStatus === "RECEIVED" || turnStatus === "CLAIMED"));

    appendOptimisticUser(
      state,
      conversationId,
      text,
      turnId,
      showQueued ? "queued" : "running"
    );
    if (showQueued) {
      setNotice("已排队", "");
    } else {
      setNotice("");
    }
    if (els.draft.value === text) {
      els.draft.value = "";
    }
    if (!streamAbort) {
      startStream(conversationId, state.selectionEpoch);
    }
    await sidebar.refreshList();
  } finally {
    state.submitting = false;
    paint({ forceScroll: state.scrollPinnedBottom });
    composer.paint();
    els.draft.focus();
  }
}

function setNotice(text, kind) {
  state.notice = text || "";
  state.noticeKind = kind || "";
}

function paint(options = {}) {
  paintChrome();
  renderer.render(options);
  sidebar.updateHeaderActions();
  composer.paint();
}

function paintChrome() {
  const title = state.header.title || (state.activeConversationId ? "未命名会话" : "无会话");
  els.title.textContent = title;
  if (els.subtitle) {
    els.subtitle.textContent = state.activeConversationId
      ? shortId(state.activeConversationId)
      : "发送后会建立新会话";
  }

  const banner = state.streamNotice || state.notice;
  const kind = state.streamNotice ? "" : state.noticeKind;
  els.notice.className = "banner chat-notice" + (kind ? " " + kind : "");
  els.notice.textContent = banner;

  els.form.setAttribute("aria-busy", state.submitting ? "true" : "false");
  els.sendButton.textContent = state.submitting ? "发送中" : "发送";
  /* E：A 运行中仍可发 B；仅提交中短暂禁用发送按钮 */
  els.draft.disabled = false;
  els.sendButton.disabled = state.submitting;
  els.newButton.disabled = false;
}

function shortId(id) {
  if (!id || id.length < 8) return id || "";
  return id.slice(0, 8);
}

} // startChatApp
