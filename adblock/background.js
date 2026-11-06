const api = typeof browser !== "undefined" ? browser : chrome;

// Compteur de requêtes bloquées (local, rien n'est envoyé).
if (api.declarativeNetRequest.onRuleMatchedDebug) {
  api.declarativeNetRequest.onRuleMatchedDebug.addListener(async () => {
    const { blocked = 0 } = await api.storage.local.get("blocked");
    api.storage.local.set({ blocked: blocked + 1 });
  });
}

// Activer/désactiver globalement.
api.runtime.onMessage.addListener((msg) => {
  if (msg.type === "toggle") {
    api.declarativeNetRequest.updateEnabledRulesets(
      msg.enabled ? { enableRulesetIds: ["ads"] } : { disableRulesetIds: ["ads"] });
  }
});
