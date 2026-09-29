import { listBackgroundTasks } from "/chat/api.js?v=20260928s";
import { formatTaskClock } from "/chat/time-format.js?v=20260928s";

const TYPE_LABEL = {
  READ_ONLY_TOOL_BATCH: "工具批处理",
  USER_SCHEDULED_NOTIFY: "定时通知",
};

const RAIL_LIMIT = 6;

/**
 * 左下任务缩略条：仅未完成（非 SUCCEEDED/FAILED/CANCELLED）；进任务中心看全部。
 */
export function createTaskSidebar({
  hostEl,
  getConversationId,
  ensureView,
}) {
  let highlightTaskId = "";
  let generation = 0;

  hostEl.innerHTML = `
    <div class="task-rail">
      <div class="task-rail-head">
        <a class="task-rail-title" href="#tasks">任务</a>
        <button type="button" class="ghost task-rail-refresh" data-action="refresh" title="刷新">↻</button>
      </div>
      <div class="task-rail-list" role="list"></div>
    </div>
  `;

  const listEl = hostEl.querySelector(".task-rail-list");
  const head = hostEl.querySelector(".task-rail-head");

  head.addEventListener("click", (ev) => {
    const btn = ev.target.closest("[data-action='refresh']");
    if (btn) {
      ev.preventDefault();
      refresh();
    }
  });

  listEl.addEventListener("click", (ev) => {
    const row = ev.target.closest("[data-task-id]");
    if (!row) return;
    ev.preventDefault();
    openTasksPage(row.dataset.taskId);
  });

  window.addEventListener("wannian:tasks-changed", () => {
    refresh();
  });

  function openTasksPage(taskId) {
    highlightTaskId = taskId || "";
    paintHighlight();
    const hash = taskId ? "tasks?taskId=" + encodeURIComponent(taskId) : "tasks";
    if (location.hash.replace(/^#/, "") !== hash) {
      location.hash = hash;
      return;
    }
    if (typeof ensureView === "function") {
      ensureView("tasks");
    }
  }

  async function refresh() {
    const cid = getConversationId();
    const gen = ++generation;
    if (!cid) {
      listEl.replaceChildren();
      return;
    }
    const res = await listBackgroundTasks(cid, { activeOnly: true });
    if (gen !== generation) return;
    if (!res.ok) {
      listEl.textContent = res.detail || "无法加载";
      return;
    }
    const items = Array.isArray(res.items) ? res.items : [];
    listEl.replaceChildren();
    if (items.length === 0) {
      const empty = document.createElement("p");
      empty.className = "task-rail-empty";
      empty.textContent = "暂无进行中任务";
      listEl.appendChild(empty);
      return;
    }
    const shown = items.slice(0, RAIL_LIMIT);
    for (const item of shown) {
      listEl.appendChild(renderThumb(item));
    }
    paintHighlight();
  }

  function renderThumb(item) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "task-rail-thumb";
    row.dataset.taskId = item.taskId || "";
    row.setAttribute("role", "listitem");

    const name = document.createElement("span");
    name.className = "task-rail-thumb-name";
    name.textContent = taskDisplayName(item);

    const meta = document.createElement("span");
    meta.className = "task-rail-thumb-meta";
    meta.textContent = [typeLabel(item.taskType), timeLabel(item)]
      .filter(Boolean)
      .join(" · ");

    row.append(name, meta);
    return row;
  }

  function paintHighlight() {
    listEl.querySelectorAll(".task-rail-thumb").forEach((row) => {
      row.classList.toggle("is-highlight", row.dataset.taskId === highlightTaskId);
    });
  }

  function focusTask(taskId) {
    highlightTaskId = taskId || "";
    return refresh().then(() => {
      paintHighlight();
      if (taskId) openTasksPage(taskId);
    });
  }

  return {
    refresh,
    focusTask,
    hostEl,
  };
}

function taskDisplayName(item) {
  const remind = String(item.reminderText || "").trim();
  if (remind) return clip(remind, 28);
  const preview = humanizePreview(item.inputPreview);
  if (preview) return clip(preview, 28);
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
        const ops = summarizeOperations(o.tools || o.calls || o.operations);
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
    powershell_resolve_5: "解析路径",
    powershell_resolve_7: "解析路径",
  };
  const parts = [];
  for (const op of operations.slice(0, 3)) {
    if (!op || typeof op !== "object") continue;
    const tool = String(op.tool || op.name || op.toolName || "").trim();
    const label = labels[tool] || tool || "工具";
    let expr = String(op.expression || op.query || op.url || op.skill_id || "").trim();
    if (!expr && op.arguments && typeof op.arguments === "object") {
      expr = String(
        op.arguments.expression ||
          op.arguments.query ||
          op.arguments.url ||
          op.arguments.skill_id ||
          ""
      ).trim();
    } else if (!expr && op.parameters && typeof op.parameters === "object") {
      expr = String(
        op.parameters.expression || op.parameters.query || op.parameters.url || ""
      ).trim();
    }
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

function timeLabel(item) {
  if (item.nextFireAt) return formatTaskClock(item.nextFireAt);
  if (item.createdAt) return formatTaskClock(item.createdAt);
  return "";
}

function clip(text, max) {
  const t = String(text || "").trim();
  if (t.length <= max) return t;
  return t.slice(0, max) + "…";
}
