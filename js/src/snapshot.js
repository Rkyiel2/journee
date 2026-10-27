// Builds the compact JSON snapshot the Android widget renders.
// `P` is the pawnote module (passed in so this file can be tested with a mock).

const DAY = 24 * 60 * 60 * 1000;

export function startOfDay(d) {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x;
}

export function addDays(d, n) {
  const x = new Date(d);
  x.setDate(x.getDate() + n);
  return x;
}

export function dayKey(d) {
  const x = new Date(d);
  const m = String(x.getMonth() + 1).padStart(2, "0");
  const day = String(x.getDate()).padStart(2, "0");
  return `${x.getFullYear()}-${m}-${day}`;
}

function iso(d) {
  if (!d) return null;
  const x = d instanceof Date ? d : new Date(d);
  return isNaN(x.getTime()) ? null : x.toISOString();
}

function errText(e) {
  if (!e) return "Erreur inconnue";
  return String(e.message || e).slice(0, 200);
}

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };

export function htmlToText(html) {
  if (!html) return "";
  let s = String(html);
  // Prefer the browser parser when available (Android WebView).
  if (typeof DOMParser !== "undefined") {
    try {
      s = s.replace(/<br\s*\/?>/gi, "\n").replace(/<\/(p|div|li)>/gi, "\n");
      const doc = new DOMParser().parseFromString(s, "text/html");
      s = doc.body ? doc.body.textContent || "" : s;
    } catch (_) { /* fall through to regex */ }
  }
  s = s
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li)>/gi, "\n")
    .replace(/<[^>]*>/g, "")
    .replace(/&(#x?[0-9a-f]+|\w+);/gi, (m, code) => {
      if (code[0] === "#") {
        const n = code[1] === "x" || code[1] === "X" ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
        return isNaN(n) ? m : String.fromCodePoint(n);
      }
      return ENTITIES[code.toLowerCase()] ?? m;
    });
  return s
    .replace(/ /g, " ")
    .split("\n")
    .map((l) => l.replace(/[ \t]+/g, " ").trim())
    .filter(Boolean)
    .join("\n");
}

function joinNames(arr) {
  return Array.isArray(arr) ? arr.filter(Boolean).join(", ") : "";
}

export function mapClass(c) {
  if (!c || !c.startDate || !c.endDate) return null;
  const base = {
    start: iso(c.startDate),
    end: iso(c.endDate),
    color: c.backgroundColor || null,
    note: c.notes ? htmlToText(c.notes) : null,
  };
  if (c.is === "lesson") {
    return {
      ...base,
      kind: "lesson",
      title: (c.subject && c.subject.name) || "Cours",
      room: joinNames(c.classrooms),
      teacher: joinNames(c.teacherNames),
      group: joinNames(c.groupNames),
      status: c.status || null,
      canceled: !!c.canceled,
      exempted: !!c.exempted,
      test: !!c.test,
    };
  }
  if (c.is === "activity") {
    return {
      ...base,
      kind: "activity",
      title: c.title || "Activité",
      room: "",
      teacher: "",
      group: "",
      status: null,
      canceled: false,
      exempted: false,
      test: false,
    };
  }
  if (c.is === "detention") {
    return {
      ...base,
      kind: "detention",
      title: c.title || "Retenue",
      room: joinNames(c.classrooms),
      teacher: joinNames(c.teacherNames),
      group: "",
      status: "Retenue",
      canceled: false,
      exempted: false,
      test: false,
    };
  }
  return null;
}

function num(n) {
  if (typeof n !== "number" || isNaN(n)) return "";
  const r = Math.round(n * 100) / 100;
  return String(r).replace(".", ",");
}

export function gradeValueText(P, v) {
  if (!v) return "";
  const K = (P && P.GradeKind) || {};
  switch (v.kind) {
    case K.Grade: return num(v.points);
    case K.Absent: return "Abs";
    case K.Exempted: return "Disp";
    case K.NotGraded: return "N.Not";
    case K.Unfit: return "Inap";
    case K.Unreturned: return "N.Rdu";
    case K.AbsentZero: return "Abs 0";
    case K.UnreturnedZero: return "N.Rdu 0";
    case K.Congratulations: return "Félic.";
    default: return typeof v.points === "number" ? num(v.points) : "";
  }
}

function mealItems(meal) {
  if (!meal) return [];
  const parts = [];
  for (const key of ["entry", "main", "side", "fromage", "dessert"]) {
    const list = meal[key];
    if (Array.isArray(list)) {
      for (const f of list) {
        const name = f && f.name ? String(f.name).trim() : "";
        if (name) parts.push(name);
      }
    }
  }
  return parts;
}

function pickGradesPeriod(session, P) {
  const tabs = session && session.userResource && session.userResource.tabs;
  const tab = tabs && typeof tabs.get === "function" ? tabs.get(P.TabLocation.Grades) : null;
  if (!tab) return null;
  const periods = Array.isArray(tab.periods) ? tab.periods : [];
  const now = Date.now();
  const current = periods.find((p) => p && p.startDate && p.endDate && +p.startDate <= now && now <= +p.endDate);
  return tab.defaultPeriod || current || periods[0] || null;
}

export async function buildSnapshot(P, session, now = new Date()) {
  const today = startOfDay(now);
  const user = (session && session.userResource) || {};
  const snap = {
    v: 1,
    fetchedAt: iso(now),
    user: {
      name: user.name || "",
      className: user.className || "",
      school: user.establishmentName || "",
    },
    lessons: [],
    homework: [],
    grades: [],
    unread: null,
    menus: [],
    errors: {},
  };

  // 1. Timetable: today + the next 7 days, so the widget can roll over to the next school day.
  try {
    const tt = await P.timetableFromIntervals(session, today, addDays(today, 8));
    P.parseTimetable(session, tt, {
      withSuperposedCanceledClasses: false,
      withCanceledClasses: true,
      withPlannedClasses: true,
    });
    snap.lessons = (tt.classes || [])
      .map(mapClass)
      .filter(Boolean)
      .sort((a, b) => (a.start < b.start ? -1 : a.start > b.start ? 1 : 0));
  } catch (e) {
    snap.errors.timetable = errText(e);
  }

  // 2. Homework due in the next two weeks.
  try {
    const list = await P.assignmentsFromIntervals(session, today, addDays(today, 14));
    snap.homework = (list || [])
      .map((a) => ({
        due: iso(a.deadline),
        subject: (a.subject && a.subject.name) || "",
        text: htmlToText(a.description).slice(0, 400),
        done: !!a.done,
        color: a.backgroundColor || null,
      }))
      .filter((h) => h.due)
      .sort((a, b) => (a.due < b.due ? -1 : a.due > b.due ? 1 : 0));
  } catch (e) {
    snap.errors.homework = errText(e);
  }

  // 3. Latest grades of the current period.
  try {
    const period = pickGradesPeriod(session, P);
    if (period) {
      const ov = await P.gradesOverview(session, period);
      snap.grades = (ov.grades || [])
        .slice()
        .sort((a, b) => +new Date(b.date) - +new Date(a.date))
        .slice(0, 8)
        .map((g) => ({
          date: iso(g.date),
          subject: (g.subject && g.subject.name) || "",
          value: gradeValueText(P, g.value),
          outOf: gradeValueText(P, g.outOf),
          average: gradeValueText(P, g.average),
          coefficient: typeof g.coefficient === "number" ? g.coefficient : 1,
          comment: g.comment || "",
        }));
    }
  } catch (e) {
    snap.errors.grades = errText(e);
  }

  // 4. Unread messages.
  try {
    const d = await P.discussions(session);
    const items = (d && d.items) || [];
    snap.unread = items.reduce((sum, x) => sum + (x && x.numberOfMessagesUnread ? x.numberOfMessagesUnread : 0), 0);
  } catch (e) {
    snap.errors.discussions = errText(e);
  }

  // 5. Canteen menus for this week and next week.
  try {
    const seen = new Set();
    for (const when of [today, addDays(today, 7)]) {
      const week = await P.menus(session, when);
      for (const m of (week && week.days) || []) {
        if (!m || !m.date) continue;
        const key = dayKey(m.date);
        if (seen.has(key)) continue;
        seen.add(key);
        const lunch = mealItems(m.lunch);
        if (lunch.length) snap.menus.push({ day: key, lunch });
      }
    }
    snap.menus.sort((a, b) => (a.day < b.day ? -1 : 1));
  } catch (e) {
    snap.errors.menus = errText(e);
  }

  return snap;
}

export { DAY };
