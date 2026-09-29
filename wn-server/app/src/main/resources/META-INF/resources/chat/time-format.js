/**
 * 对话/任务时间显示（对标 DSH formatMessageClock：同日 HH:mm，更早带日期；最小单位分钟）。
 */

function pad2(n) {
  return String(n).padStart(2, "0");
}

function toDate(isoOrMs) {
  if (isoOrMs == null || isoOrMs === "") return null;
  if (typeof isoOrMs === "number" && Number.isFinite(isoOrMs)) {
    const d = new Date(isoOrMs);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  const d = new Date(String(isoOrMs));
  return Number.isNaN(d.getTime()) ? null : d;
}

function startOfLocalDay(d) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/**
 * @param {string|number|null|undefined} iso
 * @param {number} [nowMs]
 * @returns {string} 空串表示无时间
 */
export function formatMessageClock(iso, nowMs = Date.now()) {
  const d = toDate(iso);
  if (!d) return "";
  const clock = pad2(d.getHours()) + ":" + pad2(d.getMinutes());
  const now = new Date(nowMs);
  const dayDiff = Math.round(
    (startOfLocalDay(d) - startOfLocalDay(now)) / 86400000
  );
  if (dayDiff === 0) return clock;
  if (dayDiff === -1) return "昨天 " + clock;
  if (d.getFullYear() === now.getFullYear()) {
    return pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) + " " + clock;
  }
  return (
    d.getFullYear() +
    "-" +
    pad2(d.getMonth() + 1) +
    "-" +
    pad2(d.getDate()) +
    " " +
    clock
  );
}

/** 任务中心：同样规则，允许略长上下文。 */
export function formatTaskClock(iso, nowMs = Date.now()) {
  return formatMessageClock(iso, nowMs);
}

/** 客户端即时时间戳（ISO）。 */
export function nowIso() {
  return new Date().toISOString();
}
