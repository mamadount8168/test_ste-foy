const api = typeof browser !== "undefined" ? browser : chrome;
const btn = document.getElementById("toggle");
(async () => {
  const { blocked = 0, enabled = true } = await api.storage.local.get(["blocked", "enabled"]);
  document.getElementById("count").textContent = blocked;
  render(enabled);
})();
function render(on) { btn.textContent = on ? "Désactiver" : "Activer"; btn.className = on ? "" : "off"; }
btn.onclick = async () => {
  const { enabled = true } = await api.storage.local.get("enabled");
  await api.storage.local.set({ enabled: !enabled });
  api.runtime.sendMessage({ type: "toggle", enabled: !enabled });
  render(!enabled);
};
