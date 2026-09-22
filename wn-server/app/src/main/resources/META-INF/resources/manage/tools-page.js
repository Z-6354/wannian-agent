import { getToken, getTools, isLocalHost, saveTools } from "/manage/api.js?v=20260922u";
import { clearBanner, clearStatus, showError, showSuccess } from "/manage/page-feedback.js?v=20260920p";

const STATUS_LABEL = {
  IN_USE: "使用",
  NOT_USING: "不使用",
  UNAVAILABLE: "不能使用",
};

const PS5 = "powershell_resolve_5";
const PS7 = "powershell_resolve_7";

export function mountToolsPage(view) {
  view.main.replaceChildren();
  view.actions.replaceChildren();
  clearStatus(view.status);
  clearBanner(view.banner);

  if (!isLocalHost() && !getToken()) {
    showError(view.banner, { code: "UNAUTHENTICATED", detail: "外网访问需要管理口令" });
    return;
  }

  const title = document.createElement("h2");
  title.className = "section-title";
  title.textContent = "工具";

  const hint = document.createElement("p");
  hint.className = "muted";
  hint.textContent =
    "三态：使用 / 不使用 / 不能使用。不能使用由本机环境决定，不可勾选。PowerShell 5 与 7 本机皆可用时互斥，默认优先 7。下方「本机模型可见」为各模式求交预览。";

  const hostLine = document.createElement("p");
  hostLine.className = "muted";
  hostLine.id = "tools-host-caps";

  const previewBox = document.createElement("fieldset");
  previewBox.className = "stack";
  const previewLegend = document.createElement("legend");
  previewLegend.textContent = "本机模型可见预览";
  const previewBody = document.createElement("pre");
  previewBody.className = "muted";
  previewBody.id = "tools-model-preview";
  previewBox.append(previewLegend, previewBody);

  const form = document.createElement("form");
  form.className = "stack";

  const enabledBox = document.createElement("fieldset");
  enabledBox.className = "stack";
  const enabledLegend = document.createElement("legend");
  enabledLegend.textContent = "工具列表（使用=进目录）";
  enabledBox.append(enabledLegend);

  const facetsRow = document.createElement("div");
  facetsRow.className = "stack";
  const chatBox = facetFieldset("chat", "聊天模式");
  const workBox = facetFieldset("work", "工作模式");
  const researchBox = facetFieldset("research", "科研模式");
  facetsRow.append(chatBox.root, workBox.root, researchBox.root);

  const saveBtn = document.createElement("button");
  saveBtn.type = "submit";
  saveBtn.className = "btn primary";
  saveBtn.textContent = "保存到配置文件";

  form.append(enabledBox, facetsRow, saveBtn);
  view.main.append(title, hint, hostLine, previewBox, form);

  /** @type {{ name: string, description: string, status?: string, selectable?: boolean }[]} */
  let pool = [];
  /** @type {Record<string, HTMLInputElement>} */
  const enabledChecks = {};
  /** @type {Record<string, Record<string, HTMLInputElement>>} */
  const facetChecks = { chat: chatBox.checks, work: workBox.checks, research: researchBox.checks };

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    saveBtn.disabled = true;
    const enabled = pool
      .map((p) => p.name)
      .filter((n) => enabledChecks[n] && !enabledChecks[n].disabled && enabledChecks[n].checked);
    const body = {
      enabled,
      yanhuo: {
        chat: selected(facetChecks.chat, enabled),
        work: selected(facetChecks.work, enabled),
        research: selected(facetChecks.research, enabled),
      },
    };
    const result = await saveTools(body);
    saveBtn.disabled = false;
    if (!result.ok) {
      showError(view.banner, result);
      return;
    }
    paint(result.body);
    showSuccess(view.banner, "已写入 wannian.json");
  });

  load();

  async function load() {
    const result = await getTools();
    if (!result.ok) {
      showError(view.banner, result);
      return;
    }
    paint(result.body);
    clearBanner(view.banner);
  }

  function paint(body) {
    pool = Array.isArray(body?.pool) ? body.pool : [];
    const caps = Array.isArray(body?.hostCapabilities) ? body.hostCapabilities.join(", ") : "";
    hostLine.textContent = "本机能力: " + (caps || "（无）");
    const preview = body?.modelVisiblePreview || {};
    previewBody.textContent = JSON.stringify(
      { chat: preview.chat || [], work: preview.work || [], research: preview.research || [] },
      null,
      2,
    );

    enabledBox.replaceChildren(enabledLegend);
    for (const key of Object.keys(enabledChecks)) delete enabledChecks[key];
    for (const item of pool) {
      const label = document.createElement("label");
      label.className = "field checkbox";
      const input = document.createElement("input");
      input.type = "checkbox";
      input.name = "enabled-" + item.name;
      input.value = item.name;
      const selectable = item.selectable !== false && item.status !== "UNAVAILABLE";
      input.disabled = !selectable;
      const statusKey = item.status || (selectable ? "NOT_USING" : "UNAVAILABLE");
      const text = document.createElement("span");
      text.textContent =
        "[" +
        (STATUS_LABEL[statusKey] || statusKey) +
        "] " +
        item.name +
        " — " +
        (item.description || "");
      label.append(input, text);
      enabledBox.append(label);
      enabledChecks[item.name] = input;
      input.addEventListener("change", () => {
        onPsMutex(item.name, input);
        syncFacetDisabled();
      });
    }

    rebuildFacet(chatBox, "chat");
    rebuildFacet(workBox, "work");
    rebuildFacet(researchBox, "research");

    const enabledSet = new Set(Array.isArray(body?.enabled) ? body.enabled : []);
    for (const name of Object.keys(enabledChecks)) {
      const input = enabledChecks[name];
      if (input.disabled) {
        input.checked = false;
      } else {
        input.checked = enabledSet.has(name);
      }
    }
    applyFacet(body?.yanhuo?.chat, facetChecks.chat);
    applyFacet(body?.yanhuo?.work, facetChecks.work);
    applyFacet(body?.yanhuo?.research, facetChecks.research);
    syncFacetDisabled();
  }

  function onPsMutex(name, input) {
    if (!input.checked) return;
    if (name === PS5 && enabledChecks[PS7] && !enabledChecks[PS7].disabled) {
      enabledChecks[PS7].checked = false;
    }
    if (name === PS7 && enabledChecks[PS5] && !enabledChecks[PS5].disabled) {
      enabledChecks[PS5].checked = false;
    }
  }

  function rebuildFacet(box, facet) {
    box.root.replaceChildren(box.legend);
    for (const key of Object.keys(box.checks)) delete box.checks[key];
    for (const item of pool) {
      const label = document.createElement("label");
      label.className = "field checkbox";
      const input = document.createElement("input");
      input.type = "checkbox";
      input.name = facet + "-" + item.name;
      input.value = item.name;
      const text = document.createElement("span");
      text.textContent = item.name;
      label.append(input, text);
      box.root.append(label);
      box.checks[item.name] = input;
    }
  }

  function syncFacetDisabled() {
    for (const name of Object.keys(enabledChecks)) {
      const on = !!enabledChecks[name]?.checked && !enabledChecks[name].disabled;
      for (const facet of ["chat", "work", "research"]) {
        const input = facetChecks[facet][name];
        if (!input) continue;
        input.disabled = !on;
        if (!on) input.checked = false;
      }
    }
  }
}

function facetFieldset(name, title) {
  const root = document.createElement("fieldset");
  root.className = "stack";
  const legend = document.createElement("legend");
  legend.textContent = title;
  root.append(legend);
  return { root, legend, checks: {}, name };
}

function applyFacet(names, checks) {
  const set = new Set(Array.isArray(names) ? names : []);
  for (const name of Object.keys(checks)) {
    checks[name].checked = set.has(name);
  }
}

function selected(checks, enabled) {
  const enabledSet = new Set(enabled);
  return Object.keys(checks).filter((n) => enabledSet.has(n) && checks[n].checked);
}
