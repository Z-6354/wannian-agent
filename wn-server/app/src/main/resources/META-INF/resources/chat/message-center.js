import {
  listNotices,
  dismissNotice,
  undoArchiveNotice,
} from "/chat/api.js?v=20260928r";
import { openDialog } from "/shell/dialog.js?v=20260925r";
import { formatMessageClock } from "/chat/time-format.js?v=20260928r";

/**
 * 消息中心（2.5.10 → 2.5.11）：顶栏入口 + 统一 dialog 弹窗。
 * 角标仍挂在按钮上；列表面板改走 shell/dialog.js，与确认/输入弹窗同一套。
 */
export function createMessageCenterController({
  toggleEl,
  badgeEl,
  setNotice,
  onChanged,
  openConversationAndTask,
}) {
  const POLL_MS = 45000;
  let pollTimer = null;
  /** @type {null | { close: Function, listHost: HTMLElement }} */
  let openModal = null;

  async function refresh() {
    const res = await listNotices();
    if (!res.ok) {
      return;
    }
    const notices = Array.isArray(res.notices) ? res.notices : [];
    updateBadge(notices.length);
    if (openModal && openModal.listHost) {
      renderList(openModal.listHost, notices);
    }
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

  function renderList(host, notices) {
    host.replaceChildren();
    if (!notices.length) {
      const empty = document.createElement("p");
      empty.className = "msg-center-empty";
      empty.textContent = "暂无通知";
      host.append(empty);
      return;
    }
    for (const notice of notices) {
      host.appendChild(renderNotice(notice));
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
    const headRight = document.createElement("div");
    headRight.className = "msg-center-head-right";
    const clock = formatMessageClock(notice.createdAt);
    if (clock) {
      const timeEl = document.createElement("time");
      timeEl.className = "msg-center-time";
      timeEl.dateTime = String(notice.createdAt || "");
      timeEl.textContent = clock;
      timeEl.title = String(notice.createdAt || clock);
      headRight.append(timeEl);
    }
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
    headRight.append(close);
    head.append(title, headRight);
    row.append(head);

    if (notice.body) {
      const body = document.createElement("p");
      body.className = "msg-center-body";
      body.textContent = notice.body;
      row.append(body);
    }

    const preview = noticePreview(notice);
    if (preview || notice.errorCode) {
      const meta = document.createElement("p");
      meta.className = "msg-center-preview";
      const bits = [];
      if (notice.errorCode && !String(notice.body || "").includes(notice.errorCode)) {
        bits.push(notice.errorCode);
      }
      if (preview) {
        bits.push(preview);
      }
      if (bits.length > 0) {
        meta.textContent = bits.join(" · ");
        row.append(meta);
      }
    }

    if (notice.kind === "ARCHIVE") {
      row.appendChild(renderArchiveItems(notice));
    } else if (
      (notice.kind === "TASK_COMPLETED" || notice.kind === "TASK_FAILED") &&
      notice.taskId
    ) {
      row.classList.add("is-clickable");
      row.addEventListener("click", async () => {
        const conversationId = notice.conversationId;
        const taskId = notice.taskId;
        if (openModal) openModal.close();
        if (typeof openConversationAndTask === "function") {
          await openConversationAndTask(conversationId, taskId);
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

  async function openNoticesDialog() {
    if (openModal) {
      openModal.close();
      return;
    }
    if (document.querySelector(".dialog-backdrop")) {
      if (typeof setNotice === "function") {
        setNotice("请先关闭当前弹窗", "");
      }
      return;
    }

    const res = await listNotices();
    const notices = res.ok && Array.isArray(res.notices) ? res.notices : [];
    updateBadge(notices.length);

    const listHost = document.createElement("div");
    listHost.className = "msg-center-dialog-list";
    renderList(listHost, notices);

    const api = openDialog({
      title: "通知",
      body: listHost,
      trigger: toggleEl,
      closeLabel: "关闭",
      onClose() {
        openModal = null;
        if (toggleEl) toggleEl.setAttribute("aria-expanded", "false");
      },
      renderActions(actions, dialogApi) {
        if (notices.length > 0) {
          const clearAll = document.createElement("button");
          clearAll.type = "button";
          clearAll.className = "secondary";
          clearAll.textContent = "全部关闭";
          clearAll.addEventListener("click", async () => {
            dialogApi.setPending(true);
            const ids = Array.from(listHost.querySelectorAll("[data-notice-id]"))
              .map((el) => el.dataset.noticeId)
              .filter(Boolean);
            for (const id of ids) {
              await dismissNotice(id);
            }
            await refresh();
            if (onChanged) onChanged();
            dialogApi.setPending(false);
            dialogApi.close();
          });
          actions.append(clearAll);
        }
        const closeBtn = document.createElement("button");
        closeBtn.type = "button";
        closeBtn.textContent = "关闭";
        closeBtn.addEventListener("click", () => dialogApi.close());
        actions.append(closeBtn);
        queueMicrotask(() => dialogApi.open(closeBtn));
      },
    });

    if (!api) return;
    openModal = { close: api.close, listHost };
    if (toggleEl) toggleEl.setAttribute("aria-expanded", "true");
  }

  function setOpen(next) {
    if (next) {
      void openNoticesDialog();
      return;
    }
    if (openModal) openModal.close();
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
    toggleEl.setAttribute("aria-haspopup", "dialog");
    toggleEl.removeAttribute("aria-controls");
    toggleEl.addEventListener("click", (ev) => {
      ev.stopPropagation();
      void openNoticesDialog();
    });
  }
  document.addEventListener("visibilitychange", onVisibility);
  startPolling();

  return { refresh, stopPolling, setOpen };
}

function noticePreview(notice) {
  if (!notice || !notice.payload || typeof notice.payload !== "object") {
    return "";
  }
  const raw = notice.payload.resultPreview;
  return typeof raw === "string" ? raw.trim() : "";
}
