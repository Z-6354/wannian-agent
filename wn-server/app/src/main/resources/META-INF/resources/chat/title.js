/**
 * 首轮自动标题消费与 MANUAL 守卫（0.2.4-E）。
 */

const TITLE_FAIL_KEY = "wannian.chat.titleFailNoticed";

export function createTitleController({ getState, isEpochCurrent, onTitleApplied, setNotice }) {
  function applyTitleEvent(evt, epoch, conversationId) {
    if (!isEpochCurrent(epoch, conversationId)) return false;
    if (!evt || typeof evt !== "object") return false;
    const id = stringOf(evt.conversationId) || conversationId;
    if (!id || id !== conversationId) return false;

    const state = getState();
    const local =
      (state.header && state.header.id === id && state.header) ||
      state.conversationsById[id] ||
      null;
    const localSource = local ? String(local.titleSource || "").toUpperCase() : "";
    const localRevision = local && typeof local.revision === "number" ? local.revision : 0;
    const evtRevision = typeof evt.revision === "number" ? evt.revision : -1;
    const evtSource = String(evt.titleSource || "AUTO").toUpperCase();

    // MANUAL 胜出：本地已手动改名则忽略迟到 auto
    if (localSource === "MANUAL") {
      if (evtSource === "AUTO") return false;
      if (evtRevision >= 0 && evtRevision <= localRevision) return false;
    }
    if (evtSource === "AUTO" && localSource === "MANUAL") {
      return false;
    }

    const title = stringOf(evt.title);
    if (!title) return false;

    const summary = {
      id,
      title,
      titleSource: evtSource || "AUTO",
      revision: evtRevision >= 0 ? evtRevision : localRevision + 1,
      status: (local && local.status) || (state.header && state.header.status) || "ACTIVE",
    };
    if (typeof onTitleApplied === "function") {
      onTitleApplied(summary);
    }
    return true;
  }

  function markManualTitle(id, title, newRevision) {
    const state = getState();
    const summary = {
      id,
      title,
      titleSource: "MANUAL",
      revision: typeof newRevision === "number" ? newRevision : (state.header.revision || 0) + 1,
      status: state.header.status || "ACTIVE",
    };
    if (typeof onTitleApplied === "function") {
      onTitleApplied(summary);
    }
  }

  function noticeTitleFailedOnce() {
    try {
      if (sessionStorage.getItem(TITLE_FAIL_KEY) === "1") return;
      sessionStorage.setItem(TITLE_FAIL_KEY, "1");
    } catch (_error) {
      /* ignore */
    }
    if (typeof setNotice === "function") {
      setNotice("标题生成失败，对话正常", "");
    }
  }

  return { applyTitleEvent, markManualTitle, noticeTitleFailedOnce };
}

function stringOf(value) {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}
