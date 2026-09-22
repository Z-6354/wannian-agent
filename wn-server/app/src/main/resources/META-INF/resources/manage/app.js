import { getToken, isLocalHost, setToken } from "/manage/api.js?v=20260922t";
import { installMobileNavigation, renderAppNavigation } from "/shell/navigation.js?v=20260922t";
import { clearBanner, clearStatus } from "/manage/page-feedback.js?v=20260920p";
import { mountModelsPage } from "/manage/models-page.js?v=20260920p";
import { mountSystem } from "/manage/system-panel.js?v=20260921a";
import { mountToolsPage } from "/manage/tools-page.js?v=20260922v";
import { mountVendorsPage } from "/manage/vendors-page.js?v=20260920p";

const nav = document.querySelector("#manage-nav");
const title = document.querySelector("#page-title");
const enabledLine = document.querySelector("#enabled-line");
const pageActions = document.querySelector("#page-actions");
const banner = document.querySelector("#banner");
const panel = document.querySelector("#panel");
const tokenForm = document.querySelector("#token-form");
const tokenInput = document.querySelector("#token-input");
const skipLink = document.querySelector(".skip-link");

tokenForm.addEventListener("submit", (event) => {
  event.preventDefault();
  setToken(tokenInput.value.trim());
  tokenInput.value = "";
  clearBanner(banner);
  render();
});

if (skipLink) {
  skipLink.addEventListener("click", (event) => {
    event.preventDefault();
    panel.focus();
    panel.scrollIntoView({ block: "start" });
  });
}

window.addEventListener("hashchange", render);
renderAppNavigation(nav, current().path);
installMobileNavigation();
render();

function current() {
  const hash = location.hash.startsWith("#") ? location.hash.slice(1) : "vendors";
  const parsed = new URL(hash || "vendors", "http://wannian.local/");
  const path = parsed.pathname.replace(/^\//, "") || "vendors";
  return { path, params: parsed.searchParams };
}

function render() {
  const view = current();
  const local = isLocalHost();
  tokenForm.hidden = local;
  for (const link of nav.querySelectorAll(".nav-link")) {
    const active = link.dataset.nav === view.path;
    if (active) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  }
  pageActions.replaceChildren();
  if (view.path === "system") {
    title.textContent = "系统";
    clearStatus(enabledLine);
    clearBanner(banner);
    mountSystem({ main: panel, actions: pageActions, status: enabledLine, banner });
    return;
  }
  if (view.path === "tools") {
    title.textContent = "工具";
    clearStatus(enabledLine);
    clearBanner(banner);
    if (!local && !getToken()) {
      panel.replaceChildren();
      banner.className = "banner error";
      banner.textContent = "外网访问需要管理口令";
      return;
    }
    mountToolsPage({ main: panel, actions: pageActions, status: enabledLine, banner, route: view.params });
    return;
  }
  const page = view.path === "models" ? "models" : "vendors";
  title.textContent = page === "models" ? "模型" : "供应商";
  if (!local && !getToken()) {
    panel.replaceChildren();
    clearStatus(enabledLine);
    banner.className = "banner error";
    banner.textContent = "外网访问需要管理口令";
    return;
  }
  const pageView = { main: panel, actions: pageActions, status: enabledLine, banner, route: view.params };
  if (page === "models") {
    mountModelsPage(pageView);
  } else {
    mountVendorsPage(pageView);
  }
}
