import { deleteVendor, saveVendor } from "/manage/api.js?v=20260920p";
import { loadModelOverview } from "/manage/model-data.js?v=20260920p";
import { clearBanner, paintEnabled, renderLoading, renderRetry, showError, showSuccess } from "/manage/page-feedback.js?v=20260920p";
import { PROTOCOL_OPTIONS } from "/manage/protocols.js?v=20260920p";

export async function mountVendorsPage(view) {
  const state = { vendors: [], enabled: null, pending: "" };

  async function refresh() {
    const result = await loadModelOverview();
    if (!result.ok) {
      showError(view.banner, result);
      return false;
    }
    state.vendors = result.vendors;
    state.enabled = result.enabled;
    clearBanner(view.banner);
    return true;
  }

  function render() {
    paintEnabled(view.status, state.enabled);
    renderActions();
    view.main.replaceChildren();
    const page = document.createElement("section");
    page.className = "content-page";
    const intro = document.createElement("p");
    intro.className = "hint";
    intro.textContent = "管理连接信息；密钥值只从环境变量读取，页面只保存变量名。";
    page.append(intro);
    if (!state.vendors.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      const line = document.createElement("p");
      line.textContent = "还没有供应商。用页头的「添加供应商」建立第一条连接。";
      empty.append(line);
      page.append(empty);
    } else {
      const grid = document.createElement("div");
      grid.className = "card-grid";
      for (const vendor of state.vendors) {
        grid.append(vendorCard(vendor));
      }
      page.append(grid);
    }
    view.main.append(page);
  }

  function renderActions() {
    view.actions.replaceChildren();
    const add = document.createElement("button");
    add.type = "button";
    add.textContent = "添加供应商";
    add.disabled = Boolean(state.pending);
    add.addEventListener("click", () => openVendorDialog(null, add));
    view.actions.append(add);
  }

  function vendorCard(vendor) {
    const card = document.createElement("article");
    card.className = "card";
    const head = document.createElement("div");
    head.className = "card-head";
    const title = document.createElement("h2");
    title.textContent = vendor.displayName;
    const badge = document.createElement("div");
    badge.className = "badge";
    badge.textContent = vendor.protocol;
    head.append(title, badge);

    const status = document.createElement("p");
    status.className = "status-line";
    const dot = document.createElement("span");
    dot.className = "status-dot";
    dot.dataset.tone = "ok";
    dot.setAttribute("aria-hidden", "true");
    const statusText = document.createElement("span");
    statusText.textContent = "已配置";
    status.append(dot, statusText);

    const facts = document.createElement("dl");
    facts.className = "card-facts";
    facts.append(fact("地址", vendor.baseUrl), fact("密钥变量", vendor.apiKeyEnv));

    const actions = document.createElement("div");
    actions.className = "actions";
    const edit = actionButton("编辑", "ghost", () => openVendorDialog(vendor, edit));
    const discover = actionButton("检索模型", "secondary", () => {
      location.hash = "#models?vendor=" + encodeURIComponent(vendor.id);
    });
    const remove = actionButton("删除", "danger ghost", () => confirmDelete(vendor, remove));
    actions.append(edit, discover, remove);
    card.append(head, status, facts, actions);
    return card;
  }

  function actionButton(text, className, activate) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = className;
    button.textContent = text;
    button.disabled = Boolean(state.pending);
    button.addEventListener("click", activate);
    return button;
  }

  function confirmDelete(vendor, trigger) {
    const modal = createDialog("confirm-delete-title", "删除供应商", trigger);
    const message = document.createElement("p");
    message.textContent = "将删除「" + vendor.displayName + "」。如果它正在启用，当前启用也会一并取消。";
    modal.body.append(message);
    const cancel = actionButton("取消", "secondary", modal.close);
    const confirm = actionButton("删除", "danger", async () => {
      if (state.pending) return;
      state.pending = "delete:" + vendor.id;
      modal.setPending(true);
      confirm.disabled = true;
      cancel.disabled = true;
      confirm.setAttribute("aria-busy", "true");
      const result = await deleteVendor(vendor.id);
      state.pending = "";
      if (!result.ok) {
        modal.setPending(false);
        showError(view.banner, result);
        confirm.disabled = false;
        cancel.disabled = false;
        confirm.removeAttribute("aria-busy");
        return;
      }
      modal.close();
      if (await refresh()) render();
    });
    confirm.dataset.solid = "true";
    modal.actions.append(cancel, confirm);
    modal.open(confirm);
  }

  function openVendorDialog(editing, trigger) {
    if (document.querySelector(".dialog-backdrop")) return;
    const modal = createDialog("vendor-dialog-title", editing ? "编辑供应商" : "添加供应商", trigger);
    const formError = document.createElement("p");
    formError.className = "field-error";
    formError.setAttribute("role", "alert");
    formError.setAttribute("aria-live", "assertive");
    formError.hidden = true;
    const form = document.createElement("form");
    form.append(
      field("id", "id", editing ? editing.id : "", !editing, "创建后不可修改。"),
      field("显示名", "displayName", editing ? editing.displayName : "", true, ""),
      protocolField(editing ? editing.protocol : PROTOCOL_OPTIONS[0].id),
      field("Base URL", "baseUrl", editing ? editing.baseUrl : "", true, "例如 https://api.example.com/v1"),
      field("密钥变量名", "apiKeyEnv", editing ? editing.apiKeyEnv : "", true, "只填环境变量名，不保存或回显密钥值。"),
      formError
    );
    modal.body.append(form);
    const cancel = actionButton("取消", "secondary", modal.close);
    const save = actionButton("保存", "", () => {});
    save.type = "submit";
    modal.actions.append(cancel, save);
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (state.pending) return;
      formError.hidden = true;
      const data = new FormData(form);
      const id = String(data.get("id") || "").trim();
      const body = {
        displayName: String(data.get("displayName") || ""),
        protocol: String(data.get("protocol") || ""),
        baseUrl: String(data.get("baseUrl") || ""),
        apiKeyEnv: String(data.get("apiKeyEnv") || ""),
      };
      if (editing) body.expectedRevision = editing.revision;
      state.pending = "save:" + id;
      modal.setPending(true);
      save.disabled = true;
      cancel.disabled = true;
      save.setAttribute("aria-busy", "true");
      const result = await saveVendor(id, body);
      state.pending = "";
      if (!result.ok) {
        modal.setPending(false);
        formError.textContent = result.code + " " + result.detail;
        formError.hidden = false;
        showError(view.banner, result);
        save.disabled = false;
        cancel.disabled = false;
        save.removeAttribute("aria-busy");
        return;
      }
      modal.close();
      if (await refresh()) {
        showSuccess(view.banner, "供应商已保存");
        render();
      }
    });
    modal.open(form.querySelector("input:not([readonly]), select"));
  }

  renderLoading(view.main);
  view.actions.replaceChildren();
  paintEnabled(view.status, null);
  if (!(await refresh())) {
    renderRetry(view.main, "无法加载供应商列表。", () => mountVendorsPage(view));
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
  input.value = value;
  input.required = true;
  if (["id", "apiKeyEnv", "baseUrl"].includes(name)) input.className = "mono";
  if (!editable && name === "id") input.readOnly = true;
  wrap.append(input);
  return wrap;
}

function protocolField(selected) {
  const wrap = document.createElement("label");
  wrap.className = "field";
  const label = document.createElement("span");
  label.className = "field-label";
  label.textContent = "协议";
  const select = document.createElement("select");
  select.name = "protocol";
  for (const option of PROTOCOL_OPTIONS) {
    const item = document.createElement("option");
    item.value = option.id;
    item.textContent = option.label;
    item.selected = option.id === selected;
    select.append(item);
  }
  wrap.append(label, select);
  return wrap;
}

function createDialog(labelId, titleText, trigger) {
  const backdrop = document.createElement("div");
  backdrop.className = "dialog-backdrop";
  const dialog = document.createElement("div");
  dialog.className = "dialog";
  dialog.setAttribute("role", "dialog");
  dialog.setAttribute("aria-modal", "true");
  dialog.setAttribute("aria-labelledby", labelId);
  dialog.setAttribute("aria-describedby", labelId + "-description");
  dialog.tabIndex = -1;
  const head = document.createElement("div");
  head.className = "dialog-head";
  const title = document.createElement("h2");
  title.id = labelId;
  title.textContent = titleText;
  const closeButton = document.createElement("button");
  closeButton.type = "button";
  closeButton.className = "ghost dialog-close";
  closeButton.setAttribute("aria-label", "关闭");
  closeButton.textContent = "×";
  head.append(title, closeButton);
  const body = document.createElement("div");
  body.className = "dialog-body";
  body.id = labelId + "-description";
  const foot = document.createElement("div");
  foot.className = "dialog-foot";
  const actions = document.createElement("div");
  actions.className = "actions";
  foot.append(actions);
  dialog.append(head, body, foot);
  backdrop.append(dialog);
  let pending = false;
  function close() {
    if (pending) return;
    document.removeEventListener("keydown", onKey);
    backdrop.remove();
    if (trigger) trigger.focus();
  }
  function onKey(event) {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === "Tab") {
      const focusable = [...dialog.querySelectorAll(
        'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
      )].filter((node) => !node.hidden);
      if (!focusable.length) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  }
  closeButton.addEventListener("click", close);
  backdrop.addEventListener("click", (event) => {
    if (event.target === backdrop) close();
  });
  return {
    body,
    actions,
    close,
    open(initialFocus) {
      document.body.append(backdrop);
      document.addEventListener("keydown", onKey);
      (initialFocus || dialog).focus();
    },
    setPending(value) {
      pending = value;
      closeButton.disabled = value;
    },
  };
}
