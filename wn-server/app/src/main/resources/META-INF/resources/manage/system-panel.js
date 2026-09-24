import { getAgentBudget, getToken, isLocalHost, saveAgentBudget } from "/manage/api.js?v=20260924a";
import { clearBanner, clearStatus, showError, showSuccess } from "/manage/page-feedback.js?v=20260920p";

export function mountSystem(view) {
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
  title.textContent = "Agent 预算";

  const hint = document.createElement("p");
  hint.className = "muted";
  hint.textContent =
    "读写数据目录 wannian.json 的 agentBudget。普通 decide（含普通工具）与系统工具（记忆/关系/搜索）分开计数；改完后对新 Turn 生效。";

  const form = document.createElement("form");
  form.className = "stack";

  const maxInput = numberField("maxModelDecisions", "普通决策上限（含普通工具）");
  const systemInput = numberField("maxSystemToolInvocationsPerTool", "每个系统工具上限");
  const softInput = numberField("softDeadlineSeconds", "软截止（秒）");
  const hardInput = numberField("hardDeadlineSeconds", "硬截止（秒）");
  form.append(maxInput.wrap, systemInput.wrap, softInput.wrap, hardInput.wrap);

  const saveBtn = document.createElement("button");
  saveBtn.type = "submit";
  saveBtn.className = "btn primary";
  saveBtn.textContent = "保存到配置文件";
  form.append(saveBtn);

  view.main.append(title, hint, form);

  load();

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    saveBtn.disabled = true;
    const result = await saveAgentBudget({
      maxModelDecisions: Number(maxInput.input.value),
      maxSystemToolInvocationsPerTool: Number(systemInput.input.value),
      softDeadlineSeconds: Number(softInput.input.value),
      hardDeadlineSeconds: Number(hardInput.input.value),
    });
    saveBtn.disabled = false;
    if (!result.ok) {
      showError(view.banner, result);
      return;
    }
    applyBody(result.body, maxInput.input, systemInput.input, softInput.input, hardInput.input);
    showSuccess(view.banner, "已写入 wannian.json");
  });

  async function load() {
    const result = await getAgentBudget();
    if (!result.ok) {
      showError(view.banner, result);
      return;
    }
    applyBody(result.body, maxInput.input, systemInput.input, softInput.input, hardInput.input);
    clearBanner(view.banner);
  }
}

function numberField(name, labelText) {
  const wrap = document.createElement("label");
  wrap.className = "field";
  const label = document.createElement("span");
  label.textContent = labelText;
  const input = document.createElement("input");
  input.type = "number";
  input.name = name;
  input.min = "1";
  input.required = true;
  wrap.append(label, input);
  return { wrap, input };
}

function applyBody(body, maxInput, systemInput, softInput, hardInput) {
  if (!body) {
    return;
  }
  maxInput.value = String(body.maxModelDecisions ?? "");
  systemInput.value = String(body.maxSystemToolInvocationsPerTool ?? "5");
  softInput.value = String(body.softDeadlineSeconds ?? "");
  hardInput.value = String(body.hardDeadlineSeconds ?? "");
}
