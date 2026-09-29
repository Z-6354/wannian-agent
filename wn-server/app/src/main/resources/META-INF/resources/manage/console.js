import { getToken, isLocalHost, setToken } from "/manage/api.js?v=20260925a";
import { clearBanner, clearStatus } from "/manage/page-feedback.js?v=20260920p";
import { mountModelsPage } from "/manage/models-page.js?v=20260920p";
import { mountSystem } from "/manage/system-panel.js?v=20260924a";
import { mountTasksPage } from "/manage/tasks-page.js?v=20260928s";
import { mountToolsPage } from "/manage/tools-page.js?v=20260923b";
import { mountVendorsPage } from "/manage/vendors-page.js?v=20260925r";

/**
 * 控制台面板（供应商 / 模型 / 工具 / 系统）挂到壳层 #view-console。
 * @param {{ title: HTMLElement, enabledLine: HTMLElement, pageActions: HTMLElement, banner: HTMLElement, panel: HTMLElement, tokenForm: HTMLFormElement, tokenInput: HTMLInputElement }} els
 */
export function createConsoleController(els) {
  let wired = false;

  function wireOnce() {
    if (wired) return;
    wired = true;
    els.tokenForm.addEventListener("submit", (event) => {
      event.preventDefault();
      setToken(els.tokenInput.value.trim());
      els.tokenInput.value = "";
      clearBanner(els.banner);
      render(currentRoute());
    });
  }

  function currentRoute() {
    const hash = location.hash.startsWith("#") ? location.hash.slice(1) : "vendors";
    const parsed = new URL(hash || "vendors", "http://wannian.local/");
    const path = parsed.pathname.replace(/^\//, "") || "vendors";
    return { path, params: parsed.searchParams };
  }

  function render(view) {
    wireOnce();
    const route = view || currentRoute();
    const local = isLocalHost();
    els.tokenForm.hidden = local;
    els.pageActions.replaceChildren();
    if (route.path === "system") {
      els.title.textContent = "系统";
      clearStatus(els.enabledLine);
      clearBanner(els.banner);
      mountSystem({
        main: els.panel,
        actions: els.pageActions,
        status: els.enabledLine,
        banner: els.banner,
      });
      return;
    }
    if (route.path === "tasks") {
      els.title.textContent = "任务";
      clearStatus(els.enabledLine);
      clearBanner(els.banner);
      mountTasksPage({
        main: els.panel,
        actions: els.pageActions,
        status: els.enabledLine,
        banner: els.banner,
        route: route.params,
      });
      return;
    }
    if (route.path === "tools") {
      els.title.textContent = "工具";
      clearStatus(els.enabledLine);
      clearBanner(els.banner);
      if (!local && !getToken()) {
        els.panel.replaceChildren();
        els.banner.className = "banner error";
        els.banner.textContent = "外网访问需要管理口令";
        return;
      }
      mountToolsPage({
        main: els.panel,
        actions: els.pageActions,
        status: els.enabledLine,
        banner: els.banner,
        route: route.params,
      });
      return;
    }
    const page = route.path === "models" ? "models" : "vendors";
    els.title.textContent = page === "models" ? "模型" : "供应商";
    if (!local && !getToken()) {
      els.panel.replaceChildren();
      clearStatus(els.enabledLine);
      els.banner.className = "banner error";
      els.banner.textContent = "外网访问需要管理口令";
      return;
    }
    const pageView = {
      main: els.panel,
      actions: els.pageActions,
      status: els.enabledLine,
      banner: els.banner,
      route: route.params,
    };
    if (page === "models") {
      mountModelsPage(pageView);
    } else {
      mountVendorsPage(pageView);
    }
  }

  return { render };
}
