import { subscribeConversationEvents, getTurnStatus } from "/chat/api.js?v=20260925l";
import { applyStreamEvent } from "/chat/state.js?v=20260928b";
import { refreshHistoryTail } from "/chat/history.js?v=20260925i";

/**
 * 会话 SSE：按 selectionEpoch 防串线；有界指数退避重连。
 * 正常流结束（服务端超时等）静默续连；仅故障才显示「连接中断」。
 * 2.4.6：终态先喂 queue，再 render；titleChanged 交 title 守卫。
 */
export function createStreamController({
  getState,
  isEpochCurrent,
  onChange,
  onRefetch,
  queue,
  title,
  onComposerPaint,
}) {
  let subscription = null;
  let reconnectTimer = null;
  let attempt = 0;
  let closed = false;
  let connectionGeneration = 0;

  function stop() {
    closed = true;
    connectionGeneration += 1;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (subscription) {
      subscription.close();
      subscription = null;
    }
  }

  function start(conversationId, epoch, signal) {
    stop();
    closed = false;
    attempt = 0;
    if (queue) {
      queue.bindConversation(conversationId);
    }
    connect(conversationId, epoch, signal);
  }

  function connect(conversationId, epoch, signal) {
    if (closed || !conversationId) return;
    if (!isEpochCurrent(epoch, conversationId)) return;
    const connectionId = ++connectionGeneration;

    const state = getState();
    // 仅故障重试时显示文案；静默续连不抬 notice
    if (attempt > 0 && state.streamNotice) {
      onChange({ streamOnly: true });
    } else if (attempt === 0) {
      state.streamNotice = "";
      onChange({ streamOnly: true });
    }

    subscription = subscribeConversationEvents(conversationId, {
      cursor: state.outboxCursor || 0,
      signal,
      onEvent(type, data, id) {
        if (
          closed ||
          connectionId !== connectionGeneration ||
          !isEpochCurrent(epoch, conversationId)
        )
          return;
        const result = applyStreamEvent(getState(), conversationId, type, data, id);
        if (!result.changed) return;

        if (queue) {
          if (result.turnStarted && result.turnId) {
            queue.onTurnStarted(result.turnId);
          }
          if (result.turnTerminal && result.turnId) {
            const terminal =
              result.terminalStatus === "cancelled"
                ? "CANCELLED"
                : result.terminalStatus === "failed"
                  ? "FAILED"
                  : "COMPLETED";
            queue.onTurnTerminal(result.turnId, terminal);
          }
        }

        if (result.titleChanged && title && result.summary) {
          title.applyTitleEvent(result.summary, epoch, conversationId);
        }

        onChange({ fromStream: true });
        if (typeof onComposerPaint === "function") {
          onComposerPaint();
        }
        if (result.refetch && typeof onRefetch === "function") {
          onRefetch(conversationId, epoch);
        }
      },
      onError() {
        // api.js 只 throw，由 promise.catch 统一重连；此处留空避免双重 scheduleReconnect
      },
      onOpen() {
        if (
          closed ||
          connectionId !== connectionGeneration ||
          !isEpochCurrent(epoch, conversationId)
        )
          return;
        if (reconnectTimer) {
          clearTimeout(reconnectTimer);
          reconnectTimer = null;
        }
        attempt = 0;
        getState().streamNotice = "";
        onChange({ streamOnly: true });
      },
    });

    subscription.promise
      .then((result) => {
        if (
          closed ||
          connectionId !== connectionGeneration ||
          !isEpochCurrent(epoch, conversationId)
        )
          return;
        // 主动 abort / close：不重连
        if (result && result.aborted) return;
        // 流正常结束（含服务端 30m 超时）：静默续连，并校准未完成回合
        void calibrate(conversationId, epoch, signal);
        scheduleReconnect(conversationId, epoch, signal, connectionId, { silent: true });
      })
      .catch((err) => {
        if (
          closed ||
          connectionId !== connectionGeneration ||
          !isEpochCurrent(epoch, conversationId)
        )
          return;
        if (typeof onError === "function") {
          // 兼容旧调用方；本控制器主要走 catch
        }
        scheduleReconnect(conversationId, epoch, signal, connectionId, {
          silent: false,
          reasonCode: err && err.code ? String(err.code) : "NETWORK",
          httpStatus: err && err.status != null ? Number(err.status) : 0,
        });
        void calibrate(conversationId, epoch, signal);
      });
  }

  function scheduleReconnect(conversationId, epoch, signal, connectionId, opts) {
    const silent = !!(opts && opts.silent);
    const reasonCode = (opts && opts.reasonCode) || "";
    const httpStatus = (opts && opts.httpStatus) || 0;

    if (
      closed ||
      connectionId !== connectionGeneration ||
      !isEpochCurrent(epoch, conversationId) ||
      reconnectTimer
    )
      return;

    // 非法参数等：不重连
    if (
      !silent &&
      (httpStatus === 400 ||
        reasonCode === "ILLEGAL_ARGUMENT" ||
        reasonCode === "CONVERSATION_NOT_FOUND")
    ) {
      getState().streamNotice = "无法订阅会话事件";
      onChange({ streamOnly: true });
      return;
    }

    // 同会话订阅过多：停止狂重试
    if (!silent && reasonCode === "SSE_SUBSCRIBER_LIMIT") {
      getState().streamNotice = "同一会话订阅过多，请关闭多余标签页后刷新";
      onChange({ streamOnly: true });
      return;
    }

    if (attempt >= 8) {
      getState().streamNotice = "连接中断，请刷新或重新打开会话";
      onChange({ streamOnly: true });
      return;
    }

    let delay;
    if (silent) {
      // 正常续连：短延迟、不计失败 attempt、不弹文案
      delay = 250;
    } else {
      const busy =
        reasonCode === "RETRYABLE_BUSY" || httpStatus === 503 || httpStatus === 429;
      const base = Math.min(busy ? 20000 : 15000, (busy ? 800 : 500) * Math.pow(2, attempt));
      const jitter = Math.floor(Math.random() * (busy ? 800 : 400));
      delay = base + jitter;
      attempt += 1;
      getState().streamNotice =
        attempt <= 2
          ? "连接中断，正在重试"
          : "连接中断，正在重试（" + attempt + "/8）";
      onChange({ streamOnly: true });
    }

    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (
        closed ||
        connectionId !== connectionGeneration ||
        !isEpochCurrent(epoch, conversationId)
      )
        return;
      if (subscription) {
        subscription.close();
        subscription = null;
      }
      connect(conversationId, epoch, signal && signal.aborted ? undefined : signal);
    }, delay);
  }

  async function calibrate(conversationId, epoch, signal) {
    if (!isEpochCurrent(epoch, conversationId)) return;
    const state = getState();
    const turnIds = Object.keys(state.inflightByTurnId);
    for (const turnId of turnIds) {
      const status = await getTurnStatus(conversationId, turnId, { signal });
      if (!isEpochCurrent(epoch, conversationId) || status.aborted) return;
      if (status.ok && status.turnStatus) {
        const terminal = String(status.turnStatus).toUpperCase();
        if (
          terminal === "COMPLETED" ||
          terminal === "FAILED" ||
          terminal === "CANCELLED"
        ) {
          if (queue) queue.onTurnTerminal(turnId, terminal);
          applyStreamEvent(
            state,
            conversationId,
            terminal === "COMPLETED"
              ? "turn.completed"
              : terminal === "FAILED"
                ? "turn.failed"
                : "turn.cancelled",
            {
              turnId,
              errorCode: status.errorCode || "",
            },
            null
          );
          await refreshHistoryTail(state, conversationId, signal);
          if (state.inflightByTurnId[turnId]) {
            delete state.inflightByTurnId[turnId];
          }
        } else if (terminal === "COMMITTING" && queue) {
          queue.markCommitting(turnId);
        } else if (terminal === "RUNNING" && queue) {
          queue.onTurnStarted(turnId);
        } else if ((terminal === "RECEIVED" || terminal === "CLAIMED") && queue) {
          queue.onTurnAccepted({
            turnId,
            status: terminal,
            conversationId,
          });
        }
      }
    }
    onChange({ fromStream: true });
    if (typeof onComposerPaint === "function") {
      onComposerPaint();
    }
  }

  return { start, stop, calibrate };
}
