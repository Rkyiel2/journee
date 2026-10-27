// Entry point loaded by the hidden Android WebView (assets/engine.html).
// Kotlin talks to it through window.PW; it talks back through the injected `Bridge` object.
import * as P from "pawnote";
import { createEngine } from "./core.js";

const engine = createEngine(P, () => window.Bridge);
window.PW = engine;
try {
  window.Bridge.ready();
} catch (_) { /* Bridge not available (tests) */ }
