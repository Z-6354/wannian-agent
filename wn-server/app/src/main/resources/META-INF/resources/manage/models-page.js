import { addListed, enableModel, listModels, removeListed } from "/manage/api.js?v=20260920p";
import { loadModelOverview } from "/manage/model-data.js?v=20260920p";
import { clearBanner, paintEnabled, renderLoading, renderRetry, showError, showSuccess } from "/manage/page-feedback.js?v=20260920p";

export async function mountModelsPage(view) {
  const state = {
    vendors: [],
    listed: [],
    enabled: null,
    catalog: null,
    catalogVendorId: view.route.get("vendor") || "",
    selectedModel: "",
    selectedVendor: "",
    pending: "",
  };

  async function refresh() {
    const result = await loadModelOverview();
    if (!result.ok) {
      showError(view.banner, result);
      return false;
    }
    state.vendors = result.vendors;
    state.listed = result.listed;
    state.enabled = result.enabled;
    clearBanner(view.banner);
    return true;
  }

  async function refreshCatalog() {
    if (!state.catalogVendorId) {
      state.catalog = null;
      return true;
    }
    const result = await listModels(state.catalogVendorId);
    if (!result.ok) {
      showError(view.banner, result);
      state.catalog = null;
      return false;
    }
    state.catalog = result.body.entries || [];
    clearBanner(view.banner);
    return true;
  }

  function render() {
    view.actions.replaceChildren();
    paintEnabled(view.status, state.enabled);
    view.main.replaceChildren();
    const page = document.createElement("section");
    page.className = "content-page";
    page.append(section("已加入列表", "这些模型会写入数据库，刷新后仍在。", listedBlock()));
    page.append(section("本次检索", "从供应商拉来的目录只在这次页面里，不写入磁盘。", catalogBlock()));
    view.main.append(page);
  }

  function listedBlock() {
    if (!state.listed.length) {
      return empty(state.vendors.length ? "还没有加入列表的模型。在检索结果里点「加入列表」。" : "还没有供应商。请先打开主导航中的「供应商」添加连接。");
    }
    const wrap = document.createElement("div");
    wrap.className = "table-wrap";
    const table = document.createElement("table");
    const caption = document.createElement("caption");
    caption.className = "sr-only";
    caption.textContent = "已加入列表的模型";
    const head = document.createElement("thead");
    head.append(headerRow(["选择", "供应商", "modelId", "名称", "当前使用", "操作"]));
    const body = document.createElement("tbody");
    table.append(caption, head, body);
    let enable;
    for (const entry of state.listed) {
      const row = document.createElement("tr");
      const pick = document.createElement("td");
      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = "listedModel";
      radio.setAttribute("aria-label", "选择 " + entry.vendorId + " / " + entry.modelId);
      if (entry.enabled) {
        radio.checked = true;
        state.selectedVendor = entry.vendorId;
        state.selectedModel = entry.modelId;
      }
      radio.addEventListener("change", () => {
        state.selectedVendor = entry.vendorId;
        state.selectedModel = entry.modelId;
        enable.disabled = false;
        enable.title = "";
      });
      pick.append(radio);
      const vendor = cell(entry.vendorId, "mono");
      const id = cell(entry.modelId, "mono");
      const name = cell(entry.displayName || "");
      const current = document.createElement("td");
      if (entry.enabled) current.append(status("当前使用"));
      const action = document.createElement("td");
      const remove = button("移出", "secondary", async () => {
        if (state.pending) return;
        state.pending = "unlist:" + entry.vendorId + "/" + entry.modelId;
        remove.disabled = true;
        remove.setAttribute("aria-busy", "true");
        const result = await removeListed(entry.vendorId, entry.modelId);
        state.pending = "";
        if (!result.ok) {
          showError(view.banner, result);
          remove.disabled = false;
          remove.removeAttribute("aria-busy");
          return;
        }
        if (await refresh()) render();
      });
      action.append(remove);
      row.append(pick, vendor, id, name, current, action);
      body.append(row);
    }
    enable = button("启用所选", "", async () => {
      if (!state.selectedModel || !state.selectedVendor || state.pending) {
        showError(view.banner, { code: "ILLEGAL_ARGUMENT", detail: "先在已加入列表里选择一个模型" });
        return;
      }
      state.pending = "enable";
      enable.disabled = true;
      enable.setAttribute("aria-busy", "true");
      const result = await enableModel(state.selectedVendor, state.selectedModel);
      state.pending = "";
      if (!result.ok) {
        showError(view.banner, result);
        enable.disabled = false;
        enable.removeAttribute("aria-busy");
        return;
      }
      if (await refresh()) {
        showSuccess(view.banner, "已更新当前启用模型");
        render();
      }
    });
    enable.disabled = !state.selectedModel || !state.selectedVendor || Boolean(state.pending);
    if (enable.disabled) enable.title = "先在列表里选择一个模型";
    const actions = document.createElement("div");
    actions.className = "table-actions";
    actions.append(enable);
    wrap.append(table, actions);
    return wrap;
  }

  function catalogBlock() {
    if (!state.catalogVendorId || !state.catalog) {
      return empty(state.vendors.length ? "尚未检索。在供应商页面点「检索模型」。" : "供应商未配置。");
    }
    if (!state.catalog.length) return empty("该供应商本次检索结果为空。");
    const wrap = document.createElement("div");
    wrap.className = "table-wrap";
    const table = document.createElement("table");
    const caption = document.createElement("caption");
    caption.className = "sr-only";
    caption.textContent = "供应商 " + state.catalogVendorId + " 的检索结果";
    const head = document.createElement("thead");
    head.append(headerRow(["modelId", "名称", "操作"]));
    const body = document.createElement("tbody");
    table.append(caption, head, body);
    for (const entry of state.catalog) {
      const row = document.createElement("tr");
      const action = document.createElement("td");
      const add = button("加入列表", "secondary", async () => {
        if (state.pending) return;
        state.pending = "list:" + entry.id;
        add.disabled = true;
        add.setAttribute("aria-busy", "true");
        const result = await addListed(state.catalogVendorId, entry.id);
        state.pending = "";
        if (!result.ok) {
          showError(view.banner, result);
          add.disabled = false;
          add.removeAttribute("aria-busy");
          return;
        }
        if (await refresh()) {
          showSuccess(view.banner, "已加入列表");
          render();
        }
      });
      action.append(add);
      row.append(cell(entry.id, "mono"), cell(entry.displayName || ""), action);
      body.append(row);
    }
    wrap.append(table);
    return wrap;
  }

  view.actions.replaceChildren();
  renderLoading(view.main);
  paintEnabled(view.status, null);
  if (!(await refresh())) {
    renderRetry(view.main, "无法加载模型列表。", () => mountModelsPage(view));
    return;
  }
  await refreshCatalog();
  render();
}

function section(titleText, hintText, content) {
  const section = document.createElement("section");
  const title = document.createElement("h2");
  title.className = "section-title";
  title.textContent = titleText;
  const hint = document.createElement("p");
  hint.className = "hint";
  hint.textContent = hintText;
  section.append(title, hint, content);
  return section;
}

function empty(text) {
  const wrap = document.createElement("div");
  wrap.className = "empty-state";
  const line = document.createElement("p");
  line.textContent = text;
  wrap.append(line);
  return wrap;
}

function headerRow(titles) {
  const row = document.createElement("tr");
  for (const title of titles) {
    const heading = document.createElement("th");
    heading.scope = "col";
    heading.textContent = title;
    row.append(heading);
  }
  return row;
}

function cell(text, className = "") {
  const node = document.createElement("td");
  node.className = className;
  node.textContent = text;
  return node;
}

function status(text) {
  const line = document.createElement("span");
  line.className = "status-line";
  const dot = document.createElement("span");
  dot.className = "status-dot";
  dot.dataset.tone = "ok";
  dot.setAttribute("aria-hidden", "true");
  const copy = document.createElement("span");
  copy.textContent = text;
  line.append(dot, copy);
  return line;
}

function button(text, className, activate) {
  const node = document.createElement("button");
  node.type = "button";
  node.className = className;
  node.textContent = text;
  node.addEventListener("click", activate);
  return node;
}
