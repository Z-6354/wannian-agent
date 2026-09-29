import {
  listBackgroundTasks,
  getBackgroundTask,
  cancelBackgroundTask,
} from "/chat/api.js?v=20260928d";

/**
 * 2.5.9：会话侧栏「任务」分区。
 */
export function createTaskSidebar({
  hostEl,
  getConversationId,
  setNotice,
}) {
  let filterActiveOnly = false;
  let highlightTaskId = "";
  let openTaskId = "";
  let generation = 0;

  hostEl.innerHTML = `
    <details class="task-sidebar-panel" id="task-sidebar-details" open>
      <summary class="task-sidebar-summary">任务</summary>
      <div class="task-sidebar-toolbar">
        <button type="button" class="ghost is-active" data-filter="all" aria-pressed="true">全部</button>
        <button type="button" class="ghost" data-filter="active" aria-pressed="false">进行中</button>
        <button type="button" class="ghost" data-action="refresh">刷新</button>
      </div>
      <div class="task-sidebar-list" role="list"></div>
      <div class="task-sidebar-detail" hidden></div>
    </details>
  `;

  const details = hostEl.querySelector("#task-sidebar-details");
  const listEl = hostEl.querySelector(".task-sidebar-list");
  const detailEl = hostEl.querySelector(".task-sidebar-detail");
  const toolbar = hostEl.querySelector(".task-sidebar-toolbar");

  toolbar.addEventListener("click", (ev) => {
    const btn = ev.target.closest("button");
    if (!btn) return;
    if (btn.dataset.filter === "all") {
      filterActiveOnly = false;
      setFilterButtons();
      refresh();
    } else if (btn.dataset.filter === "active") {
      filterActiveOnly = true;
      setFilterButtons();
      refresh();
    } else if (btn.dataset.action === "refresh") {
      refresh();
    }
  });

  listEl.addEventListener("click", async (ev) => {
    const row = ev.target.closest("[data-task-id]");
    if (!row) return;
    const taskId = row.dataset.taskId;
    const cancelBtn = ev.target.closest("[data-action='cancel']");
    if (cancelBtn) {
      ev.preventDefault();
      await doCancel(taskId);
      return;
    }
    openTaskId = taskId;
    highlightTaskId = taskId;
    await showDetail(taskId);
    paintHighlight();
  });

  function setFilterButtons() {
    toolbar.querySelectorAll("[data-filter]").forEach((btn) => {
      const on =
        (btn.dataset.filter === "active" && filterActiveOnly) ||
        (btn.dataset.filter === "all" && !filterActiveOnly);
      btn.classList.toggle("is-active", on);
      btn.setAttribute("aria-pressed", on ? "true" : "false");
    });
  }

  async function refresh() {
    const cid = getConversationId();
    const gen = ++generation;
    if (!cid) {
      listEl.replaceChildren();
      detailEl.hidden = true;
      return;
    }
    const res = await listBackgroundTasks(cid, { activeOnly: filterActiveOnly });
    if (gen !== generation) return;
    if (!res.ok) {
      listEl.textContent = res.detail || "无法加载任务";
      return;
    }
    const items = Array.isArray(res.items) ? res.items : [];
    listEl.replaceChildren();
    if (items.length === 0) {
      const empty = document.createElement("p");
      empty.className = "task-sidebar-empty";
      empty.textContent = "暂无后台任务";
      listEl.appendChild(empty);
      detailEl.hidden = true;
      return;
    }
    for (const item of items) {
      listEl.appendChild(renderRow(item));
    }
    paintHighlight();
    if (openTaskId) {
      await showDetail(openTaskId);
    }
  }

  function renderRow(item) {
    const row = document.createElement("div");
    row.className = "task-sidebar-row";
    row.dataset.taskId = item.taskId || "";
    row.setAttribute("role", "listitem");
    const title = document.createElement("div");
    title.className = "task-sidebar-row-title";
    title.textContent =
      (item.taskType || "TASK") + " · " + (item.status || "?");
    const meta = document.createElement("div");
    meta.className = "task-sidebar-row-meta";
    const bits = [];
    if (item.nextFireAt) bits.push("下次 " + shortIso(item.nextFireAt));
    if (item.scheduleSummary) bits.push(item.scheduleSummary);
    if (item.inputPreview) bits.push(clip(item.inputPreview, 48));
    meta.textContent = bits.join(" · ") || "—";
    row.appendChild(title);
    row.appendChild(meta);
    if (item.cancelable) {
      const actions = document.createElement("div");
      actions.className = "task-sidebar-row-actions";
      const cancel = document.createElement("button");
      cancel.type = "button";
      cancel.className = "ghost";
      cancel.dataset.action = "cancel";
      cancel.textContent = "取消";
      actions.appendChild(cancel);
      row.appendChild(actions);
    }
    return row;
  }

  async function showDetail(taskId) {
    const cid = getConversationId();
    if (!cid || !taskId) {
      detailEl.hidden = true;
      return;
    }
    const res = await getBackgroundTask(cid, taskId);
    if (!res.ok) {
      detailEl.hidden = false;
      detailEl.textContent = res.detail || "无法加载详情";
      return;
    }
    const d = res.task || res;
    detailEl.hidden = false;
    detailEl.replaceChildren();
    const pre = document.createElement("pre");
    pre.className = "task-sidebar-detail-pre";
    pre.textContent = [
      "状态：" + (d.status || ""),
      "类型：" + (d.taskType || ""),
      d.nextFireAt ? "下次开火：" + d.nextFireAt : null,
      d.lastFiredAt ? "上次开火：" + d.lastFiredAt : null,
      d.scheduleSummary ? "日程：" + d.scheduleSummary : null,
      d.inputPreview ? "输入：" + d.inputPreview : null,
      d.resultPreview ? "结果：" + d.resultPreview : null,
      d.errorCode ? "错误：" + d.errorCode : null,
    ]
      .filter(Boolean)
      .join("\n");
    detailEl.appendChild(pre);
  }

  async function doCancel(taskId) {
    const cid = getConversationId();
    if (!cid) return;
    const res = await cancelBackgroundTask(cid, taskId);
    if (!res.ok) {
      if (typeof setNotice === "function") {
        setNotice(res.detail || "取消失败");
      }
      return;
    }
    if (typeof setNotice === "function") {
      setNotice("任务已取消");
    }
    openTaskId = taskId;
    await refresh();
  }

  function paintHighlight() {
    listEl.querySelectorAll(".task-sidebar-row").forEach((row) => {
      row.classList.toggle("is-highlight", row.dataset.taskId === highlightTaskId);
    });
  }

  function focusTask(taskId) {
    if (details) details.open = true;
    highlightTaskId = taskId || "";
    openTaskId = taskId || "";
    return refresh().then(() => {
      paintHighlight();
      if (taskId) return showDetail(taskId);
    });
  }

  return {
    refresh,
    focusTask,
    hostEl,
  };
}

function shortIso(iso) {
  if (!iso) return "";
  return String(iso).replace("T", " ").slice(0, 16);
}

function clip(text, max) {
  const t = String(text || "").trim();
  if (t.length <= max) return t;
  return t.slice(0, max) + "…";
}
