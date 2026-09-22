const TOKEN_KEY = "wannian.manage.token";

export function isLocalHost() {
  const host = location.hostname;
  return host === "localhost" || host === "127.0.0.1" || host === "::1" || host === "[::1]";
}

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY) || "";
}

export function setToken(value) {
  if (value) {
    sessionStorage.setItem(TOKEN_KEY, value);
  } else {
    sessionStorage.removeItem(TOKEN_KEY);
  }
}

export function listVendors() {
  return request("/api/manage/model/vendors");
}

export function saveVendor(id, body) {
  return request("/api/manage/model/vendors/" + encodeURIComponent(id), {
    method: "PUT",
    body,
  });
}

export function deleteVendor(id) {
  return request("/api/manage/model/vendors/" + encodeURIComponent(id), {
    method: "DELETE",
  });
}

export function listModels(vendorId) {
  return request("/api/manage/model/vendors/" + encodeURIComponent(vendorId) + "/models:list", {
    method: "POST",
  });
}

export function listListed() {
  return request("/api/manage/model/listed");
}

export function addListed(vendorId, modelId) {
  return request("/api/manage/model/listed", {
    method: "PUT",
    body: { vendorId, modelId },
  });
}

export function removeListed(vendorId, modelId) {
  const params = new URLSearchParams({ vendorId, modelId });
  return request("/api/manage/model/listed?" + params.toString(), { method: "DELETE" });
}

export function getEnabled() {
  return request("/api/manage/model/enabled");
}

export function enableModel(vendorId, modelId) {
  return request("/api/manage/model/enabled", {
    method: "PUT",
    body: { vendorId, modelId },
  });
}

export function getAgentBudget() {
  return request("/api/manage/agent/budget");
}

export function saveAgentBudget(body) {
  return request("/api/manage/agent/budget", {
    method: "PUT",
    body,
  });
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const token = getToken();
  if (token) {
    headers.set("Authorization", "Bearer " + token);
  }
  if (options.body != null && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(path, {
    method: options.method || "GET",
    headers,
    body: options.body == null ? undefined : JSON.stringify(options.body),
  });
  if (response.status === 204) {
    return { ok: true, status: 204, code: "", detail: "", body: null };
  }
  const body = await response.json().catch(() => null);
  const code = body && typeof body.code === "string" && body.code ? body.code : response.ok ? "" : "ERROR";
  const detail = body && typeof body.detail === "string" && body.detail ? body.detail : response.ok ? "" : "请求失败";
  return { ok: response.ok, status: response.status, code, detail, body };
}
