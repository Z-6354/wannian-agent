export const APP_GROUPS = [
  { id: "agents", label: "工作台" },
  { id: "system", label: "系统" },
];

export const APP_NAV = [
  { id: "chat", href: "/chat/", label: "对话", icon: "◎", group: "agents" },
  { id: "vendors", href: "/manage/#vendors", label: "供应商", icon: "◇", group: "agents" },
  { id: "models", href: "/manage/#models", label: "模型", icon: "▦", group: "agents" },
  { id: "tools", href: "/manage/#tools", label: "工具", icon: "⚒", group: "agents" },
  { id: "system", href: "/manage/#system", label: "系统", icon: "⚙", group: "system" },
];

export function renderAppNavigation(nav, currentId) {
  nav.replaceChildren();
  for (const group of APP_GROUPS) {
    const label = document.createElement("div");
    label.className = "nav-group";
    label.textContent = group.label;
    nav.append(label);
    for (const item of APP_NAV.filter((entry) => entry.group === group.id)) {
      const link = document.createElement("a");
      link.className = "nav-link";
      link.href = item.href;
      link.dataset.nav = item.id;
      if (item.id === currentId) link.setAttribute("aria-current", "page");
      const icon = document.createElement("span");
      icon.className = "nav-icon";
      icon.setAttribute("aria-hidden", "true");
      icon.textContent = item.icon;
      const text = document.createElement("span");
      text.textContent = item.label;
      link.append(icon, text);
      nav.append(link);
    }
  }
}

export function installMobileNavigation() {
  const shell = document.querySelector(".shell");
  const sidebar = document.querySelector(".sidebar");
  const toggle = document.querySelector("#mobile-nav-toggle");
  const backdrop = document.querySelector("#mobile-nav-backdrop");
  if (!shell || !sidebar || !toggle || !backdrop) return;

  function setOpen(open) {
    shell.classList.toggle("nav-open", open);
    toggle.setAttribute("aria-expanded", String(open));
    backdrop.hidden = !open;
    if (open) sidebar.querySelector(".nav-link")?.focus();
    else toggle.focus();
  }

  toggle.addEventListener("click", () => setOpen(!shell.classList.contains("nav-open")));
  backdrop.addEventListener("click", () => setOpen(false));
  sidebar.addEventListener("click", (event) => {
    if (event.target.closest(".nav-link")) setOpen(false);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && shell.classList.contains("nav-open")) setOpen(false);
  });
}
