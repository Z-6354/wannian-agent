import {
  createConversation,
  sendTurnAsync,
  getTurnStatus,
} from "/chat/api.js?v=20260928d";
import {
  createChatState,
  selectConversation,
  rememberConversationId,
  readRememberedConversationId,
  setConversationHeader,
  appendOptimisticUser,
  upsertConversation,
  applyStreamEvent,
  findRecoverableTurnIds,
  ensureInflightAssistant,
} from "/chat/state.js?v=20260928d";
import { bootstrapRecent, openConversation, refreshHistoryTail } from "/chat/history.js?v=20260928d";
import { createStreamController } from "/chat/stream.js?v=20260928d";
import { createRenderer } from "/chat/render.js?v=20260928d";
import { createSidebar } from "/chat/sidebar.js?v=20260925r";
import { createQueueController } from "/chat/queue.js?v=20260925n";
import { createComposerController } from "/chat/composer.js?v=20260926c";
import { createTitleController } from "/chat/title.js?v=20260925i";
import { createArchiveNoticeController } from "/chat/notices.js?v=20260925i";
import { createTaskReviewController } from "/chat/task-review.js?v=20260927a";
import { createTaskSidebar } from "/chat/task-sidebar.js?v=20260928d";

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
  stopButton: document.querySelector("#stop-turn"),
  newButton: document.querySelector("#new-conversation"),
  skipLink: document.querySelector(".skip-link"),
  conversationList: document.querySelector("#conversation-list"),
  searchInput: document.querySelector("#conversation-search"),
  listStatusActive: document.querySelector("#list-status-active"),
  listStatusArchived: document.querySelector("#list-status-archived"),
  listStatusTrashed: document.querySelector("#list-status-trashed"),
  emptyTrashBtn: document.querySelector("#empty-trash"),
  emptyArchiveBtn: document.querySelector("#empty-archive"),
  renameBtn: document.querySelector("#rename-conversation"),
  archiveBtn: document.querySelector("#archive-conversation"),
  unarchiveBtn: document.querySelector("#unarchive-conversation"),
  trashBtn: document.querySelector("#trash-conversation"),
  restoreBtn: document.querySelector("#restore-conversation"),
  composerToolbar: document.querySelector("#composer-toolbar"),
  archiveNotices: document.querySelector("#archive-notices"),
  taskSidebarHost: document.querySelector("#chat-task-sidebar"),
};

if (!els.transcript || !els.form || !els.draft) {
  return;
}

const taskReviewHost = document.createElement("div");
taskReviewHost.id = "task-review-host";
taskReviewHost.className = "chat-task-review-host";
taskReviewHost.hidden = true;
els.transcript.parentElement.insertBefore(taskReviewHost, els.transcript);

const taskReviews = createTaskReviewController({
  hostEl: taskReviewHost,
  getConversationId: () => state.activeConversationId,
  onChanged: () => {
    refreshHistoryTail(
      state,
      state.activeConversationId,
      streamAbort ? streamAbort.signal : undefined
    ).then(() => paint());
    if (taskSidebar) taskSidebar.refresh();
  },
});

const taskSidebar = els.taskSidebarHost
  ? createTaskSidebar({
      hostEl: els.taskSidebarHost,
      getConversationId: () => state.activeConversationId,
      setNotice: (text) => {
        setNotice(text || "");
        paint();
      },
    })
  : null;

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
  onTaskDeliveryClick: (taskId) => {
    if (taskSidebar && taskId) {
      taskSidebar.focusTask(taskId);
    }
  },
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
        if (taskSidebar) taskSidebar.refresh();
        paint();
        composer.paint();
      }
    );
  },
});

const archiveNotices = createArchiveNoticeController({
  root: els.archiveNotices,
  setNotice,
  onChanged: () => sidebar.refreshList().then(() => paint()),
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
  refreshArchiveNotices: () => archiveNotices.refresh(),
});

els.form.addEventListener("submit", (event) => {
  event.preventDefault();
  submit();
});

const DRAFT_MIN_PX = 40;
const DRAFT_MAX_PX = 160;

function fitDraftHeight() {
  const el = els.draft;
  if (!el) return;
  el.style.height = "auto";
  const next = Math.min(DRAFT_MAX_PX, Math.max(DRAFT_MIN_PX, el.scrollHeight));
  el.style.height = `${next}px`;
}

els.draft.addEventListener("input", () => {
  fitDraftHeight();
});

els.draft.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    els.form.requestSubmit();
  }
});

fitDraftHeight();

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
  await recoverActiveTurns(id, epoch);
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
  await recoverActiveTurns(conversationId, epoch);
  await taskReviews.refresh();
  if (taskSidebar) await taskSidebar.refresh();
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

/** 虚线附带点击 → 侧栏高亮任务（2.5.9 U6）。 */
export function focusBackgroundTask(taskId) {
  if (taskSidebar && taskId) {
    return taskSidebar.focusTask(taskId);
  }
  return Promise.resolve();
}

// 供 render 回调（同模块闭包）
function onTaskDeliveryClick(taskId) {
  focusBackgroundTask(taskId);
}

/**
 * 切回会话时：对「有用户句、尚无正式助手句」的 turn 拉状态，恢复临时气泡 + Stop + 轮询。
 */
async function recoverActiveTurns(conversationId, epoch) {
  if (!conversationId || !isEpochCurrent(epoch, conversationId)) return;
  const turnIds = findRecoverableTurnIds(state);
  for (const turnId of turnIds) {
    if (!isEpochCurrent(epoch, conversationId)) return;
    const status = await getTurnStatus(conversationId, turnId);
    if (!isEpochCurrent(epoch, conversationId) || status.aborted || !status.ok) continue;
    const terminal = String(status.turnStatus || status.status || "").toUpperCase();
    if (
      terminal === "COMPLETED" ||
      terminal === "FAILED" ||
      terminal === "CANCELLED"
    ) {
      continue;
    }
    ensureInflightAssistant(state, turnId, terminal);
    queue.onTurnAccepted({
      turnId,
      status: terminal,
      conversationId,
    });
    if (terminal === "RUNNING" || terminal === "COMMITTING") {
      queue.onTurnStarted(turnId);
      if (terminal === "COMMITTING") queue.markCommitting(turnId);
    }
    void watchTurnUntilTerminal(conversationId, turnId, epoch);
  }
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
  await dispatchTurn(text, crypto.randomUUID(), { clearDraft: true });
}

async function dispatchTurn(text, clientRequestId, options = {}) {
  const clearDraft = options.clearDraft !== false;
  const noticeOnSend = options.notice || "已发送";
  if (state.submitting) {
    return;
  }
  state.submitting = true;
  setNotice(noticeOnSend, "");
  paintChrome();

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
    if (clearDraft && els.draft.value === text) {
      els.draft.value = "";
      fitDraftHeight();
    }
    // 无论是否已有 AbortController，都确保 SSE 在跑（放弃重连后旧 controller 仍在会导致永不重启）
    startStream(conversationId, state.selectionEpoch);
    // SSE 丢事件时仍能靠轮询把终态与正文拉回来
    void watchTurnUntilTerminal(conversationId, turnId, state.selectionEpoch);
    await sidebar.refreshList();
  } finally {
    state.submitting = false;
    paint({ forceScroll: state.scrollPinnedBottom });
    composer.paint();
    els.draft.focus();
  }
}

/**
 * 异步 receive 后有界轮询 turn 状态；终态则校准历史。
 * 不依赖 SSE 是否连通——避免「后端已完成、气泡一直空」。
 */
async function watchTurnUntilTerminal(conversationId, turnId, epoch) {
  if (!conversationId || !turnId) return;
  for (let i = 0; i < 90; i++) {
    await sleep(700);
    if (!isEpochCurrent(epoch, conversationId)) return;
    const status = await getTurnStatus(conversationId, turnId);
    if (!isEpochCurrent(epoch, conversationId) || status.aborted) return;
    if (!status.ok) continue;
    const terminal = String(status.turnStatus || status.status || "").toUpperCase();
    if (
      terminal !== "COMPLETED" &&
      terminal !== "FAILED" &&
      terminal !== "CANCELLED"
    ) {
      continue;
    }
    queue.onTurnTerminal(turnId, terminal);
    applyStreamEvent(
      state,
      conversationId,
      terminal === "COMPLETED"
        ? "turn.completed"
        : terminal === "FAILED"
          ? "turn.failed"
          : "turn.cancelled",
      {
        turnId,
        errorCode: status.errorCode || "",
      },
      null
    );
    await refreshHistoryTail(state, conversationId, streamAbort ? streamAbort.signal : undefined);
    if (!isEpochCurrent(epoch, conversationId)) return;
    await taskReviews.refresh();
    if (taskSidebar) await taskSidebar.refresh();
    if (state.inflightByTurnId[turnId]) {
      delete state.inflightByTurnId[turnId];
    }
    if (state.streamNotice && state.streamNotice.indexOf("连接中断") === 0) {
      state.streamNotice = "";
    }
    setNotice("");
    paint({ forceScroll: state.scrollPinnedBottom });
    composer.paint();
    return;
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
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
  /* Stop 可见时由 composer.paint 隐藏发送按钮；此处只更新发送态文案 */
  if (!els.sendButton.hidden) {
    els.sendButton.textContent = state.submitting ? "发送中" : "发送";
  }
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
