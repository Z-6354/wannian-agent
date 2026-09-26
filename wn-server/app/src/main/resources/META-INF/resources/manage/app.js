/**
 * 管理模块门面：页面已并入 `/#…` 单页壳。
 * 保留 mount* 与 pageView 窄接口，供测试与旧引用。
 */
export { createConsoleController } from "/manage/console.js?v=20260925r";
export { mountModelsPage } from "/manage/models-page.js?v=20260920p";
export { mountSystem } from "/manage/system-panel.js?v=20260924a";
export { mountToolsPage } from "/manage/tools-page.js?v=20260923b";
export { mountVendorsPage } from "/manage/vendors-page.js?v=20260925r";

import { mountModelsPage } from "/manage/models-page.js?v=20260920p";
import { mountVendorsPage } from "/manage/vendors-page.js?v=20260925r";

/** @param {{ path: string, params?: URLSearchParams }} view @param {{ main: HTMLElement, actions: HTMLElement, status: HTMLElement, banner: HTMLElement }} els */
export function mountConsolePage(view, els) {
  const page = view.path === "models" ? "models" : "vendors";
  const pageView = {
    main: els.main,
    actions: els.actions,
    status: els.status,
    banner: els.banner,
    route: view.params,
  };
  if (page === "models") {
    mountModelsPage(pageView);
  } else {
    mountVendorsPage(pageView);
  }
}
