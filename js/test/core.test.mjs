// Offline test of the engine glue with a mocked pawnote. Run: node --test js/test
import test from "node:test";
import assert from "node:assert/strict";
import { createEngine } from "../src/core.js";
import { htmlToText, dayKey } from "../src/snapshot.js";

class SecurityError extends Error { constructor(handle) { super("security"); this.handle = handle; } }
class BadCredentialsError extends Error {}

function makeMock({ security = false } = {}) {
  const calls = [];
  const now = new Date();
  const at = (dayOffset, h, m = 0) => { const d = new Date(now); d.setDate(d.getDate() + dayOffset); d.setHours(h, m, 0, 0); return d; };
  const userResource = {
    name: "DUPONT Camille", className: "1G3", establishmentName: "Lycée Test",
    tabs: new Map([[198, { periods: [{ name: "Trimestre 1", startDate: at(-60, 0), endDate: at(30, 0) }], defaultPeriod: { name: "Trimestre 1" } }]]),
  };
  const P = {
    AccountKind: { STUDENT: 6 },
    TabLocation: { Grades: 198 },
    GradeKind: { Error: -1, Grade: 0, Absent: 1, Exempted: 2, NotGraded: 3, Unfit: 4, Unreturned: 5, AbsentZero: 6, UnreturnedZero: 7, Congratulations: 8 },
    DoubleAuthMode: { MGDA_PasEncoreChoisi: 0, MGDA_Inactive: 1, MGDA_NotificationSeulement: 2, MGDA_SaisieCodePIN: 3 },
    SecurityError, BadCredentialsError,
    createSessionHandle(fetcher) { return { fetcher, userResource }; },
    async loginToken(session, auth) {
      calls.push(["loginToken", auth]);
      // exercise the fetcher once
      const r = await session.fetcher({ url: new URL(auth.url), method: "POST", content: "{}", headers: { "Content-Type": "application/json" } });
      assert.equal(r.status, 200);
      assert.equal(r.headers.get("set-cookie"), "a=1, b=2");
      if (auth.token === "bad") throw new BadCredentialsError("bad");
      if (security) throw new SecurityError({ shouldEnterPIN: true, shouldEnterSource: true, availableSecurityModes: [3], context: { authentication: 1, identity: 2, initialUsername: "u" } });
      return { url: auth.url, token: "NEXT", username: auth.username, kind: 6, navigatorIdentifier: "nav" };
    },
    async securityCheckPIN(_s, pin) { calls.push(["pin", pin]); return pin === "1234"; },
    async securitySource(_s, name) { calls.push(["source", name]); return true; },
    async securitySave(_s, _h, opts) { calls.push(["save", opts]); },
    async finishLoginManually(_s, a, i, u) { calls.push(["finish", a, i, u]); return { url: "x", token: "AFTER2FA", username: u, kind: 6 }; },
    async timetableFromIntervals() {
      return { classes: [
        { is: "lesson", startDate: at(0, 10), endDate: at(0, 11), subject: { name: "MATHÉMATIQUES" }, classrooms: ["B12"], teacherNames: ["M. X"], groupNames: [], canceled: false, test: true, backgroundColor: "#FF0000" },
        { is: "lesson", startDate: at(0, 8), endDate: at(0, 9), subject: { name: "ANGLAIS" }, classrooms: [], teacherNames: ["Mme Y"], groupNames: [], status: "Prof. absent", canceled: true },
        { is: "activity", startDate: at(1, 14), endDate: at(1, 16), title: "Sortie" },
      ] };
    },
    parseTimetable(_s, tt, opts) { assert.equal(opts.withCanceledClasses, true); tt.parsed = true; },
    async assignmentsFromIntervals() {
      return [{ deadline: at(1, 0), subject: { name: "PHYSIQUE" }, description: "<p>Exercices&nbsp;3 &amp; 4</p><p>p.&#233;52</p>", done: false }];
    },
    async gradesOverview() {
      return { grades: [
        { date: at(-5, 0), subject: { name: "SVT" }, value: { kind: 0, points: 14.5 }, outOf: { kind: 0, points: 20 }, coefficient: 1, comment: "" },
        { date: at(-1, 0), subject: { name: "HISTOIRE" }, value: { kind: 1, points: 0 }, outOf: { kind: 0, points: 20 }, coefficient: 2, comment: "DS" },
      ] };
    },
    async discussions() { return { items: [{ numberOfMessagesUnread: 2 }, { numberOfMessagesUnread: 0 }, {}] }; },
    async menus(_s, date) {
      return { days: [{ date: new Date(date), lunch: { entry: [{ name: "Carottes râpées" }], main: [{ name: "Lasagnes" }], drink: [{ name: "Eau" }] } }] };
    },
  };
  return { P, calls };
}

function makeBridge(engineRef) {
  const results = new Map();
  const saved = [];
  const bridge = {
    request(id, json) {
      const req = JSON.parse(json);
      assert.equal(req.method, "POST");
      assert.equal(req.headers["Content-Type"] ?? req.headers["content-type"], "application/json");
      setTimeout(() => engineRef.engine.http(id, JSON.stringify({ status: 200, body: "ok", headers: { "set-cookie": ["a=1", "b=2"], "content-type": ["text/plain"] } })), 1);
    },
    result(id, json) { results.set(id, JSON.parse(json)); },
    saveRefresh(json) { saved.push(JSON.parse(json)); },
    log() {},
  };
  return { bridge, results, saved };
}

async function run(engine, results, id, method, args) {
  engine.call(id, method, JSON.stringify(args));
  for (let i = 0; i < 200 && !results.has(id); i++) await new Promise((r) => setTimeout(r, 5));
  return results.get(id);
}

test("token login builds a full snapshot", async () => {
  const { P, calls } = makeMock();
  const ref = {};
  const { bridge, results, saved } = makeBridge(ref);
  ref.engine = createEngine(P, () => bridge);
  const res = await run(ref.engine, results, "1", "loginToken", { url: "https://x.index-education.net/pronote/", username: "u", token: "t", deviceUUID: "d" });
  assert.equal(res.ok, true, JSON.stringify(res));
  assert.equal(saved[0].token, "NEXT");
  assert.equal(calls[0][1].kind, 6);
  const s = res.snapshot;
  assert.equal(s.user.className, "1G3");
  assert.equal(s.lessons.length, 3);
  assert.equal(s.lessons[0].title, "ANGLAIS"); // sorted by start
  assert.equal(s.lessons[0].canceled, true);
  assert.equal(s.lessons[1].room, "B12");
  assert.equal(s.lessons[2].kind, "activity");
  assert.equal(s.homework[0].text, "Exercices 3 & 4\np.é52");
  assert.deepEqual(s.grades.map((g) => g.value), ["Abs", "14,5"]);
  assert.equal(s.grades[1].outOf, "20");
  assert.equal(s.unread, 2);
  assert.equal(s.menus[0].day, dayKey(new Date()));
  assert.deepEqual(s.menus[0].lunch, ["Carottes râpées", "Lasagnes"]);
  assert.deepEqual(s.errors, {});
});

test("bad token is reported as auth error", async () => {
  const { P } = makeMock();
  const ref = {};
  const { bridge, results } = makeBridge(ref);
  ref.engine = createEngine(P, () => bridge);
  const res = await run(ref.engine, results, "2", "loginToken", { url: "https://x/pronote/", username: "u", token: "bad", deviceUUID: "d" });
  assert.equal(res.ok, false);
  assert.equal(res.error.type, "auth");
});

test("double authentication flow", async () => {
  const { P, calls } = makeMock({ security: true });
  const ref = {};
  const { bridge, results, saved } = makeBridge(ref);
  ref.engine = createEngine(P, () => bridge);
  const r1 = await run(ref.engine, results, "3", "loginToken", { url: "https://x/pronote/", username: "u", token: "t", deviceUUID: "d" });
  assert.equal(r1.ok, false);
  assert.deepEqual(r1.security, { needPin: true, newPin: false, needDeviceName: true, customPassword: false });
  const bad = await run(ref.engine, results, "4", "security", { pin: "0000", deviceName: "Tel" });
  assert.equal(bad.error.type, "pin");
  const r2 = await run(ref.engine, results, "5", "security", { pin: "1234", deviceName: "Tel" });
  assert.equal(r2.ok, true, JSON.stringify(r2));
  assert.equal(saved.at(-1).token, "AFTER2FA");
  const save = calls.find((c) => c[0] === "save");
  assert.deepEqual(save[1], { deviceName: "Tel", pin: "1234" }); // no strategy choice requested → no mode
  assert.deepEqual(calls.find((c) => c[0] === "finish").slice(1), [1, 2, "u"]);
});

test("qr validation", async () => {
  const { P } = makeMock();
  const ref = {};
  const { bridge, results } = makeBridge(ref);
  ref.engine = createEngine(P, () => bridge);
  const res = await run(ref.engine, results, "6", "loginQr", { qr: "hello", pin: "1234", deviceUUID: "d" });
  assert.equal(res.error.type, "qr");
});

test("htmlToText without DOMParser", () => {
  assert.equal(htmlToText("<div>Lire&nbsp;<b>ch. 2</b></div><br/>Bien &#x2713;"), "Lire ch. 2\nBien ✓");
});
