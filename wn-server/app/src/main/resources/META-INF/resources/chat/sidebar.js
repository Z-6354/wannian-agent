import {
  listConversations,
  searchConversations,
  patchConversation,
  emptyTrash,
  emptyArchive,
  purgeEmptyConversations,
} from "/chat/api.js?v=20260928n";
import {
  upsertConversation,
  setConversationHeader,
  selectConversation,
  rememberConversationId,
} from "/chat/state.js?v=20260928n";
import { confirmDestructive, confirmDialog } from "/shell/dialog.js?v=20260925r";

const TITLE_MAX_LEN = 80;

/**
 * 会话侧栏：列表 / 搜索 / 归档 / 回收站；双击标题就地改名（对齐 DSH TerminalTitle）。
 */
export function createSidebar({
  state,
  els,
  beginSelection,
  openSelected,
  requestRender,
  setNotice,
  titleCtrl,
  busyHint,
  refreshArchiveNotices,
}) {
  let searchTimer = null;
  let listGeneration = 0;
  let searchGeneration = 0;

  els.searchInput.addEventListener("input", () => {
    const q = els.searchInput.value.trim();
    state.searchQuery = q;
    searchGeneration += 1;
    if (searchTimer) clearTimeout(searchTimer);
    state.searchHits = [];
    if (!q) {
      renderList();
      return;
    }
    els.conversationList.replaceChildren();
    const generation = searchGeneration;
    searchTimer = setTimeout(() => runSearch(q, generation), 280);
  });

  els.listStatusActive.addEventListener("click", () => switchList("ACTIVE"));
  els.listStatusArchived.addEventListener("click", () => switchList("ARCHIVED"));
  els.listStatusTrashed.addEventListener("click", () => switchList("TRASHED"));

  if (els.emptyTrashBtn) {
    els.emptyTrashBtn.addEventListener("click", () => confirmEmptyTrash());
  }
  if (els.emptyArchiveBtn) {
    els.emptyArchiveBtn.addEventListener("click", () => confirmEmptyArchive());
  }

  if (els.renameBtn) {
    // 改名改为双击标题；按钮保留 DOM 兼容，始终隐藏。
    els.renameBtn.hidden = true;
  }

  if (els.title) {
    els.title.title = "双击改名";
    els.title.classList.add("is-renameable");
    els.title.addEventListener("dblclick", (event) => {
      event.preventDefault();
      event.stopPropagation();
      beginHeaderRename();
    });
  }

  if (els.archiveBtn) {
    els.archiveBtn.addEventListener("click", () => lifecycleActive("archive"));
  }

  if (els.unarchiveBtn) {
    els.unarchiveBtn.addEventListener("click", () => lifecycleActive("unarchive"));
  }

  if (els.trashBtn) {
    els.trashBtn.addEventListener("click", () => lifecycleActive("trash"));
  }

  if (els.restoreBtn) {
    els.restoreBtn.addEventListener("click", () => lifecycleActive("restore"));
  }

  async function refreshList(signal) {
    const status = state.listStatus || "ACTIVE";
    const generation = ++listGeneration;
    const res = await listConversations(status, null, 50, { signal });
    if (generation !== listGeneration || status !== state.listStatus) return;
    if (res.aborted) return;
    if (!res.ok) {
      setNotice(res.detail || "无法加载会话列表", "error");
      requestRender();
      return;
    }
    state.listItems = res.items || [];
    for (const item of state.listItems) {
      upsertConversation(state, item);
    }
    // Do not repaint the search panel from an unrelated list refresh while a
    // query is active or waiting for its debounced request.
    if (!state.searchQuery) renderList();
  }

  async function runSearch(q, generation = ++searchGeneration) {
    if (!q) {
      state.searchHits = [];
      if (generation === searchGeneration) renderList();
      return;
    }
    const statusAtStart = state.listStatus;
    const status = state.listStatus === "TRASHED" ? "TRASHED" : undefined;
    const res = await searchConversations(q, status, null, 30);
    if (
      generation !== searchGeneration ||
      q !== state.searchQuery ||
      statusAtStart !== state.listStatus
    ) {
      return;
    }
    if (res.aborted) return;
    if (!res.ok) {
      setNotice(res.detail || "搜索失败", "error");
      requestRender();
      return;
    }
    state.searchHits = res.hits || [];
    renderList();
  }

  function switchList(status) {
    listGeneration += 1;
    searchGeneration += 1;
    if (searchTimer) {
      clearTimeout(searchTimer);
      searchTimer = null;
    }
    state.listStatus = status;
    state.searchQuery = "";
    state.searchHits = [];
    if (els.searchInput) els.searchInput.value = "";
    state.listItems = [];
    els.conversationList.replaceChildren();
    updateListChrome();
    refreshList();
  }

  function updateListChrome() {
    const status = state.listStatus || "ACTIVE";
    setPressed(els.listStatusActive, status === "ACTIVE");
    setPressed(els.listStatusArchived, status === "ARCHIVED");
    setPressed(els.listStatusTrashed, status === "TRASHED");
    if (els.emptyTrashBtn) {
      els.emptyTrashBtn.hidden = status !== "TRASHED";
    }
    if (els.emptyArchiveBtn) {
      els.emptyArchiveBtn.hidden = status !== "ARCHIVED";
    }
  }

  function renderList() {
    updateListChrome();
    const host = els.conversationList;
    host.replaceChildren();
    const searching = Boolean(state.searchQuery);
    if (searching) {
      if (!state.searchHits.length) {
        host.append(emptyLine("无匹配会话"));
        return;
      }
      for (const hit of state.searchHits) {
        host.append(hitRow(hit));
      }
      return;
    }
    if (!state.listItems.length) {
      host.append(
        emptyLine(
          state.listStatus === "TRASHED"
            ? "回收站为空"
            : state.listStatus === "ARCHIVED"
              ? "没有已归档会话"
              : "还没有会话"
        )
      );
      return;
    }
    for (const item of state.listItems) {
      host.append(conversationRow(item));
    }
  }

  function conversationRow(item) {
    return buildConversationRow(item, item.id, false);
  }

  function buildConversationRow(item, conversationId, isHit) {
    const row = document.createElement("div");
    row.className = "conv-row";
    row.setAttribute("role", "listitem");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "conv-item" + (isHit ? " conv-hit" : "");
    if (conversationId === state.activeConversationId) {
      btn.classList.add("is-active");
      btn.setAttribute("aria-current", "true");
      row.classList.add("is-active");
    }
    const title = document.createElement("span");
    title.className = "conv-item-title";
    title.textContent = item.title || "未命名会话";
    if (!isHit && (item.status || state.listStatus) !== "TRASHED") {
      title.classList.add("is-renameable");
      title.title = "双击改名";
      title.addEventListener("dblclick", (event) => {
        event.preventDefault();
        event.stopPropagation();
        beginRowRename(title, item, conversationId);
      });
    }
    btn.append(title);
    if (isHit) {
      const snippet = document.createElement("span");
      snippet.className = "conv-item-snippet";
      snippet.textContent = item.snippet || item.matchSource || "";
      btn.append(snippet);
    } else {
      const meta = document.createElement("span");
      meta.className = "conv-item-meta";
      meta.textContent = formatActivity(item.lastActivityAt || item.updatedAt);
      btn.append(meta);
    }
    btn.addEventListener("click", () => selectRow(conversationId, isHit && item.messageId ? { messageId: item.messageId } : {}));
    // 搜索命中没有完整会话 revision/status，保持只读选择，避免用伪 revision 发起变更。
    row.append(btn);
    if (!isHit) row.append(rowActions(item, conversationId));
    return row;
  }

  function hitRow(hit) {
    return buildConversationRow(hit, hit.conversationId, true);
  }

  function rowActions(item, conversationId) {
    const actions = document.createElement("span");
    actions.className = "conv-row-actions";
    actions.setAttribute("aria-label", "会话操作");
    const status = item.status || state.listStatus || "ACTIVE";
    if (status === "ACTIVE") {
      const pin = actionButton(item.pinned ? "取消置顶" : "置顶", item.pinned ? "unpin" : "pin");
      pin.addEventListener("click", (event) => mutateRow(event, conversationId, item, pin.dataset.op));
      actions.append(pin);
    }
    if (status === "ACTIVE" || status === "ARCHIVED") {
      const lifecycle = actionButton(status === "ACTIVE" ? "归档" : "取消归档", status === "ACTIVE" ? "archive" : "unarchive");
      lifecycle.addEventListener("click", (event) => mutateRow(event, conversationId, item, lifecycle.dataset.op));
      actions.append(lifecycle);
    }
    return actions;
  }

  function actionButton(label, op) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "conv-action";
    button.dataset.op = op;
    button.setAttribute("aria-label", label);
    button.title = label;
    button.textContent = op === "pin" ? "☆" : op === "unpin" ? "★" : "归";
    return button;
  }

  async function mutateRow(event, id, item, op) {
    event.preventDefault();
    event.stopPropagation();
    if (op === "archive" || op === "unarchive") {
      const ok = await confirmDialog({
        title: op === "archive" ? "归档会话" : "取消归档",
        message: op === "archive" ? "归档此会话？" : "取消归档并恢复为进行中？",
        confirmLabel: "确定",
        trigger: event.currentTarget,
      });
      if (!ok) return;
    }
    const known = Number.isInteger(item.revision)
      ? item
      : (state.listItems || []).find((entry) => entry.id === id);
    const expectedRevision = known && Number.isInteger(known.revision) ? known.revision : 0;
    const res = await patchConversation(id, op, expectedRevision);
    if (!res.ok) {
      setNotice(res.detail || mutationHint(res.code, busyHint), "error");
      await refreshList();
      requestRender();
      return;
    }
    if (res.conversation) {
      upsertConversation(state, res.conversation);
      if (id === state.activeConversationId) {
        setConversationHeader(state, res.conversation);
      }
    }
    if ((op === "archive" || op === "unarchive") && id === state.activeConversationId && op === "archive") {
      beginSelection("");
      rememberConversationId("");
      setConversationHeader(state, null);
    }
    setNotice(op === "archive" ? "已归档" : op === "unarchive" ? "已取消归档" : op === "pin" ? "已置顶" : "已取消置顶", "");
    await refreshList();
    requestRender();
  }

  async function selectRow(id, opts = {}) {
    await openSelected(id, opts);
  }

  async function createNew() {
    searchGeneration += 1;
    if (searchTimer) {
      clearTimeout(searchTimer);
      searchTimer = null;
    }
    state.searchQuery = "";
    state.searchHits = [];
    if (els.searchInput) els.searchInput.value = "";
    // 清理历史空会话；本地点「新会话」不落库，首条发送时再 create
    try {
      await purgeEmptyConversations();
    } catch (_) {
      /* ignore */
    }
    beginSelection("");
    rememberConversationId("");
    setConversationHeader(state, null);
    state.listStatus = "ACTIVE";
    setNotice("");
    await refreshList();
    requestRender();
    return null;
  }

  function beginHeaderRename() {
    const id = state.activeConversationId;
    if (!id || !els.title) return;
    if ((state.header.status || "") === "TRASHED") return;
    startInlineTitleEdit({
      hostEl: els.title,
      displayText: state.header.title || els.title.textContent || "",
      onCommit: (next) => commitRename(id, next, state.header.revision),
    });
  }

  function beginRowRename(titleEl, item, conversationId) {
    if (!titleEl || !conversationId || !item) return;
    const known = Number.isInteger(item.revision)
      ? item
      : (state.listItems || []).find((entry) => entry.id === conversationId);
    const expectedRevision =
      known && Number.isInteger(known.revision)
        ? known.revision
        : conversationId === state.activeConversationId
          ? state.header.revision
          : null;
    if (!Number.isInteger(expectedRevision)) {
      setNotice("无法改名：缺少会话版本，请刷新后再试", "error");
      return;
    }
    startInlineTitleEdit({
      hostEl: titleEl,
      displayText: item.title || titleEl.textContent || "",
      onCommit: (next) => commitRename(conversationId, next, expectedRevision),
    });
  }

  async function commitRename(id, title, expectedRevision) {
    const res = await patchConversation(id, "rename", expectedRevision, title);
    if (!res.ok) {
      setNotice(res.detail || mutationHint(res.code, busyHint), "error");
      await refreshList();
      requestRender();
      return false;
    }
    if (res.conversation) {
      if (titleCtrl) {
        titleCtrl.markManualTitle(
          id,
          res.conversation.title || title,
          res.conversation.revision
        );
      }
      upsertConversation(state, {
        ...res.conversation,
        titleSource: res.conversation.titleSource || "MANUAL",
      });
      if (id === state.activeConversationId) {
        setConversationHeader(state, {
          ...res.conversation,
          titleSource: res.conversation.titleSource || "MANUAL",
        });
      }
    } else if (id === state.activeConversationId) {
      if (titleCtrl) {
        titleCtrl.markManualTitle(id, title, state.header.revision + 1);
      }
      state.header.title = title;
      state.header.titleSource = "MANUAL";
      state.header.revision += 1;
    }
    setNotice("");
    await refreshList();
    requestRender();
    return true;
  }

  async function lifecycleActive(op) {
    const id = state.activeConversationId;
    if (!id) return;
    const copy = {
      archive: { title: "归档会话", message: "归档此会话？", trigger: els.archiveBtn },
      unarchive: { title: "取消归档", message: "取消归档并恢复为进行中？", trigger: els.unarchiveBtn },
      trash: { title: "移入回收站", message: "移入回收站？", trigger: els.trashBtn, danger: true },
      restore: { title: "恢复会话", message: "从回收站恢复？", trigger: els.restoreBtn },
    };
    const spec = copy[op] || { title: "确认操作", message: "确认操作？" };
    const ok = await confirmDialog({
      title: spec.title,
      message: spec.message,
      confirmLabel: "确定",
      danger: !!spec.danger,
      trigger: spec.trigger,
    });
    if (!ok) return;
    const res = await patchConversation(id, op, state.header.revision);
    if (!res.ok) {
      setNotice(res.detail || mutationHint(res.code, busyHint), "error");
      return;
    }
    if (res.conversation) {
      setConversationHeader(state, res.conversation);
      upsertConversation(state, res.conversation);
    }
    if (op === "trash" || op === "archive") {
      beginSelection("");
      rememberConversationId("");
      setConversationHeader(state, null);
      setNotice(op === "trash" ? "已移入回收站" : "已归档");
    } else {
      setNotice(op === "restore" ? "已恢复" : "已取消归档");
    }
    await refreshList();
    requestRender();
  }

  async function confirmEmptyTrash() {
    const ok = await confirmDestructive({
      title: "清空回收站",
      message: "清空回收站将永久删除其中的会话与消息，且不可恢复。确定继续？",
      secondMessage: "再次确认：不可恢复。清空回收站？",
      confirmLabel: "继续",
      secondConfirmLabel: "清空回收站",
      trigger: els.emptyTrashBtn,
    });
    if (!ok) return;
    const res = await emptyTrash("EMPTY_TRASH");
    if (!res.ok) {
      setNotice(res.detail || "清空失败", "error");
      return;
    }
    const deleted = res.deletedCount != null ? res.deletedCount : 0;
    const skipped = res.skippedBusy != null ? res.skippedBusy : 0;
    setNotice(
      "已删除 " + deleted + " 个会话" + (skipped ? "，跳过忙碌 " + skipped : ""),
      ""
    );
    await refreshList();
  }

  async function confirmEmptyArchive() {
    const ok = await confirmDestructive({
      title: "清空归档",
      message:
        "清空归档将永久删除全部已归档会话与消息（含搜索索引），并关闭相关归档通知，且不可恢复。确定继续？",
      secondMessage: "再次确认：归档内容将彻底删除，不可恢复。",
      confirmLabel: "继续",
      secondConfirmLabel: "清空归档",
      trigger: els.emptyArchiveBtn,
    });
    if (!ok) return;
    let totalDeleted = 0;
    let totalSkipped = 0;
    // 有界批次，循环直到本批删空
    for (let round = 0; round < 40; round++) {
      const res = await emptyArchive("EMPTY_ARCHIVE", 50);
      if (!res.ok) {
        setNotice(res.detail || "清空归档失败", "error");
        await refreshList();
        return;
      }
      const deleted = res.deletedCount != null ? res.deletedCount : 0;
      const skipped = res.skippedBusy != null ? res.skippedBusy : 0;
      totalDeleted += deleted;
      totalSkipped += skipped;
      if (deleted === 0) {
        break;
      }
    }
    if (
      state.activeConversationId &&
      state.header &&
      state.header.status === "ARCHIVED"
    ) {
      beginSelection("");
      rememberConversationId("");
      setConversationHeader(state, null);
    }
    setNotice(
      "已清空归档 " +
        totalDeleted +
        " 个会话" +
        (totalSkipped ? "，跳过忙碌 " + totalSkipped : ""),
      ""
    );
    if (typeof refreshArchiveNotices === "function") {
      try {
        await refreshArchiveNotices();
      } catch (_) {
        /* ignore */
      }
    }
    await refreshList();
    requestRender();
  }

  function updateHeaderActions() {
    const status = state.header.status || "";
    const has = Boolean(state.activeConversationId);
    if (els.renameBtn) els.renameBtn.hidden = true;
    if (els.archiveBtn) els.archiveBtn.hidden = !has || status !== "ACTIVE";
    if (els.unarchiveBtn) els.unarchiveBtn.hidden = !has || status !== "ARCHIVED";
    if (els.trashBtn) els.trashBtn.hidden = !has || status === "TRASHED";
    if (els.restoreBtn) els.restoreBtn.hidden = !has || status !== "TRASHED";
    if (els.title) {
      const canRename = has && status !== "TRASHED";
      els.title.classList.toggle("is-renameable", canRename);
      els.title.title = canRename ? "双击改名" : "";
    }
  }

  return {
    refreshList,
    renderList,
    createNew,
    updateHeaderActions,
  };
}

/**
 * 就地改名（对齐 deepseek-harness TerminalTitle）：
 * 双击 → input；Enter/blur 提交；Escape 取消；IME composing 不截获。
 */
function startInlineTitleEdit({ hostEl, displayText, onCommit }) {
  if (!hostEl || hostEl.dataset.editing === "1") return;
  const previous = displayText != null ? String(displayText) : hostEl.textContent || "";
  hostEl.dataset.editing = "1";
  const input = document.createElement("input");
  input.type = "text";
  input.className = "conv-title-edit";
  input.value = previous;
  input.maxLength = TITLE_MAX_LEN;
  input.setAttribute("aria-label", "会话标题");
  input.autocomplete = "off";
  let cancelled = false;
  let settled = false;

  hostEl.replaceChildren(input);
  input.focus();
  input.select();

  function restoreLabel(text) {
    hostEl.dataset.editing = "";
    delete hostEl.dataset.editing;
    hostEl.textContent = text;
  }

  async function finish(commit) {
    if (settled) return;
    settled = true;
    const next = input.value.trim();
    if (!commit || cancelled || !next || next === previous.trim()) {
      restoreLabel(previous || "未命名会话");
      return;
    }
    input.disabled = true;
    try {
      const ok = await onCommit(next);
      if (ok === false) {
        restoreLabel(previous || "未命名会话");
        return;
      }
      restoreLabel(next);
    } catch (_) {
      restoreLabel(previous || "未命名会话");
    }
  }

  input.addEventListener("pointerdown", (event) => event.stopPropagation());
  input.addEventListener("click", (event) => event.stopPropagation());
  input.addEventListener("dblclick", (event) => event.stopPropagation());
  input.addEventListener("blur", () => {
    void finish(true);
  });
  input.addEventListener("keydown", (event) => {
    event.stopPropagation();
    if (event.isComposing || event.keyCode === 229) return;
    if (event.key === "Escape") {
      cancelled = true;
      event.preventDefault();
      input.blur();
    } else if (event.key === "Enter") {
      event.preventDefault();
      input.blur();
    }
  });
}

function mutationHint(code, busyHint) {
  if (code === "CONVERSATION_BUSY") {
    return busyHint || "请先停止或等待队列完成";
  }
  if (code === "REVISION_CONFLICT") {
    return "会话已被其它操作更新，请刷新后再试";
  }
  return "操作失败";
}

function setPressed(btn, pressed) {
  if (!btn) return;
  btn.setAttribute("aria-pressed", pressed ? "true" : "false");
  btn.classList.toggle("is-active", pressed);
}

function emptyLine(text) {
  const p = document.createElement("p");
  p.className = "conv-empty";
  p.textContent = text;
  return p;
}

function formatActivity(iso) {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n) => String(n).padStart(2, "0");
  return (
    pad(date.getMonth() + 1) +
    "-" +
    pad(date.getDate()) +
    " " +
    pad(date.getHours()) +
    ":" +
    pad(date.getMinutes())
  );
}
