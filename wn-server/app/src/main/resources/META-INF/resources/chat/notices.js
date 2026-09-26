import {
  listArchiveNotices,
  dismissArchiveNotice,
  undoArchiveNotice,
} from "/chat/api.js?v=20260925i";

/**
 * 自动归档结果占位条（0.2.4-G）。
 * 不写样式表：仅语义 DOM，布局交由样式轨另改。
 */
export function createArchiveNoticeController({ root, setNotice, onChanged }) {
  const POLL_MS = 45000;
  let pollTimer = null;

  async function refresh() {
    if (!root) return;
    const res = await listArchiveNotices();
    if (!res.ok) {
      return;
    }
    const notices = Array.isArray(res.notices) ? res.notices : [];
    root.replaceChildren();
    if (notices.length === 0) {
      root.hidden = true;
      return;
    }
    root.hidden = false;
    for (const notice of notices) {
      root.appendChild(renderNotice(notice));
    }
  }

  function renderNotice(notice) {
    const panel = document.createElement("section");
    panel.dataset.noticeId = notice.id || "";
    panel.setAttribute("role", "status");

    const head = document.createElement("header");
    const title = document.createElement("strong");
    title.textContent = "已自动归档 " + (notice.items?.length || 0) + " 个会话";
    const close = document.createElement("button");
    close.type = "button";
    close.textContent = "关闭";
    close.addEventListener("click", async () => {
      await dismissArchiveNotice(notice.id);
      await refresh();
      if (onChanged) onChanged();
    });
    head.append(title, close);
    panel.append(head);

    const list = document.createElement("ul");
    for (const item of notice.items || []) {
      const li = document.createElement("li");
      const label = document.createElement("span");
      label.textContent =
        (item.title || "未命名") + (item.reason ? " — " + item.reason : "");
      const undo = document.createElement("button");
      undo.type = "button";
      undo.textContent = "撤销";
      undo.addEventListener("click", async () => {
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
    panel.append(list);
    return panel;
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

  document.addEventListener("visibilitychange", onVisibility);
  startPolling();

  return { refresh, stopPolling };
}
