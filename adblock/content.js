// Masquage cosmétique dynamique : retire les éléments publicitaires ajoutés après le chargement.
const SELECTORS = [
  "ins.adsbygoogle", "[id^='div-gpt-ad']", "[data-ad-slot]",
  "iframe[src*='doubleclick.net']", "iframe[src*='googlesyndication']"
];
let removed = 0;
function sweep() {
  for (const el of document.querySelectorAll(SELECTORS.join(","))) {
    if (el.dataset.sfHidden) continue;
    el.dataset.sfHidden = "1";
    el.style.setProperty("display", "none", "important");
    removed++;
  }
}
new MutationObserver(sweep).observe(document.documentElement, { childList: true, subtree: true });
document.addEventListener("DOMContentLoaded", sweep);
