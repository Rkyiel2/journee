import { buildSnapshot } from "./snapshot.js";

// Creates the engine object exposed as window.PW.
//   P          the pawnote module
//   getBridge  returns the Android bridge: { request(id, json), result(id, json), saveRefresh(json), log(msg) }
export function createEngine(P, getBridge) {
  const pendingHttp = new Map();
  let httpSeq = 0;
  let pendingSecurity = null; // { session, error }

  const bridge = () => getBridge();
  const log = (m) => { try { bridge().log(String(m)); } catch (_) { /* ignore */ } };

  // pawnote Fetcher implemented on top of Kotlin's HttpURLConnection (no CORS, no WebView cookies).
  function fetcher(req) {
    return new Promise((resolve, reject) => {
      const id = String(++httpSeq);
      pendingHttp.set(id, { resolve, reject });
      const headers = {};
      const h = req.headers;
      if (h) {
        if (typeof h.forEach === "function" && typeof h.get === "function") h.forEach((v, k) => { headers[k] = v; });
        else for (const k of Object.keys(h)) headers[k] = String(h[k]);
      }
      const payload = {
        url: String(req.url),
        method: req.method || "GET",
        headers,
        body: req.content == null ? null : String(req.content),
        redirect: req.redirect || "follow",
      };
      try {
        bridge().request(id, JSON.stringify(payload));
      } catch (e) {
        pendingHttp.delete(id);
        reject(e);
      }
    });
  }

  // Called by Kotlin with the HTTP response.
  function http(id, json) {
    const p = pendingHttp.get(String(id));
    if (!p) return;
    pendingHttp.delete(String(id));
    let r;
    try { r = JSON.parse(json); } catch (e) { p.reject(new Error("NETWORK: bad response")); return; }
    if (r.error) { p.reject(new Error("NETWORK: " + r.error)); return; }
    const headers = new Headers();
    const src = r.headers || {};
    for (const k of Object.keys(src)) {
      const vals = Array.isArray(src[k]) ? src[k] : [src[k]];
      for (const v of vals) { try { headers.append(k, String(v)); } catch (_) { /* skip invalid header */ } }
    }
    p.resolve({ status: r.status, content: r.body || "", headers });
  }

  function errorType(e) {
    if (!e) return "unknown";
    const is = (name) => typeof P[name] === "function" && e instanceof P[name];
    if (String(e.message || "").startsWith("NETWORK:")) return "network";
    if (is("BadCredentialsError") || is("AuthenticateError") || is("SessionExpiredError")) return "auth";
    if (is("AccessDeniedError") || is("AccountDisabledError")) return "denied";
    if (is("SuspendedIPError") || is("RateLimitedError") || is("BusyPageError")) return "busy";
    if (is("PageUnavailableError") || is("ServerSideError") || is("UnreachableError")) return "server";
    return "unknown";
  }

  function fail(e, type) {
    return { ok: false, error: { type: type || errorType(e), message: String((e && e.message) || e || "Erreur").slice(0, 300) } };
  }

  function securityInfo(err) {
    const h = err.handle || {};
    const D = P.DoubleAuthMode || {};
    const modes = Array.isArray(h.availableSecurityModes) ? h.availableSecurityModes : [];
    const onlyPinStrategy = !!h.shouldCustomDoubleAuth
      && !modes.includes(D.MGDA_NotificationSeulement)
      && !modes.includes(D.MGDA_Inactive)
      && modes.includes(D.MGDA_SaisieCodePIN);
    return {
      needPin: !!h.shouldEnterPIN || onlyPinStrategy,
      newPin: onlyPinStrategy && !h.shouldEnterPIN,
      needDeviceName: !!h.shouldEnterSource,
      customPassword: !!h.shouldCustomPassword,
    };
  }

  async function finish(session, refresh) {
    // Persist the renewed token right away: the previous one is no longer valid.
    try { bridge().saveRefresh(JSON.stringify(refresh)); } catch (e) { log("saveRefresh failed: " + e); }
    const snapshot = await buildSnapshot(P, session);
    return { ok: true, refresh, snapshot };
  }

  async function withLogin(loginFn) {
    const session = P.createSessionHandle(fetcher);
    let refresh;
    try {
      refresh = await loginFn(session);
    } catch (e) {
      if (typeof P.SecurityError === "function" && e instanceof P.SecurityError) {
        pendingSecurity = { session, error: e };
        return { ok: false, security: securityInfo(e) };
      }
      return fail(e);
    }
    return finish(session, refresh);
  }

  const methods = {
    async ping() {
      return { ok: true, pong: true };
    },

    // Every refresh logs in with the last saved token (Pronote hands out a new one each time).
    async loginToken(a) {
      return withLogin((session) => P.loginToken(session, {
        url: a.url,
        kind: a.kind || P.AccountKind.STUDENT,
        username: a.username,
        token: a.token,
        deviceUUID: a.deviceUUID,
        navigatorIdentifier: a.navigatorIdentifier || undefined,
      }));
    },

    // QR code shown in Pronote web (Mes données > Mes informations > code QR) + the 4-digit code chosen there.
    async loginQr(a) {
      let qr = a.qr;
      if (typeof qr === "string") {
        try { qr = JSON.parse(qr); } catch (_) { return fail(new Error("Ce QR code n'est pas un QR code Pronote."), "qr"); }
      }
      if (!qr || !qr.jeton || !qr.url) return fail(new Error("Ce QR code n'est pas un QR code Pronote."), "qr");
      return withLogin((session) => P.loginQrCode(session, {
        deviceUUID: a.deviceUUID,
        pin: String(a.pin || ""),
        qr,
      }));
    },

    // Second step when Pronote's "double authentification" kicks in.
    async security(a) {
      const st = pendingSecurity;
      if (!st) return fail(new Error("Aucune vérification en attente."), "unknown");
      const { session, error } = st;
      const h = error.handle || {};
      const D = P.DoubleAuthMode || {};
      const pin = a.pin ? String(a.pin) : undefined;
      const deviceName = (a.deviceName && String(a.deviceName).trim()) || "Widget Android";
      try {
        if (h.shouldCustomPassword) {
          return fail(new Error("Pronote demande de changer ton mot de passe. Fais-le sur Pronote web, puis réessaie."), "password");
        }
        let mode;
        if (h.shouldCustomDoubleAuth) {
          const modes = Array.isArray(h.availableSecurityModes) ? h.availableSecurityModes : [];
          if (modes.includes(D.MGDA_NotificationSeulement)) mode = D.MGDA_NotificationSeulement;
          else if (modes.includes(D.MGDA_Inactive)) mode = D.MGDA_Inactive;
          else if (modes.includes(D.MGDA_SaisieCodePIN)) mode = D.MGDA_SaisieCodePIN;
        }
        if (h.shouldEnterPIN) {
          if (!pin) return { ok: false, security: securityInfo(error) };
          const ok = await P.securityCheckPIN(session, pin);
          if (!ok) return fail(new Error("Code PIN incorrect."), "pin");
        }
        if (h.shouldEnterSource) {
          const ok = await P.securitySource(session, deviceName);
          if (!ok) return fail(new Error("Nom d'appareil refusé par Pronote, essaie un autre nom."), "device");
        }
        const options = { deviceName };
        if (pin) options.pin = pin;
        if (mode !== undefined) options.mode = mode;
        await P.securitySave(session, h, options);
        const ctx = h.context || {};
        const refresh = await P.finishLoginManually(session, ctx.authentication, ctx.identity, ctx.initialUsername);
        pendingSecurity = null;
        return finish(session, refresh);
      } catch (e) {
        return fail(e);
      }
    },
  };

  // Called by Kotlin: PW.call("<id>", "<method>", "<json args>")
  function call(id, method, argsJson) {
    const done = (res) => {
      let json;
      try { json = JSON.stringify(res); } catch (e) { json = JSON.stringify(fail(e)); }
      try { bridge().result(String(id), json); } catch (e) { log("result failed: " + e); }
    };
    const fn = methods[method];
    if (!fn) { done(fail(new Error("Unknown method " + method))); return; }
    let args = {};
    try { args = argsJson ? JSON.parse(argsJson) : {}; } catch (e) { done(fail(e)); return; }
    Promise.resolve()
      .then(() => fn(args))
      .then(done, (e) => done(fail(e)));
  }

  return { call, http, _methods: methods };
}
