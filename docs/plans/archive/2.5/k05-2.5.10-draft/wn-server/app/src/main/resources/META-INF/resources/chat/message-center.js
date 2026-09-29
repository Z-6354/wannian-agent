import {
  listNotices,
  dismissNotice,
  undoArchiveNotice,
} from "/chat/api.js?v=20260928e";

/**
 * 消息中心（2.5.10）：顶栏铃铛 + 下拉面板。
 * 含 ARCHIVE / TASK_*；VERSION 无生产者。
 */
export function createMessageCenterController({
  toggleEl,
  panelEl,
  badgeEl,
  setNotice,
  onChanged,
  openConversationAndTask,
}) {
  const POLL_MS = 45000;
  let pollTimer = null;
  let open = false;

  async function refresh() {
    if (!panelEl || !toggleEl) return;
    const res = await listNotices();
    if (!res.ok) {
      return;
    }
    const notices = Array.isArray(res.notices) ? res.notices : [];
    renderPanel(notices);
    updateBadge(notices.length);
  }

  function updateBadge(count) {
    if (!badgeEl) return;
    if (count <= 0) {
      badgeEl.hidden = true;
      badgeEl.textContent = "";
      return;
    }
    badgeEl.hidden = false;
    badgeEl.textContent = count > 99 ? "99+" : String(count);
  }

  function renderPanel(notices) {
    panelEl.replaceChildren();
    if (notices.length === 0) {
      const empty = document.createElement("p");
      empty.className = "msg-center-empty";
      empty.textContent = "暂无通知";
      panelEl.append(empty);
      return;
    }
    for (const notice of notices) {
      panelEl.appendChild(renderNotice(notice));
    }
  }

  function renderNotice(notice) {
    const row = document.createElement("article");
    row.className = "msg-center-item";
    row.dataset.noticeId = notice.noticeId || "";
    row.dataset.kind = notice.kind || "";

    const head = document.createElement("header");
    const title = document.createElement("strong");
    title.textContent = notice.title || notice.kind || "通知";
    const close = document.createElement("button");
    close.type = "button";
    close.className = "ghost msg-center-dismiss";
    close.textContent = "关闭";
    close.addEventListener("click", async (ev) => {
      ev.stopPropagation();
      await dismissNotice(notice.noticeId);
      await refresh();
      if (onChanged) onChanged();
    });
    head.append(title, close);
    row.append(head);

    if (notice.body) {
      const body = document.createElement("p");
      body.className = "msg-center-body";
      body.textContent = notice.body;
      row.append(body);
    }

    if (notice.kind === "ARCHIVE") {
      row.appendChild(renderArchiveItems(notice));
    } else if (
      (notice.kind === "TASK_COMPLETED" || notice.kind === "TASK_FAILED") &&
      notice.taskId
    ) {
      row.classList.add("is-clickable");
      row.addEventListener("click", async () => {
        setOpen(false);
        if (typeof openConversationAndTask === "function") {
          await openConversationAndTask(notice.conversationId, notice.taskId);
        }
      });
    }

    return row;
  }

  function renderArchiveItems(notice) {
    const list = document.createElement("ul");
    list.className = "msg-center-archive-list";
    const items =
      notice.payload && Array.isArray(notice.payload.items) ? notice.payload.items : [];
    for (const item of items) {
      const li = document.createElement("li");
      const label = document.createElement("span");
      label.textContent =
        (item.title || "未命名") + (item.reason ? " — " + item.reason : "");
      const undo = document.createElement("button");
      undo.type = "button";
      undo.className = "ghost";
      undo.textContent = "撤销";
      undo.addEventListener("click", async (ev) => {
        ev.stopPropagation();
        const res = await undoArchiveNotice(item.conversationId, item.revision);
        if (!res.ok) {
          setNotice(res.detail || "撤销失败", "error");
          return;
        }
        setNotice("已取消归档：" + (item.title || ""), "");
        await refresh();
        if (onChanged) onChanged();
      });
      li.append(label, undo);
      list.append(li);
    }
    return list;
  }

  function setOpen(next) {
    open = !!next;
    if (panelEl) panelEl.hidden = !open;
    if (toggleEl) toggleEl.setAttribute("aria-expanded", open ? "true" : "false");
  }

  function toggle() {
    setOpen(!open);
    if (open) {
      refresh().catch(() => {});
    }
  }

  function startPolling() {
    stopPolling();
    pollTimer = window.setInterval(() => {
      refresh().catch(() => {});
    }, POLL_MS);
  }

  function stopPolling() {
    if (pollTimer != null) {
      window.clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function onVisibility() {
    if (document.visibilityState === "visible") {
      refresh().catch(() => {});
      startPolling();
    } else {
      stopPolling();
    }
  }

  if (toggleEl) {
    toggleEl.addEventListener("click", (ev) => {
      ev.stopPropagation();
      toggle();
    });
  }
  document.addEventListener("click", (ev) => {
    if (!open) return;
    const root = toggleEl && toggleEl.closest(".message-center");
    if (root && root.contains(ev.target)) return;
    setOpen(false);
  });
  document.addEventListener("visibilitychange", onVisibility);
  startPolling();

  return { refresh, stopPolling, setOpen };
}
