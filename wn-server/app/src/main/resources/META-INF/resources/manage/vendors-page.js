import {
  connectVendor,
  deleteVendor,
  getEnabled,
  listPresets,
  listVendors,
} from "/manage/api.js?v=20260925f";
import { clearBanner, paintEnabled, renderLoading, renderRetry, showError, showSuccess } from "/manage/page-feedback.js?v=20260920p";
import { openDialog } from "/shell/dialog.js?v=20260925r";

/**
 * 供应商页：只选内置 Preset + 粘贴密钥；模型启用仍走模型页。
 */
export async function mountVendorsPage(view) {
  const state = { presets: [], vendorsById: {}, enabled: null, pending: "" };

  async function refresh() {
    const [presetsRes, vendorsRes, enabledRes] = await Promise.all([
      listPresets(),
      listVendors(),
      getEnabled(),
    ]);
    if (!presetsRes.ok) {
      showError(view.banner, presetsRes);
      return false;
    }
    if (!vendorsRes.ok) {
      showError(view.banner, vendorsRes);
      return false;
    }
    if (!enabledRes.ok) {
      showError(view.banner, enabledRes);
      return false;
    }
    state.presets = Array.isArray(presetsRes.body) ? presetsRes.body : [];
    state.vendorsById = {};
    for (const v of Array.isArray(vendorsRes.body) ? vendorsRes.body : []) {
      state.vendorsById[v.id] = v;
    }
    state.enabled = enabledRes.body && enabledRes.body.enabled ? enabledRes.body.enabled : null;
    clearBanner(view.banner);
    return true;
  }

  function render() {
    paintEnabled(view.status, state.enabled);
    view.actions.replaceChildren();
    view.main.replaceChildren();
    const page = document.createElement("section");
    page.className = "content-page";
    const intro = document.createElement("p");
    intro.className = "hint";
    intro.textContent =
      "选择内置供应商并保存 API 密钥（写入本机 data/secrets）。模型请到「模型」页检索并启用。";
    page.append(intro);

    const grid = document.createElement("div");
    grid.className = "card-grid";
    for (const preset of state.presets) {
      grid.append(presetCard(preset));
    }
    page.append(grid);
    view.main.append(page);
  }

  function presetCard(preset) {
    const connected = state.vendorsById[preset.id];
    const card = document.createElement("article");
    card.className = "card";
    const head = document.createElement("div");
    head.className = "card-head";
    const title = document.createElement("h2");
    title.textContent = preset.displayName;
    const badge = document.createElement("div");
    badge.className = "badge";
    badge.textContent = connected ? (connected.hasSecret || preset.hasSecret ? "已连接" : "已登记") : "未连接";
    head.append(title, badge);

    const facts = document.createElement("dl");
    facts.className = "card-facts";
    facts.append(fact("id", preset.id), fact("地址", preset.baseUrl), fact("协议", preset.protocol));

    const actions = document.createElement("div");
    actions.className = "actions";
    const connect = actionButton(connected ? "更新密钥" : "连接", "", () =>
      openConnectDialog(preset, connected, connect)
    );
    actions.append(connect);
    if (connected) {
      const discover = actionButton("检索模型", "secondary", () => {
        location.hash = "#models?vendor=" + encodeURIComponent(preset.id);
      });
      const remove = actionButton("断开", "danger ghost", () => confirmDelete(connected, remove));
      actions.append(discover, remove);
    }
    card.append(head, facts, actions);
    return card;
  }

  function openConnectDialog(preset, existing, trigger) {
    const formError = document.createElement("p");
    formError.className = "field-error";
    formError.setAttribute("role", "alert");
    formError.setAttribute("aria-live", "assertive");
    formError.hidden = true;
    const form = document.createElement("form");
    form.id = "vendor-connect-form";
    const keyField = field("API 密钥", "apiKey", "", true, "写入本机 secrets/" + preset.id + ".key，不会回显。");
    form.append(keyField, formError);

    const modal = openDialog({
      titleId: "vendor-connect-title",
      title: "连接 " + preset.displayName,
      body: form,
      trigger,
      renderActions(actions, api) {
        const cancel = actionButton("取消", "secondary", api.close);
        const save = actionButton("保存", "", () => {});
        save.type = "submit";
        save.setAttribute("form", form.id);
        actions.append(cancel, save);
      },
    });
    if (!modal) return;

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (state.pending) return;
      formError.hidden = true;
      const apiKey = String(new FormData(form).get("apiKey") || "").trim();
      if (!apiKey) {
        formError.textContent = "请填写 API 密钥";
        formError.hidden = false;
        return;
      }
      state.pending = "connect:" + preset.id;
      modal.setPending(true);
      const result = await connectVendor(
        preset.id,
        apiKey,
        existing && typeof existing.revision === "number" ? existing.revision : undefined
      );
      state.pending = "";
      if (!result.ok) {
        modal.setPending(false);
        formError.textContent = result.code + " " + result.detail;
        formError.hidden = false;
        showError(view.banner, result);
        return;
      }
      modal.close();
      if (await refresh()) {
        showSuccess(view.banner, preset.displayName + " 已连接");
        render();
      }
    });
    modal.open(form.querySelector("input"));
  }

  function confirmDelete(vendor, trigger) {
    const modal = openDialog({
      titleId: "vendor-delete-title",
      title: "断开供应商",
      description:
        "将删除「" + vendor.displayName + "」的连接与本机密钥文件，并移除其已加入的模型列表。",
      role: "alertdialog",
      trigger,
      renderActions(actions, api) {
        const cancel = actionButton("取消", "secondary", api.close);
        const confirm = actionButton("断开", "danger", async () => {
          if (state.pending) return;
          state.pending = "delete:" + vendor.id;
          api.setPending(true);
          const result = await deleteVendor(vendor.id);
          state.pending = "";
          if (!result.ok) {
            api.setPending(false);
            showError(view.banner, result);
            return;
          }
          api.close();
          if (await refresh()) {
            showSuccess(view.banner, "已断开 " + vendor.displayName);
            render();
          }
        });
        confirm.dataset.solid = "true";
        actions.append(cancel, confirm);
        queueMicrotask(() => api.open(confirm));
      },
    });
  }

  renderLoading(view.main);
  view.actions.replaceChildren();
  paintEnabled(view.status, null);
  if (!(await refresh())) {
    renderRetry(view.main, "无法加载供应商。", () => mountVendorsPage(view));
    return;
  }
  render();
}

function fact(label, value) {
  const row = document.createElement("div");
  const term = document.createElement("dt");
  term.textContent = label;
  const detail = document.createElement("dd");
  detail.className = "mono";
  detail.textContent = value || "";
  row.append(term, detail);
  return row;
}

function field(labelText, name, value, editable, hintText) {
  const wrap = document.createElement("label");
  wrap.className = "field";
  const label = document.createElement("span");
  label.className = "field-label";
  label.textContent = labelText;
  wrap.append(label);
  if (hintText) {
    const hint = document.createElement("p");
    hint.className = "field-hint";
    hint.textContent = hintText;
    wrap.append(hint);
  }
  const input = document.createElement("input");
  input.name = name;
  input.type = name === "apiKey" ? "password" : "text";
  input.value = value || "";
  input.required = true;
  input.autocomplete = "off";
  if (!editable) input.readOnly = true;
  wrap.append(input);
  return wrap;
}

function actionButton(text, className, activate) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className || "";
  button.textContent = text;
  button.addEventListener("click", activate);
  return button;
}
