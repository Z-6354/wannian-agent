import {
  listTaskReviews,
  confirmTaskReview,
  rejectTaskReview,
} from "/chat/api.js?v=20260929c";

const TYPE_LABEL = {
  READ_ONLY_TOOL_BATCH: "工具批处理",
  USER_SCHEDULED_NOTIFY: "定时提醒",
};

/**
 * 2.5.5：chat 内嵌待审后台任务卡片（确认 / 驳回）。
 * 未点选：不落 background_task；关闭页面后仍 PENDING，下次打开同会话会再出现；到期 EXPIRED。
 * 驳回：卡片先标「已拒绝」，并刷新会话以显示助手短文。
 * 卡片只展示类型/摘要，不展示模型 acknowledgementText（易误导为已完成）。
 */
export function createTaskReviewController({ hostEl, getConversationId, onChanged }) {
  let busy = false;
  /** @type {Map<string, {item: object, until: number}>} */
  const rejectedFlash = new Map();
  /** 最近一次成功拉到的待审列表，列表失败时不藏卡。 */
  let lastPending = [];

  async function refresh() {
    const conversationId = getConversationId();
    if (!conversationId) {
      hostEl.hidden = true;
      hostEl.replaceChildren();
      rejectedFlash.clear();
      lastPending = [];
      return;
    }
    try {
      const data = await listTaskReviews(conversationId);
      if (data && data.aborted) return;
      if (!data || data.ok === false) {
        // 网络/5xx：保留旧卡，避免误藏
        console.warn("[chat] task-review list failed", data && (data.detail || data.code));
        if (lastPending.length || rejectedFlash.size) {
          render(lastPending, conversationId);
        }
        return;
      }
      const items = Array.isArray(data.items) ? data.items : [];
      lastPending = items;
      render(items, conversationId);
    } catch (err) {
      console.warn("[chat] task-review refresh failed", err);
      if (lastPending.length || rejectedFlash.size) {
        render(lastPending, conversationId);
      }
    }
  }

  function render(items, conversationId) {
    const now = Date.now();
    for (const [id, flash] of [...rejectedFlash.entries()]) {
      if (flash.until <= now) rejectedFlash.delete(id);
    }

    hostEl.replaceChildren();
    const pending = Array.isArray(items) ? items : [];
    const flashCards = [];
    for (const [id, flash] of rejectedFlash.entries()) {
      if (!pending.some((p) => p.reviewId === id)) {
        flashCards.push({ ...flash.item, reviewId: id, _rejected: true });
      }
    }

    if (!pending.length && !flashCards.length) {
      hostEl.hidden = true;
      return;
    }
    hostEl.hidden = false;
    for (const item of pending) {
      hostEl.append(buildCard(item, conversationId, false));
    }
    for (const item of flashCards) {
      hostEl.append(buildCard(item, conversationId, true));
    }
  }

  function buildCard(item, conversationId, rejected) {
    const card = document.createElement("section");
    card.className =
      "chat-task-review-card" + (rejected ? " is-rejected" : "");
    card.dataset.reviewId = item.reviewId || "";
    if (rejected) card.dataset.status = "REJECTED";

    const title = document.createElement("h3");
    title.className = "chat-task-review-title";
    title.textContent = rejected ? "已拒绝后台任务" : "待确认后台任务";

    const expire = document.createElement("p");
    expire.className = "chat-task-review-expire";
    expire.textContent = rejected
      ? "已拒绝 · 不会创建或执行"
      : expireHint(item.expiresAt);

    const meta = document.createElement("p");
    meta.className = "chat-task-review-meta";
    const type = TYPE_LABEL[item.taskType] || item.taskType || "?";
    const when = item.scheduled ? "定时" : "立即";
    meta.textContent = type + " · " + when;

    const previewText = humanizePreview(item.inputPreview);
    const preview = document.createElement("p");
    preview.className = "chat-task-review-preview";
    preview.textContent = previewText;

    card.append(title, expire, meta);
    if (previewText) card.append(preview);

    if (!rejected) {
      const hint = document.createElement("p");
      hint.className = "chat-task-review-expire";
      hint.textContent = "确认后才会创建并执行；未确认不会开始。";
      card.append(hint);

      const actions = document.createElement("div");
      actions.className = "chat-task-review-actions";

      const confirmBtn = document.createElement("button");
      confirmBtn.type = "button";
      confirmBtn.className = "chat-task-review-confirm";
      confirmBtn.textContent = "确认";
      confirmBtn.addEventListener("click", () =>
        act(conversationId, item.reviewId, "confirm", item)
      );

      const rejectBtn = document.createElement("button");
      rejectBtn.type = "button";
      rejectBtn.className = "chat-task-review-reject";
      rejectBtn.textContent = "驳回";
      rejectBtn.addEventListener("click", () =>
        act(conversationId, item.reviewId, "reject", item)
      );

      actions.append(confirmBtn, rejectBtn);
      card.append(actions);
    } else {
      const status = document.createElement("p");
      status.className = "chat-task-review-status";
      status.textContent = "已拒绝";
      card.append(status);
    }
    return card;
  }

  async function act(conversationId, reviewId, op, item) {
    if (busy || !conversationId || !reviewId) return;
    busy = true;
    hostEl.querySelectorAll("button").forEach((b) => {
      b.disabled = true;
    });
    try {
      const res =
        op === "confirm"
          ? await confirmTaskReview(conversationId, reviewId)
          : await rejectTaskReview(conversationId, reviewId);
      if (!res || res.aborted) return;
      if (!res.ok) {
        const msg =
          reviewErrorMessage(res.code, res.detail) ||
          (res.detail && String(res.detail)) ||
          "操作失败";
        alert(msg);
        await refresh();
        return;
      }
      if (op === "reject") {
        rejectedFlash.set(reviewId, {
          item: item || { reviewId },
          until: Date.now() + 8000,
        });
      }
      await refresh();
      if (typeof onChanged === "function") {
        await onChanged();
      }
      if (op === "reject") {
        window.setTimeout(() => {
          rejectedFlash.delete(reviewId);
          refresh();
        }, 2500);
      }
    } catch (err) {
      console.warn("[chat] task-review " + op + " failed", err);
      alert((err && err.message) || "操作失败");
      await refresh();
    } finally {
      busy = false;
    }
  }

  return { refresh };
}

function reviewErrorMessage(code, detail) {
  switch (String(code || "")) {
    case "TASK_REVIEW_EXPIRED":
      return "待审已过期，请重新让助手提案。";
    case "TASK_REVIEW_NOT_FOUND":
      return "待审单不存在或已处理。";
    case "ILLEGAL_STATUS":
      return "状态已变更，请刷新后再试。";
    default:
      return detail ? String(detail) : "";
  }
}

function expireHint(expiresAt) {
  if (!expiresAt) {
    return "未确认不会开始执行；关闭页面后仍可回来处理。";
  }
  try {
    const t = new Date(expiresAt);
    if (Number.isNaN(t.getTime())) {
      return "未确认不会开始执行；关闭页面后仍可回来处理。";
    }
    const mm = String(t.getMonth() + 1).padStart(2, "0");
    const dd = String(t.getDate()).padStart(2, "0");
    const hh = String(t.getHours()).padStart(2, "0");
    const mi = String(t.getMinutes()).padStart(2, "0");
    return "未确认不会开始执行 · 有效至 " + mm + "-" + dd + " " + hh + ":" + mi;
  } catch (_e) {
    return "未确认不会开始执行；关闭页面后仍可回来处理。";
  }
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

const TOOL_LABEL = {
  calculate: "计算",
  current_time: "查时间",
  list_tools: "列工具",
  search_memory: "搜记忆",
  load_skill: "读技能",
  http_read: "读网页",
  powershell_resolve_5: "解析路径",
  powershell_resolve_7: "解析路径",
};

function summarizeOperations(operations) {
  if (!Array.isArray(operations) || !operations.length) return "";
  const parts = [];
  for (const op of operations.slice(0, 3)) {
    if (!op || typeof op !== "object") continue;
    const tool = String(op.tool || op.name || op.toolName || "").trim();
    const label = TOOL_LABEL[tool] || tool || "工具";
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
  if (operations.length > parts.length) {
    text += " 等" + operations.length + "项";
  }
  return text;
}
