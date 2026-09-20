export function clearBanner(target) {
  target.className = "banner";
  target.textContent = "";
}

export function showError(target, body) {
  target.className = "banner error";
  const code = body && body.code ? body.code : "ERROR";
  const detail = body && body.detail ? body.detail : "请求失败";
  target.textContent = code + " " + detail;
}

export function showSuccess(target, message) {
  target.className = "banner success";
  target.textContent = message;
}

export function clearStatus(target) {
  target.replaceChildren();
  delete target.dataset.tone;
}

export function paintEnabled(target, enabled) {
  target.replaceChildren();
  const dot = document.createElement("span");
  dot.className = "status-dot";
  dot.setAttribute("aria-hidden", "true");
  const text = document.createElement("span");
  if (enabled) {
    target.dataset.tone = "ok";
    dot.setAttribute("data-tone", "ok");
    text.textContent = "当前启用：" + enabled.vendorId + " / " + enabled.modelId;
  } else {
    target.dataset.tone = "warn";
    text.textContent = "尚未启用";
  }
  target.append(dot, text);
}

export function renderLoading(target) {
  target.replaceChildren();
  const loading = document.createElement("p");
  loading.className = "loading";
  loading.setAttribute("aria-busy", "true");
  loading.textContent = "正在加载…";
  target.append(loading);
}

export function renderRetry(target, message, retry) {
  target.replaceChildren();
  const wrap = document.createElement("div");
  wrap.className = "empty-state";
  const line = document.createElement("p");
  line.textContent = message;
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = "重试";
  button.addEventListener("click", retry);
  wrap.append(line, button);
  target.append(wrap);
}
