import {
  listBackgroundTasks,
  getBackgroundTask,
  cancelBackgroundTask,
  deleteBackgroundTask,
} from "/chat/api.js?v=20260928s";
import { readRememberedConversationId } from "/chat/state.js?v=20260928r";
import { clearBanner, clearStatus, showError, showSuccess } from "/manage/page-feedback.js?v=20260920p";
import { formatTaskClock } from "/chat/time-format.js?v=20260928r";
import { confirmDialog } from "/shell/dialog.js?v=20260925r";

const TYPE_LABEL = {
  READ_ONLY_TOOL_BATCH: "工具批处理",
  USER_SCHEDULED_NOTIFY: "定时通知",
};

const STATUS_LABEL = {
  SCHEDULED: "已定时",
  CREATED: "已创建",
  READY: "就绪",
  WAITING: "等待中",
  RUNNING: "运行中",
  CANCEL_REQUESTED: "取消中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
};

const STATUS_TONE = {
  SCHEDULED: "scheduled",
  CREATED: "idle",
  READY: "idle",
  WAITING: "idle",
  RUNNING: "running",
  CANCEL_REQUESTED: "running",
  SUCCEEDED: "done",
  FAILED: "bad",
  CANCELLED: "muted",
};

/**
 * 任务中心：单列手风琴列表（点开在行下展开详情）。
 * @param {{ main: HTMLElement, actions: HTMLElement, status: HTMLElement, banner: HTMLElement, route?: URLSearchParams }} view
 */
export function mountTasksPage(view) {
  view.main.replaceChildren();
  view.actions.replaceChildren();
  clearStatus(view.status);
  clearBanner(view.banner);

  const page = document.createElement("div");
  page.className = "content-page tasks-page";

  const convLine = document.createElement("p");
  convLine.className = "tasks-conv-line";
  convLine.id = "tasks-conv-line";

  const toolbar = document.createElement("div");
  toolbar.className = "tasks-toolbar";
  toolbar.setAttribute("role", "tablist");
  toolbar.setAttribute("aria-label", "任务筛选");

  const filterAll = document.createElement("button");
  filterAll.type = "button";
  filterAll.className = "tasks-filter is-active";
  filterAll.dataset.filter = "all";
  filterAll.setAttribute("aria-pressed", "true");
  filterAll.textContent = "全部";

  const filterActive = document.createElement("button");
  filterActive.type = "button";
  filterActive.className = "tasks-filter";
  filterActive.dataset.filter = "active";
  filterActive.setAttribute("aria-pressed", "false");
  filterActive.textContent = "进行中";

  const refreshBtn = document.createElement("button");
  refreshBtn.type = "button";
  refreshBtn.className = "ghost tasks-refresh";
  refreshBtn.textContent = "刷新";
  refreshBtn.title = "刷新列表";

  toolbar.append(filterAll, filterActive, refreshBtn);

  const listEl = document.createElement("div");
  listEl.className = "tasks-list";
  listEl.setAttribute("role", "list");

  // 页头 h1 已是「任务」，此处不再重复标题，避免双标题占位与错位
  page.append(convLine, toolbar, listEl);
  view.main.append(page);

  let filterActiveOnly = false;
  let openTaskId = view.route?.get("taskId") || "";
  let generation = 0;
  /** @type {Map<string, object>} */
  const itemById = new Map();

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
    } else if (btn === refreshBtn) {
      refresh();
    }
  });

  listEl.addEventListener("click", async (ev) => {
    const row = ev.target.closest("[data-task-id]");
    if (!row || !listEl.contains(row)) return;
    const taskId = row.dataset.taskId;
    if (!taskId) return;

    const actionBtn = ev.target.closest("[data-action]");
    if (actionBtn) {
      ev.preventDefault();
      ev.stopPropagation();
      const action = actionBtn.dataset.action;
      if (action === "delete") {
        if (actionBtn.disabled) return;
        const item = itemById.get(taskId);
        await doDeleteOrCancel(taskId, item);
        return;
      }
      if (action === "edit") {
        // 占位：后续加编辑
        return;
      }
      return;
    }

    // 再点同一行 → 收起
    if (openTaskId === taskId) {
      openTaskId = "";
      syncHash("");
      collapseAll();
      return;
    }
    openTaskId = taskId;
    syncHash(taskId);
    await expandRow(taskId);
  });

  function setFilterButtons() {
    for (const btn of toolbar.querySelectorAll("[data-filter]")) {
      const on =
        (btn.dataset.filter === "active" && filterActiveOnly) ||
        (btn.dataset.filter === "all" && !filterActiveOnly);
      btn.classList.toggle("is-active", on);
      btn.setAttribute("aria-pressed", on ? "true" : "false");
    }
  }

  function syncHash(taskId) {
    const next = taskId ? "tasks?taskId=" + encodeURIComponent(taskId) : "tasks";
    if (location.hash.replace(/^#/, "") !== next) {
      history.replaceState(null, "", "#" + next);
    }
  }

  async function refresh() {
    const cid = readRememberedConversationId();
    const gen = ++generation;
    if (!cid) {
      convLine.textContent = "尚未选择会话 — 请先打开对话";
      listEl.replaceChildren();
      itemById.clear();
      return;
    }
    convLine.textContent = "当前会话 · " + cid.slice(0, 8);
    const res = await listBackgroundTasks(cid, { activeOnly: filterActiveOnly });
    if (gen !== generation) return;
    if (!res.ok) {
      showError(view.banner, res);
      listEl.textContent = res.detail || "无法加载任务";
      return;
    }
    clearBanner(view.banner);
    const items = Array.isArray(res.items) ? res.items : [];
    itemById.clear();
    listEl.replaceChildren();
    if (items.length === 0) {
      const empty = document.createElement("div");
      empty.className = "tasks-empty";
      empty.textContent = filterActiveOnly ? "暂无进行中的任务" : "暂无后台任务";
      listEl.appendChild(empty);
      openTaskId = "";
      return;
    }
    for (const item of items) {
      if (item.taskId) itemById.set(item.taskId, item);
      listEl.appendChild(renderRow(item));
    }
    if (openTaskId && itemById.has(openTaskId)) {
      await expandRow(openTaskId);
    } else {
      openTaskId = "";
      collapseAll();
    }
  }

  function renderRow(item) {
    const row = document.createElement("article");
    row.className = "tasks-row";
    row.dataset.taskId = item.taskId || "";
    row.setAttribute("role", "listitem");
    row.setAttribute("aria-expanded", "false");

    const main = document.createElement("div");
    main.className = "tasks-row-main";

    const left = document.createElement("div");
    left.className = "tasks-row-left";

    const name = document.createElement("div");
    name.className = "tasks-row-name";
    name.textContent = taskDisplayName(item);

    const meta = document.createElement("div");
    meta.className = "tasks-row-meta";
    const type = document.createElement("span");
    type.className = "tasks-row-type";
    type.textContent = typeLabel(item.taskType);
    meta.append(type);
    const timeText = timeLabel(item);
    if (timeText) {
      const time = document.createElement("span");
      time.className = "tasks-row-time";
      time.textContent = timeText;
      meta.append(time);
    }
    left.append(name, meta);

    const right = document.createElement("div");
    right.className = "tasks-row-right";

    const badge = document.createElement("span");
    badge.className =
      "tasks-status-badge tone-" + (STATUS_TONE[item.status] || "muted");
    badge.textContent = statusLabel(item.status);

    const editBtn = document.createElement("button");
    editBtn.type = "button";
    editBtn.className = "tasks-icon-btn";
    editBtn.dataset.action = "edit";
    editBtn.disabled = true;
    editBtn.title = "编辑（即将支持）";
    editBtn.setAttribute("aria-label", "编辑");
    editBtn.textContent = "编辑";

    const delBtn = document.createElement("button");
    delBtn.type = "button";
    delBtn.className = "tasks-icon-btn tasks-icon-btn-danger";
    delBtn.dataset.action = "delete";
    delBtn.textContent = "删除";
    delBtn.setAttribute("aria-label", "删除");
    delBtn.disabled = false;
    if (item.cancelable) {
      delBtn.title = "取消并结束任务";
    } else {
      delBtn.title = "从列表删除";
    }

    right.append(badge, editBtn, delBtn);
    main.append(left, right);

    const panel = document.createElement("div");
    panel.className = "tasks-row-panel";
    panel.hidden = true;

    row.append(main, panel);
    return row;
  }

  function collapseAll() {
    listEl.querySelectorAll(".tasks-row").forEach((row) => {
      row.classList.remove("is-open");
      row.setAttribute("aria-expanded", "false");
      const panel = row.querySelector(".tasks-row-panel");
      if (panel) {
        panel.hidden = true;
        panel.replaceChildren();
      }
    });
  }

  async function expandRow(taskId) {
    collapseAll();
    const row = listEl.querySelector('[data-task-id="' + cssEscape(taskId) + '"]');
    if (!row) return;
    row.classList.add("is-open");
    row.setAttribute("aria-expanded", "true");
    const panel = row.querySelector(".tasks-row-panel");
    if (!panel) return;
    panel.hidden = false;
    panel.replaceChildren();
    const loading = document.createElement("p");
    loading.className = "tasks-panel-loading";
    loading.textContent = "加载详情…";
    panel.append(loading);

    const cid = readRememberedConversationId();
    if (!cid) {
      loading.textContent = "无会话";
      return;
    }
    const res = await getBackgroundTask(cid, taskId);
    if (openTaskId !== taskId) return;
    if (!res.ok) {
      loading.className = "tasks-detail-error";
      loading.textContent = res.detail || "无法加载详情";
      return;
    }
    const d = res.task || res;
    panel.replaceChildren(buildDetailBody(d));
  }

  function buildDetailBody(d) {
    const wrap = document.createElement("div");
    wrap.className = "tasks-panel-body";

    const dl = document.createElement("dl");
    dl.className = "tasks-detail-dl";

    const terminal =
      d.status === "SUCCEEDED" || d.status === "FAILED" || d.status === "CANCELLED";
    const rows = [
      ["类型", typeLabel(d.taskType)],
      ["提醒", d.reminderText || humanizePreview(d.inputPreview) || null],
      ["日程", d.scheduleSummary || null],
      ["创建", formatTaskClock(d.createdAt)],
      ["下次", !terminal ? formatTaskClock(d.nextFireAt) : null],
      ["上次开火", formatTaskClock(d.lastFiredAt)],
      ["完成", formatTaskClock(d.completedAt)],
      ["结果", d.resultPreview || null],
      ["错误", d.errorCode || null],
      ["时区", d.timezone || null],
    ];
    for (const [k, v] of rows) {
      if (v == null || v === "") continue;
      const dt = document.createElement("dt");
      dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = v;
      dl.append(dt, dd);
    }
    wrap.append(dl);
    return wrap;
  }

  async function doDeleteOrCancel(taskId, item) {
    const cancelable = !!(item && item.cancelable);
    const name = item ? taskDisplayName(item) : "该任务";
    if (cancelable) {
      const ok = await confirmDialog({
        title: "取消任务",
        description: "确定取消「" + name + "」？进行中的调度将停止。",
        danger: true,
        confirmLabel: "取消任务",
        cancelLabel: "返回",
      });
      if (!ok) return;
      await doCancel(taskId);
      return;
    }
    const ok = await confirmDialog({
      title: "删除任务",
      description: "确定从列表删除「" + name + "」？此操作不可恢复。",
      danger: true,
      confirmLabel: "删除",
      cancelLabel: "返回",
    });
    if (!ok) return;
    await doDelete(taskId);
  }

  async function doCancel(taskId) {
    const cid = readRememberedConversationId();
    if (!cid) return;
    const res = await cancelBackgroundTask(cid, taskId);
    if (!res.ok) {
      showError(view.banner, res);
      return;
    }
    showSuccess(view.banner, "任务已取消");
    openTaskId = "";
    await refresh();
    window.dispatchEvent(new CustomEvent("wannian:tasks-changed"));
  }

  async function doDelete(taskId) {
    const cid = readRememberedConversationId();
    if (!cid) return;
    const res = await deleteBackgroundTask(cid, taskId);
    if (!res.ok) {
      showError(view.banner, res);
      return;
    }
    showSuccess(view.banner, "任务已删除");
    if (openTaskId === taskId) {
      openTaskId = "";
      syncHash("");
    }
    await refresh();
    window.dispatchEvent(new CustomEvent("wannian:tasks-changed"));
  }

  refresh();
}

function cssEscape(value) {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
    return CSS.escape(value);
  }
  return String(value).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

function taskDisplayName(item) {
  const remind = String(item.reminderText || "").trim();
  if (remind) return clip(remind, 64);
  const preview = humanizePreview(item.inputPreview);
  if (preview) return clip(preview, 64);
  return typeLabel(item.taskType) || "未命名任务";
}

function humanizePreview(raw) {
  const s = String(raw || "").trim();
  if (!s) return "";
  if (s.startsWith("{") || s.startsWith("[")) {
    try {
      const o = JSON.parse(s);
      if (o && typeof o === "object" && !Array.isArray(o)) {
        const t = o.title || o.message || o.reminder;
        if (t) return String(t).trim();
        const ops = summarizeOperations(o.operations);
        if (ops) return ops;
      }
      if (Array.isArray(o)) {
        const ops = summarizeOperations(o);
        if (ops) return ops;
      }
    } catch (_e) {
      /* ignore */
    }
    return "";
  }
  return s;
}

function summarizeOperations(operations) {
  if (!Array.isArray(operations) || !operations.length) return "";
  const labels = {
    calculate: "计算",
    current_time: "查时间",
    list_tools: "列工具",
    search_memory: "搜记忆",
    load_skill: "读技能",
    http_read: "读网页",
  };
  const parts = [];
  for (const op of operations.slice(0, 3)) {
    if (!op || typeof op !== "object") continue;
    const tool = String(op.tool || op.name || "").trim();
    const label = labels[tool] || tool || "工具";
    const expr = String(op.expression || op.query || op.url || op.skill_id || "").trim();
    parts.push(expr ? label + " " + expr : label);
  }
  if (!parts.length) return "工具批处理";
  let text = parts.join("；");
  if (operations.length > parts.length) text += " 等" + operations.length + "项";
  return text;
}

function typeLabel(type) {
  return TYPE_LABEL[type] || type || "任务";
}

function statusLabel(status) {
  return STATUS_LABEL[status] || status || "?";
}

function timeLabel(item) {
  const terminal =
    item.status === "SUCCEEDED" ||
    item.status === "FAILED" ||
    item.status === "CANCELLED";
  if (!terminal && item.nextFireAt) {
    const t = formatTaskClock(item.nextFireAt);
    return t ? "下次 " + t : "";
  }
  if (item.completedAt) {
    const t = formatTaskClock(item.completedAt);
    return t ? "完成 " + t : "";
  }
  if (item.createdAt) {
    const t = formatTaskClock(item.createdAt);
    return t ? "创建 " + t : "";
  }
  return "";
}

function clip(text, max) {
  const t = String(text || "").trim();
  if (t.length <= max) return t;
  return t.slice(0, max) + "…";
}
