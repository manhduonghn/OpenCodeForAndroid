(function () {
  "use strict";
  var ok = !!(window.Promise && window.Map && window.Set && window.WeakMap &&
    window.Symbol && window.globalThis && window.ResizeObserver &&
    window.MutationObserver && window.requestAnimationFrame && window.CSS &&
    String.prototype.normalize && Array.prototype.includes);
  window.__ocEditorSupported = ok;
  if (!ok && window.OpenCodeEditorBridge) {
    try {
      window.OpenCodeEditorBridge.onError(JSON.stringify({
        code: "unsupported",
        message: "WebView is too old to run the editor"
      }));
    } catch (e) {
      // The native side may not have registered the bridge yet; the timeout fallback covers it.
    }
  }
})();
