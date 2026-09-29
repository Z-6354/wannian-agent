import { listMessages, getConversation, recentConversation } from "/chat/api.js?v=20260928n";
import {
  applyHistoryPage,
  getCommitted,
  setConversationHeader,
  upsertConversation,
} from "/chat/state.js?v=20260928n";

const PAGE_LIMIT = 100;

/**
 * 分页拉满已提交历史（includeToolCalls=true）。
 */
export async function loadFullHistory(conversationId, signal, state, outboxCursor = 0) {
  const cursor = safeOutboxCursor(outboxCursor);
  if (!conversationId) {
    return { ok: true, messages: [], outboxCursor: cursor, aborted: false };
  }
  let afterSeq = 0;
  let guard = 0;
  const collected = [];
  while (guard < 50) {
    guard += 1;
    const page = await listMessages(conversationId, afterSeq, PAGE_LIMIT, true, { signal });
    if (page.aborted) {
      return { ok: false, aborted: true, messages: collected };
    }
    if (!page.ok) {
      return {
        ok: false,
        aborted: false,
        detail: page.detail || "无法加载历史",
        code: page.code,
        messages: collected,
      };
    }
    const batch = page.messages || [];
    collected.push(...batch);
    applyHistoryPage(state, conversationId, batch, { replace: afterSeq === 0 && guard === 1 });
    if (page.nextAfterSeq == null) {
      break;
    }
    if (page.nextAfterSeq === afterSeq) {
      break;
    }
    afterSeq = page.nextAfterSeq;
    if (batch.length === 0) {
      break;
    }
  }
  return { ok: true, aborted: false, messages: collected, outboxCursor: cursor };
}

/**
 * 打开页：recent → 详情 + 全量历史；零模型调用。
 */
export async function bootstrapRecent(state, signal) {
  const recent = await recentConversation({ signal });
  if (recent.aborted) {
    return { ok: false, aborted: true };
  }
  if (recent.status === 204 || recent.result === "empty" || !recent.conversation) {
    return { ok: true, empty: true, conversation: null };
  }
  const summary = recent.conversation;
  upsertConversation(state, summary);
  setConversationHeader(state, summary);
  const history = await loadFullHistory(summary.id, signal, state, recent.outboxCursor);
  if (history.aborted) {
    return { ok: false, aborted: true };
  }
  if (!history.ok) {
    return {
      ok: false,
      aborted: false,
      conversation: summary,
      detail: history.detail,
      code: history.code,
    };
  }
  return {
    ok: true,
    empty: false,
    conversation: summary,
    outboxCursor: history.outboxCursor,
  };
}

export async function openConversation(state, conversationId, signal) {
  const detail = await getConversation(conversationId, { signal });
  if (detail.aborted) {
    return { ok: false, aborted: true };
  }
  if (!detail.ok || !detail.conversation) {
    return {
      ok: false,
      aborted: false,
      detail: detail.detail || "会话不存在",
      code: detail.code,
      status: detail.status,
    };
  }
  const summary = detail.conversation;
  upsertConversation(state, summary);
  setConversationHeader(state, summary);
  const history = await loadFullHistory(summary.id, signal, state, detail.outboxCursor);
  if (history.aborted) {
    return { ok: false, aborted: true };
  }
  if (!history.ok) {
    return {
      ok: false,
      aborted: false,
      conversation: summary,
      detail: history.detail,
      code: history.code,
    };
  }
  return { ok: true, conversation: summary, outboxCursor: history.outboxCursor };
}

/** turn 终态后补拉缺口，用正式 Message 校准临时块。 */
export async function refreshHistoryTail(state, conversationId, signal) {
  const bucket = getCommitted(state, conversationId);
  const afterSeq = Math.max(0, (bucket.lastSeq || 0) - 5);
  const page = await listMessages(conversationId, afterSeq, PAGE_LIMIT, true, { signal });
  if (page.aborted || !page.ok) {
    return page;
  }
  applyHistoryPage(state, conversationId, page.messages || [], { replace: false });
  pruneReplacedOptimistic(bucket, page.messages || []);
  return page;
}

/** 用服务端 Message 替换同 turn 的 local-* optimistic；保留 unfinished 临时投影。 */
function pruneReplacedOptimistic(bucket, messages) {
  const serverTurnIds = new Set();
  for (const raw of messages) {
    if (raw && raw.turnId) serverTurnIds.add(String(raw.turnId));
  }
  bucket.items = bucket.items.filter((item) => {
    if (item.unfinished && item.temporary) {
      return true;
    }
    if (item.optimistic || (typeof item.messageId === "string" && item.messageId.startsWith("local-"))) {
      if (item.turnId && serverTurnIds.has(String(item.turnId))) {
        // 同 turn 已有正式消息时，丢掉乐观块（merge 可能已用正式项覆盖 key）
        const hasOfficial = bucket.items.some(
          (other) =>
            other !== item &&
            other.turnId === item.turnId &&
            other.kind === item.kind &&
            !other.temporary &&
            !other.optimistic
        );
        if (hasOfficial) return false;
      }
    }
    return true;
  });
}

function safeOutboxCursor(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
