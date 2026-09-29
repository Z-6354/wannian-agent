import {
  listTaskReviews,
  confirmTaskReview,
  rejectTaskReview,
} from "/chat/api.js?v=20260927a";

/**
 * 2.5.5：chat 内嵌待审后台任务卡片（确认 / 驳回）。
 */
export function createTaskReviewController({ hostEl, getConversationId, onChanged }) {
  let busy = false;

  async function refresh() {
    const conversationId = getConversationId();
    if (!conversationId) {
      hostEl.hidden = true;
      hostEl.replaceChildren();
      return;
    }
    try {
      const data = await listTaskReviews(conversationId);
      const items = Array.isArray(data && data.items) ? data.items : [];
      render(items, conversationId);
    } catch (err) {
      console.debug("[chat] task-review refresh failed", err);
      hostEl.hidden = true;
      hostEl.replaceChildren();
    }
  }

  function render(items, conversationId) {
    hostEl.replaceChildren();
    if (!items.length) {
      hostEl.hidden = true;
      return;
    }
    hostEl.hidden = false;
    for (const item of items) {
      const card = document.createElement("section");
      card.className = "chat-task-review-card";
      card.dataset.reviewId = item.reviewId || "";

      const title = document.createElement("h3");
      title.className = "chat-task-review-title";
      title.textContent = "待确认后台任务";

      const meta = document.createElement("p");
      meta.className = "chat-task-review-meta";
      const type = item.taskType || "?";
      const when = item.scheduled ? "定时" : "立即";
      meta.textContent = type + " · " + when;

      const preview = document.createElement("pre");
      preview.className = "chat-task-review-preview";
      preview.textContent = item.inputPreview || "";

      const ack = document.createElement("p");
      ack.className = "chat-task-review-ack";
      ack.textContent = item.acknowledgementText || "";

      const actions = document.createElement("div");
      actions.className = "chat-task-review-actions";

      const confirmBtn = document.createElement("button");
      confirmBtn.type = "button";
      confirmBtn.className = "chat-task-review-confirm";
      confirmBtn.textContent = "确认";
      confirmBtn.addEventListener("click", () =>
        act(conversationId, item.reviewId, "confirm")
      );

      const rejectBtn = document.createElement("button");
      rejectBtn.type = "button";
      rejectBtn.className = "chat-task-review-reject";
      rejectBtn.textContent = "驳回";
      rejectBtn.addEventListener("click", () =>
        act(conversationId, item.reviewId, "reject")
      );

      actions.append(confirmBtn, rejectBtn);
      card.append(title, meta, preview, ack, actions);
      hostEl.append(card);
    }
  }

  async function act(conversationId, reviewId, op) {
    if (busy || !conversationId || !reviewId) return;
    busy = true;
    try {
      if (op === "confirm") {
        await confirmTaskReview(conversationId, reviewId);
      } else {
        await rejectTaskReview(conversationId, reviewId);
      }
      await refresh();
      if (typeof onChanged === "function") {
        await onChanged();
      }
    } catch (err) {
      console.warn("[chat] task-review " + op + " failed", err);
      alert((err && err.message) || "操作失败");
    } finally {
      busy = false;
    }
  }

  return { refresh };
}
