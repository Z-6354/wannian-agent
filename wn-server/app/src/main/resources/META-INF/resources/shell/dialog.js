/**
 * 跨页面共享弹窗。布局对齐 DSH ui-primitives Modal：
 * body portal · presentation 层 · 独立 mask · Escape/点遮罩关闭 · 打开时 sibling inert。
 * 样式：/ui/layouts/console.css 的 .dialog*。
 */

let titleSeq = 0;

function nextTitleId(prefix) {
  titleSeq += 1;
  return (prefix || "dialog-title") + "-" + titleSeq;
}

function focusableWithin(root) {
  return Array.from(
    root.querySelectorAll(
      'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )
  ).filter((el) => !el.hasAttribute("disabled") && el.getAttribute("aria-hidden") !== "true");
}

function appendBodyContent(body, content) {
  if (content == null || content === "") return false;
  if (typeof content === "string") {
    const p = document.createElement("p");
    p.textContent = content;
    body.append(p);
    return true;
  }
  if (typeof content === "function") {
    content(body);
    return body.childNodes.length > 0;
  }
  if (content instanceof Node) {
    body.append(content);
    return true;
  }
  return false;
}

function makeButton(label, className, onClick) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className || "";
  button.textContent = label;
  button.addEventListener("click", onClick);
  return button;
}

function setPageInert(backdrop, inert) {
  for (const child of Array.from(document.body.children)) {
    if (child === backdrop) continue;
    if (inert) child.inert = true;
    else child.inert = false;
  }
}

/**
 * 打开自定义弹窗。同一时刻只允许一个；若已有则返回 null。
 *
 * @param {object} options
 * @param {string} options.title
 * @param {string} [options.titleId]
 * @param {string|Node|function(HTMLElement):void} [options.body]
 * @param {string} [options.description] 标题下说明，写入 aria-describedby
 * @param {HTMLElement} [options.trigger] 关闭后恢复焦点
 * @param {string} [options.closeLabel="关闭"]
 * @param {boolean} [options.closeButton=true]
 * @param {boolean} [options.dismissible=true] Esc / 点遮罩关闭
 * @param {string} [options.role="dialog"] dialog | alertdialog
 * @param {function():void} [options.onClose]
 * @param {function(HTMLElement, {close:function, setPending:function, open:function}):void} [options.renderActions]
 */
export function openDialog(options) {
  if (document.querySelector(".dialog-backdrop")) return null;
  const opts = options || {};
  const titleId = opts.titleId || nextTitleId();
  const descriptionText =
    opts.description != null && String(opts.description).trim() !== ""
      ? String(opts.description)
      : "";
  const descriptionId = descriptionText ? titleId + "-desc" : "";
  const trigger = opts.trigger;
  const dismissible = opts.dismissible !== false;
  const showClose = opts.closeButton !== false;
  const role = opts.role === "alertdialog" ? "alertdialog" : "dialog";

  const backdrop = document.createElement("div");
  backdrop.className = "dialog-backdrop";
  backdrop.setAttribute("role", "presentation");

  const mask = document.createElement("div");
  mask.className = "dialog-mask";
  mask.setAttribute("aria-hidden", "true");

  const dialog = document.createElement("div");
  dialog.className = "dialog";
  dialog.setAttribute("role", role);
  dialog.setAttribute("aria-modal", "true");
  dialog.setAttribute("aria-labelledby", titleId);
  // DSH 用 aria-label；可见标题时 labelledby 更稳，同时保留 name 可读性。
  dialog.setAttribute("aria-label", opts.title || "");
  if (descriptionId) dialog.setAttribute("aria-describedby", descriptionId);
  dialog.tabIndex = -1;

  const head = document.createElement("div");
  head.className = "dialog-head";
  const title = document.createElement("h2");
  title.id = titleId;
  title.textContent = opts.title || "";
  head.append(title);
  if (showClose) {
    const closeBtn = document.createElement("button");
    closeBtn.type = "button";
    closeBtn.className = "dialog-close ghost";
    closeBtn.setAttribute("aria-label", opts.closeLabel || "关闭");
    closeBtn.textContent = "×";
    closeBtn.addEventListener("click", () => close());
    head.append(closeBtn);
  }

  dialog.append(head);

  if (descriptionText) {
    const desc = document.createElement("p");
    desc.id = descriptionId;
    desc.className = "dialog-description";
    desc.textContent = descriptionText;
    dialog.append(desc);
  }

  const body = document.createElement("div");
  body.className = "dialog-body";
  const hasBody = appendBodyContent(body, opts.body);
  if (hasBody) dialog.append(body);

  const actions = document.createElement("div");
  actions.className = "dialog-foot actions";
  dialog.append(actions);

  backdrop.append(mask, dialog);
  document.body.append(backdrop);
  setPageInert(backdrop, true);

  let closed = false;
  let onKey = null;

  function close() {
    if (closed) return;
    closed = true;
    if (onKey) document.removeEventListener("keydown", onKey);
    setPageInert(backdrop, false);
    backdrop.remove();
    if (trigger && typeof trigger.focus === "function") trigger.focus();
    if (typeof opts.onClose === "function") opts.onClose();
  }

  function setPending(pending) {
    backdrop.dataset.pending = pending ? "true" : "";
    const disabled = !!pending;
    for (const el of actions.querySelectorAll("button, input, select, textarea")) {
      el.disabled = disabled;
    }
    const closeBtn = head.querySelector(".dialog-close");
    if (closeBtn) closeBtn.disabled = disabled;
  }

  function open(focusEl) {
    const target =
      focusEl && typeof focusEl.focus === "function"
        ? focusEl
        : focusableWithin(dialog)[0] || dialog;
    target.focus?.();
  }

  if (dismissible) {
    mask.addEventListener("click", () => {
      if (!backdrop.dataset.pending) close();
    });
  }

  onKey = function onDialogKey(event) {
    if (closed) return;
    if (event.key === "Escape") {
      if (dismissible && !backdrop.dataset.pending) {
        event.preventDefault();
        close();
      }
      return;
    }
    if (event.key === "Tab") {
      const list = focusableWithin(dialog);
      if (!list.length) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = list[0];
      const last = list[list.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  };
  document.addEventListener("keydown", onKey);

  const api = { body, actions, close, setPending, open };
  if (typeof opts.renderActions === "function") {
    opts.renderActions(actions, api);
  }
  if (!actions.childNodes.length) actions.remove();
  return api;
}

/**
 * 确认弹窗。message 映射为 DSH 式 description（aria-describedby）。
 * @returns {Promise<boolean>}
 */
export function confirmDialog(options) {
  const opts = options || {};
  return new Promise((resolve) => {
    let accepted = false;
    let settled = false;
    function finish(value) {
      if (settled) return;
      settled = true;
      resolve(value);
    }
    const description = opts.description || opts.message || "";
    const modal = openDialog({
      title: opts.title || "确认",
      description,
      body: opts.body,
      trigger: opts.trigger,
      closeLabel: opts.closeLabel,
      closeButton: opts.closeButton !== false,
      dismissible: opts.dismissible !== false,
      role: opts.danger ? "alertdialog" : "dialog",
      onClose() {
        finish(accepted);
      },
      renderActions(actions, api) {
        const cancel = makeButton(opts.cancelLabel || "取消", "secondary", () => api.close());
        const confirm = makeButton(opts.confirmLabel || "确定", opts.danger ? "danger" : "", () => {
          accepted = true;
          api.close();
        });
        if (opts.danger) confirm.dataset.solid = "true";
        actions.append(cancel, confirm);
        queueMicrotask(() => api.open(opts.danger ? confirm : cancel));
      },
    });
    if (!modal) finish(false);
  });
}

/**
 * 危险操作双次确认。
 * @returns {Promise<boolean>}
 */
export async function confirmDestructive(options) {
  const opts = options || {};
  const first = await confirmDialog({
    title: opts.title || "确认删除",
    message: opts.message || "",
    confirmLabel: opts.confirmLabel || "继续",
    cancelLabel: opts.cancelLabel || "取消",
    danger: true,
    trigger: opts.trigger,
  });
  if (!first) return false;
  return confirmDialog({
    title: opts.secondTitle || opts.title || "再次确认",
    message: opts.secondMessage || "此操作不可恢复。确定继续？",
    confirmLabel: opts.secondConfirmLabel || opts.confirmLabel || "确定删除",
    cancelLabel: opts.cancelLabel || "取消",
    danger: true,
    trigger: opts.trigger,
  });
}

/**
 * 输入弹窗。取消返回 null。
 * @returns {Promise<string|null>}
 */
export function promptDialog(options) {
  const opts = options || {};
  return new Promise((resolve) => {
    const field = document.createElement("label");
    field.className = "field";
    const label = document.createElement("span");
    label.className = "field-label";
    label.textContent = opts.label || "内容";
    const input = document.createElement("input");
    input.type = "text";
    input.value = opts.defaultValue != null ? String(opts.defaultValue) : "";
    input.autocomplete = "off";
    if (opts.placeholder) input.placeholder = opts.placeholder;
    field.append(label, input);

    const formError = document.createElement("p");
    formError.className = "field-error";
    formError.setAttribute("role", "alert");
    formError.setAttribute("aria-live", "assertive");
    formError.hidden = true;

    const form = document.createElement("form");
    form.id = nextTitleId("dialog-form");
    form.append(field, formError);

    let value = null;
    let settled = false;
    function finish(result) {
      if (settled) return;
      settled = true;
      resolve(result);
    }

    const modal = openDialog({
      title: opts.title || "输入",
      description: opts.description,
      body: form,
      trigger: opts.trigger,
      closeLabel: opts.closeLabel,
      onClose() {
        finish(value);
      },
      renderActions(actions, api) {
        const cancel = makeButton(opts.cancelLabel || "取消", "secondary", () => api.close());
        const save = document.createElement("button");
        save.type = "submit";
        save.setAttribute("form", form.id);
        save.textContent = opts.confirmLabel || "确定";
        actions.append(cancel, save);

        form.addEventListener("submit", (event) => {
          event.preventDefault();
          const next = input.value;
          if (opts.required !== false && !String(next).trim()) {
            formError.textContent = opts.emptyMessage || "不能为空";
            formError.hidden = false;
            input.focus();
            return;
          }
          value = next;
          api.close();
        });

        queueMicrotask(() => api.open(input));
      },
    });
    if (!modal) finish(null);
  });
}
