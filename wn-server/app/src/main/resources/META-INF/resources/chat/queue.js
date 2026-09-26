/**
 * 每会话 followup 队列状态机（0.2.4-E）。
 * 真源顺序以服务端 receive / SSE 为准；不以前端点击先后为准。
 */

export function createQueueController() {
  /** @type {{ conversationId: string, runningTurnId: string|null, stoppingTurnId: string|null, committingTurnId: string|null, queued: string[], byTurn: Record<string, string> }} */
  let state = emptyState("");

  function reset(conversationId) {
    state = emptyState(conversationId || "");
  }

  function bindConversation(conversationId) {
    if (state.conversationId !== conversationId) {
      reset(conversationId);
    }
  }

  function onTurnAccepted({ turnId, status, replayed, conversationId }) {
    if (!turnId) return;
    if (conversationId) bindConversation(conversationId);
    if (replayed && state.byTurn[turnId]) {
      return;
    }
    const st = String(status || "RECEIVED").toUpperCase();
    state.byTurn[turnId] = st;
    if (st === "RUNNING" || st === "COMMITTING") {
      removeQueued(turnId);
      state.runningTurnId = turnId;
      if (st === "COMMITTING") {
        state.committingTurnId = turnId;
      }
    } else if (st === "RECEIVED" || st === "CLAIMED") {
      const othersBusy = Object.entries(state.byTurn).some(([id, statusName]) => {
        if (id === turnId) return false;
        const s = String(statusName || "").toUpperCase();
        return (
          s === "RECEIVED" ||
          s === "CLAIMED" ||
          s === "RUNNING" ||
          s === "COMMITTING"
        );
      });
      if (
        (othersBusy || (state.runningTurnId && state.runningTurnId !== turnId)) &&
        !state.queued.includes(turnId)
      ) {
        state.queued.push(turnId);
      } else {
        // 队头：RECEIVED/CLAIMED 即可 Stop（服务端 RECEIVED→撤队，CLAIMED/RUNNING→取消）
        removeQueued(turnId);
        state.runningTurnId = turnId;
      }
    }
  }

  function onTurnStarted(turnId) {
    if (!turnId) return;
    removeQueued(turnId);
    state.runningTurnId = turnId;
    state.byTurn[turnId] = "RUNNING";
    if (state.committingTurnId === turnId) {
      state.committingTurnId = null;
    }
  }

  function onTurnTerminal(turnId, status) {
    if (!turnId) return;
    const st = String(status || "").toUpperCase();
    state.byTurn[turnId] = st || "TERMINAL";
    removeQueued(turnId);
    if (state.runningTurnId === turnId) {
      state.runningTurnId = null;
    }
    if (state.stoppingTurnId === turnId) {
      state.stoppingTurnId = null;
    }
    if (state.committingTurnId === turnId) {
      state.committingTurnId = null;
    }
  }

  function markStopping(turnId) {
    if (!turnId) return;
    state.stoppingTurnId = turnId;
  }

  function markCommitting(turnId) {
    if (!turnId) return;
    state.committingTurnId = turnId;
    state.byTurn[turnId] = "COMMITTING";
    if (state.stoppingTurnId === turnId) {
      state.stoppingTurnId = null;
    }
  }

  function clearStopping(turnId) {
    if (!turnId || state.stoppingTurnId === turnId) {
      state.stoppingTurnId = null;
    }
  }

  function removeQueued(turnId) {
    state.queued = state.queued.filter((id) => id !== turnId);
  }

  function getRunningTurnId() {
    return state.runningTurnId;
  }

  function getQueuedTurnIds() {
    return state.queued.slice();
  }

  function getStoppingTurnId() {
    return state.stoppingTurnId;
  }

  function canStop() {
    return Boolean(
      state.runningTurnId &&
        state.runningTurnId !== state.committingTurnId &&
        state.stoppingTurnId !== state.runningTurnId
    );
  }

  function canCancel(turnId) {
    if (!turnId) return false;
    if (turnId === state.runningTurnId) return false;
    const st = state.byTurn[turnId];
    return state.queued.includes(turnId) && (!st || st === "RECEIVED" || st === "CLAIMED");
  }

  function isStopping() {
    return Boolean(state.stoppingTurnId);
  }

  function isCommitting() {
    return Boolean(state.committingTurnId);
  }

  function snapshot() {
    return {
      conversationId: state.conversationId,
      runningTurnId: state.runningTurnId,
      stoppingTurnId: state.stoppingTurnId,
      committingTurnId: state.committingTurnId,
      queued: state.queued.slice(),
      byTurn: { ...state.byTurn },
    };
  }

  return {
    reset,
    bindConversation,
    onTurnAccepted,
    onTurnStarted,
    onTurnTerminal,
    markStopping,
    markCommitting,
    clearStopping,
    getRunningTurnId,
    getQueuedTurnIds,
    getStoppingTurnId,
    canStop,
    canCancel,
    isStopping,
    isCommitting,
    snapshot,
  };
}

function emptyState(conversationId) {
  return {
    conversationId: conversationId || "",
    runningTurnId: null,
    stoppingTurnId: null,
    committingTurnId: null,
    queued: [],
    byTurn: Object.create(null),
  };
}
