import { stopTurn, cancelQueuedTurn, getTurnStatus } from "/chat/api.js?v=20260928f";

/**
 * composer：Stop 替换发送按钮；排队提示 / 撤队仍走 toolbar（2.4.6）。
 * 禁止在未确认服务端终态前显示「已停止」。
 */
export function createComposerController({
  els,
  getState,
  queue,
  isEpochCurrent,
  setNotice,
  requestRender,
}) {
  const leading = els.composerToolbar
    ? els.composerToolbar.querySelector(".composer-toolbar-leading")
    : null;

  let stopBtn = els.stopButton || document.querySelector("#stop-turn");
  let queueHint = null;
  let queueList = null;

  function ensureDom() {
    if (!stopBtn) {
      const row = els.form && els.form.querySelector(".composer-input-row");
      stopBtn = document.createElement("button");
      stopBtn.type = "button";
      stopBtn.id = "stop-turn";
      stopBtn.className = "composer-stop";
      stopBtn.hidden = true;
      stopBtn.textContent = "停止";
      if (row) {
        row.append(stopBtn);
      }
    }
    if (!stopBtn.dataset.bound) {
      stopBtn.addEventListener("click", () => onStopClick());
      stopBtn.dataset.bound = "1";
    }
    if (!leading) return;
    if (!queueHint) {
      queueHint = document.createElement("p");
      queueHint.id = "queue-hint";
      queueHint.className = "chat-queue-hint";
      queueHint.hidden = true;
      leading.append(queueHint);
    }
    if (!queueList) {
      queueList = document.createElement("ul");
      queueList.className = "chat-queue-list";
      queueList.hidden = true;
      leading.append(queueList);
    }
  }

  async function onStopClick() {
    ensureDom();
    const state = getState();
    const conversationId = state.activeConversationId;
    const turnId = queue.getRunningTurnId();
    const epoch = state.selectionEpoch;
    if (!conversationId || !turnId || !queue.canStop()) {
      return;
    }
    queue.markStopping(turnId);
    paint();
    const res = await stopTurn(conversationId, turnId);
    if (!isEpochCurrent(epoch, conversationId)) return;

    if (!res.ok) {
      if (res.code === "STOP_NOT_ALLOWED" || res.turnStatus === "COMMITTING") {
        queue.markCommitting(turnId);
        setNotice(res.detail || "正在完成提交，无法停止", "");
      } else {
        queue.clearStopping(turnId);
        setNotice(res.detail || "停止请求失败", "error");
      }
      paint();
      requestRender();
      return;
    }

    const status = String(res.turnStatus || "").toUpperCase();
    if (status === "COMMITTING") {
      queue.markCommitting(turnId);
      setNotice("正在完成提交，无法停止", "");
      paint();
      requestRender();
      return;
    }
    if (status === "CANCELLED" || status === "COMPLETED" || status === "FAILED") {
      queue.onTurnTerminal(turnId, status);
      setNotice(status === "CANCELLED" ? "已取消" : "", "");
      paint();
      requestRender();
      return;
    }
    setNotice("");
    paint();
    requestRender();
    calibrateStop(conversationId, turnId, epoch);
  }

  async function calibrateStop(conversationId, turnId, epoch) {
    const status = await getTurnStatus(conversationId, turnId);
    if (!isEpochCurrent(epoch, conversationId)) return;
    if (!status.ok) return;
    const st = String(status.turnStatus || "").toUpperCase();
    if (st === "COMMITTING") {
      queue.markCommitting(turnId);
      setNotice("正在完成提交，无法停止", "");
      paint();
      requestRender();
      return;
    }
    if (st === "CANCELLED" || st === "COMPLETED" || st === "FAILED") {
      queue.onTurnTerminal(turnId, st);
      if (st === "CANCELLED") {
        setNotice("已取消", "");
      }
      paint();
      requestRender();
    }
  }

  async function onCancelQueued(turnId) {
    const state = getState();
    const conversationId = state.activeConversationId;
    const epoch = state.selectionEpoch;
    if (!conversationId || !queue.canCancel(turnId)) return;
    const res = await cancelQueuedTurn(conversationId, turnId);
    if (!isEpochCurrent(epoch, conversationId)) return;
    if (!res.ok) {
      if (res.code === "STOP_NOT_ALLOWED") {
        setNotice(res.detail || "该项已不可撤销", "");
      } else {
        setNotice(res.detail || "撤队失败", "error");
      }
      paint();
      return;
    }
    const status = String(res.turnStatus || "CANCELLED").toUpperCase();
    queue.onTurnTerminal(turnId, status);
    setNotice(status === "CANCELLED" ? "已取消排队" : "", "");
    paint();
    requestRender();
  }

  function paint() {
    ensureDom();
    if (!stopBtn) return;

    const snap = queue.snapshot();
    const queued = snap.queued || [];
    const stopping = Boolean(snap.stoppingTurnId);
    const committing = Boolean(snap.committingTurnId);
    const submitting = Boolean(getState().submitting);
    const canStop = queue.canStop();
    // 发送后直接进停止位；完毕后回发送。不再经过「发送中」文案态。
    const showStop = submitting || committing || stopping || canStop;

    if (committing) {
      stopBtn.hidden = false;
      stopBtn.disabled = true;
      stopBtn.textContent = "提交中";
      stopBtn.title = "正在完成提交，无法停止";
    } else if (stopping) {
      stopBtn.hidden = false;
      stopBtn.disabled = true;
      stopBtn.textContent = "停止中";
      stopBtn.title = "正在停止…";
    } else if (canStop) {
      stopBtn.hidden = false;
      stopBtn.disabled = false;
      stopBtn.textContent = "停止";
      stopBtn.title = "停止当前回合";
    } else if (submitting) {
      stopBtn.hidden = false;
      stopBtn.disabled = true;
      stopBtn.textContent = "停止";
      stopBtn.title = "正在接通回合…";
    } else {
      stopBtn.hidden = true;
      stopBtn.disabled = true;
      stopBtn.textContent = "停止";
      stopBtn.title = "";
    }

    if (els.sendButton) {
      els.sendButton.hidden = showStop;
      if (!showStop) {
        els.sendButton.textContent = "发送";
        els.sendButton.disabled = false;
      }
    }

    if (queueHint && queueList) {
      if (queued.length > 0) {
        queueHint.hidden = false;
        queueHint.textContent =
          queued.length === 1 ? "另 1 条已排队" : "另 " + queued.length + " 条已排队";
        queueList.hidden = false;
        queueList.replaceChildren();
        for (const turnId of queued) {
          const li = document.createElement("li");
          li.className = "chat-queue-item";
          li.dataset.turnId = turnId;
          li.dataset.turnStatus = "queued";
          const label = document.createElement("span");
          label.className = "chat-queue-item-label";
          label.textContent = "已排队 · " + shortId(turnId);
          li.append(label);
          if (queue.canCancel(turnId)) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "ghost chat-queue-cancel";
            btn.textContent = "撤销";
            btn.addEventListener("click", () => onCancelQueued(turnId));
            li.append(btn);
          }
          queueList.append(li);
        }
      } else {
        queueHint.hidden = true;
        queueHint.textContent = "";
        queueList.hidden = true;
        queueList.replaceChildren();
      }
    }

    if (els.composerToolbar) {
      const queueActive =
        Boolean(queueHint && !queueHint.hidden) || Boolean(queueList && !queueList.hidden);
      els.composerToolbar.classList.toggle("is-active", queueActive);
    }
  }

  function reset() {
    queue.reset(getState().activeConversationId || "");
    paint();
  }

  return { paint, reset, onCancelQueued };
}

function shortId(id) {
  if (!id || id.length < 8) return id || "";
  return id.slice(0, 8);
}
