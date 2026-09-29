/** 单页壳路由：hash → 工作台视图（chat | tasks | vendors | models | tools | system）。 */

export const APP_VIEWS = ["chat", "tasks", "vendors", "models", "tools", "system"];

export function parseAppRoute() {
  const hash = location.hash.startsWith("#") ? location.hash.slice(1) : "";
  if (!hash) {
    return { path: "chat", params: new URLSearchParams() };
  }
  const parsed = new URL(hash, "http://wannian.local/");
  const path = parsed.pathname.replace(/^\//, "") || "chat";
  if (!APP_VIEWS.includes(path)) {
    return { path: "chat", params: parsed.searchParams };
  }
  return { path, params: parsed.searchParams };
}

export function applyViewVisibility(route) {
  const path = route?.path || "chat";
  const isChat = path === "chat";
  const chat = document.querySelector("#view-chat");
  const consoleView = document.querySelector("#view-console");
  if (chat) chat.hidden = !isChat;
  if (consoleView) consoleView.hidden = isChat;
  document.body.dataset.view = isChat ? "chat" : "console";
  return path;
}

/** 切到指定视图；已在目标则只同步显隐。 */
export function ensureView(path) {
  const target = APP_VIEWS.includes(path) ? path : "chat";
  applyViewVisibility({ path: target });
  if (parseAppRoute().path !== target) {
    location.hash = target;
  }
  return target;
}

export function syncNavCurrent(nav, path) {
  if (!nav) return;
  for (const link of nav.querySelectorAll(".nav-link")) {
    if (link.dataset.nav === path) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  }
}
