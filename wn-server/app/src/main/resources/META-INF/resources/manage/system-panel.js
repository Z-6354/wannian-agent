import { clearStatus } from "/manage/page-feedback.js?v=20260920p";

export function mountSystem(view) {
  view.main.replaceChildren();
  view.actions.replaceChildren();
  clearStatus(view.status);

  const title = document.createElement("h2");
  title.className = "section-title";
  title.textContent = "系统探针";

  const empty = document.createElement("div");
  empty.className = "empty-state";
  const note = document.createElement("p");
  note.textContent = "系统探针在现行 K06（旧 K08）。本页只证明导航可以加一项。";
  empty.append(note);

  view.main.append(title, empty);
}
