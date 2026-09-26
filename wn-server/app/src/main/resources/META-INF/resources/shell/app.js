import { installMobileNavigation, renderAppNavigation } from "/shell/navigation.js?v=20260925c";
import {
  applyViewVisibility,
  ensureView,
  parseAppRoute,
  syncNavCurrent,
} from "/shell/router.js?v=20260925a";
import { startChatApp } from "/chat/app.js?v=20260926c";
import { createConsoleController } from "/manage/console.js?v=20260925r";

const nav = document.querySelector("#app-nav");
const skipLink = document.querySelector(".skip-link");

const consoleCtrl = createConsoleController({
  title: document.querySelector("#page-title"),
  enabledLine: document.querySelector("#enabled-line"),
  pageActions: document.querySelector("#page-actions"),
  banner: document.querySelector("#banner"),
  panel: document.querySelector("#panel"),
  tokenForm: document.querySelector("#token-form"),
  tokenInput: document.querySelector("#token-input"),
});

function activate() {
  const route = parseAppRoute();
  applyViewVisibility(route);
  syncNavCurrent(nav, route.path);
  if (route.path === "chat") {
    if (skipLink) skipLink.setAttribute("href", "#transcript");
    return;
  }
  if (skipLink) skipLink.setAttribute("href", "#panel");
  consoleCtrl.render(route);
}

if (skipLink) {
  skipLink.addEventListener("click", (event) => {
    event.preventDefault();
    const route = parseAppRoute();
    const target =
      route.path === "chat"
        ? document.querySelector("#transcript")
        : document.querySelector("#panel");
    target?.focus();
    target?.scrollIntoView({ block: "start" });
  });
}

renderAppNavigation(nav, parseAppRoute().path);
installMobileNavigation();
window.addEventListener("hashchange", activate);

startChatApp({ ensureView });
activate();
