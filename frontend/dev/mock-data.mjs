// Small synthetic dataset for the dev server. Fictitious people only; deterministic for a given seed.
import { addMonths, monthDates } from '../src/lib/dates.js';

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const AREAS = [
  { id: 'AN', name: 'Notrufannahme', color: '#B45309' },
  { id: 'DR', name: 'Disposition Rettung', color: '#15803D' },
  { id: 'TV', name: 'Transportvermittlung', color: '#2563EB' },
  { id: 'LF', name: 'Lageführung', color: '#7C3AED' },
  { id: 'TS', name: 'Technik und Systeme', color: '#0E7490' },
];

const s = (id, areaId, code, name, start, end, category, color, unpopular = false) =>
  ({ id, areaId, code, name, start, end, category, color, unpopular });
const SHIFTS = [
  s('AN-F', 'AN', 'A1', 'Annahme früh 06:30', '06:30', '14:30', 'EARLY', '#F3E3D8'),
  s('AN-T', 'AN', 'A2', 'Annahme Tag 07:00', '07:00', '19:00', 'DAY', '#F6EAE1'),
  s('AN-S', 'AN', 'A3', 'Annahme spät 14:00', '14:00', '22:00', 'LATE', '#EED9C9'),
  s('AN-N', 'AN', 'A4', 'Annahme Nacht 21:30', '21:30', '06:30', 'NIGHT', '#E7C8B0', true),
  s('DR-T', 'DR', 'R1', 'Disposition Tag 07:00', '07:00', '19:00', 'DAY', '#E3F0E8'),
  s('DR-M', 'DR', 'R2', 'Disposition Mitte 09:00', '09:00', '17:00', 'DAY', '#E3F0E8'),
  s('DR-A', 'DR', 'R3', 'Disposition Abend 13:00', '13:00', '21:00', 'LATE', '#CCE3D4'),
  s('DR-N', 'DR', 'R4', 'Disposition Nacht 19:00', '19:00', '07:00', 'NIGHT', '#B4D6C1', true),
  s('TV-F', 'TV', 'V1', 'Transport früh 07:30', '07:30', '15:30', 'EARLY', '#DCE6FC'),
  s('TV-S', 'TV', 'V2', 'Transport spät 11:30', '11:30', '19:30', 'LATE', '#CFDDFB'),
  s('LF-T', 'LF', 'L1', 'Lageführung Tag', '07:00', '19:00', 'DAY', '#EFE7FD'),
  s('LF-N', 'LF', 'L2', 'Lageführung Nacht', '19:00', '07:00', 'NIGHT', '#D5C0F9', true),
  s('TS-T', 'TS', 'S1', 'Technik Tag 08:00', '08:00', '16:30', 'DAY', '#E2EEF2'),
];

const ALL = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN', 'HOL'];
const WD = ['MON', 'TUE', 'WED', 'THU', 'FRI'];
const WE = ['SAT', 'SUN', 'HOL'];
const d = (areaId, days, from, to, min, max = null) => ({ areaId, days, date: null, from, to, min, max });
const DEMAND = [
  d('AN', ALL, '06:30', '07:00', 1), d('AN', ALL, '07:00', '19:00', 3), d('AN', ALL, '19:00', '21:30', 1),
  d('AN', ALL, '21:30', '06:30', 2),
  d('DR', ALL, '07:00', '19:00', 2), d('DR', ALL, '19:00', '07:00', 2),
  d('TV', WD, '07:30', '19:30', 1), d('TV', WE, '07:30', '15:30', 1),
  d('LF', ALL, '07:00', '19:00', 1, 1), d('LF', ALL, '19:00', '07:00', 1, 1),
  d('TS', WD, '08:00', '16:30', 1),
];

/** Shifts the mock solver tries to fill per area and day, in order of importance. */
export const TEMPLATE = {
  AN: ['AN-F', 'AN-T', 'AN-T', 'AN-S', 'AN-N', 'AN-N'],
  DR: ['DR-T', 'DR-M', 'DR-A', 'DR-N', 'DR-N'],
  TV: ['TV-F', 'TV-S'],
  LF: ['LF-T', 'LF-N'],
  TS: ['TS-T'],
};

const FIRST = ['Anna', 'Bernd', 'Clara', 'David', 'Eva', 'Florian', 'Gerda', 'Hannes', 'Ines', 'Jakob', 'Karin',
  'Lukas', 'Maria', 'Niklas', 'Olga', 'Paul', 'Rita', 'Stefan', 'Theresa', 'Ulrich', 'Vera', 'Walter', 'Yvonne', 'Zoe'];
const LAST = ['Ahorn', 'Birke', 'Eiche', 'Linde', 'Ulme', 'Esche', 'Fichte', 'Kiefer', 'Tanne', 'Lärche', 'Weide',
  'Pappel', 'Erle', 'Buche', 'Eibe', 'Hasel'];
const PRIMARY = ['AN', 'AN', 'AN', 'AN', 'AN', 'AN', 'AN', 'AN', 'AN', 'AN', 'AN', 'AN', 'TV', 'TV', 'TV',
  'TV', 'TV', 'DR', 'DR', 'DR', 'DR', 'DR', 'DR', 'DR', 'DR', 'DR', 'DR', 'DR', 'LF', 'LF', 'LF', 'LF', 'LF',
  'TS', 'TS', 'TS', 'TS', 'DR', 'DR', 'DR', 'AN', 'AN', 'LF', 'AN', 'DR', 'TV', 'AN', 'DR'];

const HOLIDAYS = [
  ['01-01', 'Neujahr'], ['01-06', 'Heilige Drei Könige'], ['05-01', 'Staatsfeiertag'], ['08-15', 'Mariä Himmelfahrt'],
  ['10-26', 'Nationalfeiertag'], ['11-01', 'Allerheiligen'], ['12-08', 'Mariä Empfängnis'], ['12-25', 'Christtag'],
  ['12-26', 'Stefanitag'],
];

const ABSENCE_TYPES = [
  { code: 'U', name: 'Urlaub', reducesTarget: true, chargesAccount: false, compensatoryRest: false, color: '#A3C585' },
  { code: 'K', name: 'Krankenstand', reducesTarget: true, chargesAccount: false, compensatoryRest: false, color: '#CBD5E1' },
  { code: 'FB', name: 'Fortbildung', reducesTarget: true, chargesAccount: false, compensatoryRest: false, color: '#BFDBFE' },
  { code: 'ZA', name: 'Zeitausgleich', reducesTarget: true, chargesAccount: true, compensatoryRest: false, color: '#CA8A04' },
  { code: 'ER', name: 'Ersatzruhe', reducesTarget: true, chargesAccount: false, compensatoryRest: true, color: '#8FD3C7' },
];

export function makeDataset(month, seed = 1) {
  const r = rng(seed);
  const pick = (xs) => xs[Math.floor(r() * xs.length)];
  const people = PRIMARY.map((primary, i) => {
    const second = { AN: 'TV', TV: 'AN', DR: 'AN', LF: 'DR', TS: 'DR' }[primary];
    const areaIds = r() < 0.4 ? [primary, second] : [primary];
    return {
      id: `P${String(i + 1).padStart(4, '0')}`,
      firstName: FIRST[i % FIRST.length],
      lastName: LAST[(i * 7) % LAST.length],
      shortName: (FIRST[i % FIRST.length][0] + LAST[(i * 7) % LAST.length].slice(0, 2)).toUpperCase(),
      weeklyHours: r() < 0.2 ? 30 : 40,
      areaIds,
      hoursAccount: Math.round((r() * 30 - 12) * 2) / 2,
      compRestBalance: Math.floor(r() * 4),
      familyRules: r() < 0.12 ? [{ weekdays: [2, 4], latestEnd: '18:00', earliestStart: null, noNights: true, note: 'Kinderbetreuung' }] : [],
      externalId: String(100 + i),
    };
  });
  const dates = monthDates(month);
  const year = Number(month.slice(0, 4));
  const holidays = [year - 1, year, year + 1].flatMap((y) => HOLIDAYS.map(([md, name]) => ({ date: `${y}-${md}`, name })));
  const absences = [];
  const wishes = [];
  const fixed = [];
  for (const p of people) {
    if (r() < 0.35) {
      const start = Math.floor(r() * (dates.length - 6));
      const len = 3 + Math.floor(r() * 5);
      for (let k = start; k < Math.min(dates.length, start + len); k++) absences.push({ personId: p.id, date: dates[k], typeCode: 'U' });
    } else if (r() < 0.15) {
      absences.push({ personId: p.id, date: pick(dates), typeCode: pick(['K', 'FB', 'ZA']) });
    }
    const nWishes = Math.floor(r() * 5);
    for (let k = 0; k < nWishes; k++) {
      const date = pick(dates);
      if (absences.some((a) => a.personId === p.id && a.date === date) || wishes.some((w) => w.personId === p.id && w.date === date)) continue;
      const kind = pick(['OFF', 'OFF', 'OFF', 'DUTY', 'SHIFT', 'NOT_SHIFT']);
      const own = SHIFTS.filter((sh) => p.areaIds.includes(sh.areaId));
      wishes.push({ personId: p.id, date, kind, shiftId: kind === 'SHIFT' || kind === 'NOT_SHIFT' ? pick(own).id : null });
    }
  }
  fixed.push({ personId: people[0].id, date: dates[0], shiftId: 'AN-T' });
  fixed.push({ personId: people[17].id, date: dates[1], shiftId: null });

  const history = [];
  for (const p of people) {
    for (let k = 1; k <= 3; k++) {
      const wishesMade = Math.floor(r() * 6);
      history.push({ personId: p.id, month: addMonths(month, -k), nights: Math.floor(r() * 6), weekendsWorked: Math.floor(r() * 4),
        holidays: Math.floor(r() * 2), unpopular: Math.floor(r() * 7), wishes: wishesMade, wishesMet: Math.floor(r() * (wishesMade + 1)),
        hours: 150 + Math.round(r() * 40) });
    }
  }

  return {
    format: 'dispofold/dataset',
    version: 1,
    source: 'synthetic',
    areas: AREAS,
    shifts: SHIFTS,
    demand: DEMAND,
    holidays,
    absenceTypes: ABSENCE_TYPES,
    people,
    restrictions: [{ personId: people[3].id, shiftId: 'AN-S', weekday: 0, kind: 'NEVER' }],
    history,
    periods: [{ month, absences, wishes, fixed, previousDuties: [] }],
  };
}

export const CONSTRAINTS = [
  ['qualification', 'HARD', 'Qualifikation', 'Dienste nur in Bereichen, für die die Person eingesetzt werden darf.', 1],
  ['dailyRest', 'HARD', 'Tägliche Ruhezeit', 'Mindestens 11 Stunden Ruhe zwischen zwei Diensten (KV, Ruhezeit).', 1],
  ['weeklyRest', 'HARD', 'Wöchentliche Ruhezeit', 'Mindestens 36 Stunden zusammenhängende Ruhe je Kalenderwoche.', 1],
  ['maxWeekHours', 'HARD', 'Höchstarbeitszeit Woche', 'Höchstens 60 Stunden je Kalenderwoche (KV, Durchrechnung).', 1],
  ['absence', 'HARD', 'Abwesenheit', 'Kein Dienst an Tagen mit Urlaub, Krankenstand oder Fortbildung.', 1],
  ['restrictionNever', 'HARD', 'Ausschluss Schicht', 'Schichten, die eine Person nie macht.', 1],
  ['coverage', 'MEDIUM', 'Bedarf gedeckt', 'Nicht gedeckter Bedarf in Personenminuten.', 1],
  ['targetHours', 'SOFT', 'Sollstunden', 'Geplante plus angerechnete Stunden nahe am Monatssoll inklusive Anteil des Stundenkontos.', 20],
  ['wishOff', 'SOFT', 'Wunsch frei', 'Wunsch nach einem freien Tag erfüllen.', 60],
  ['wishShift', 'SOFT', 'Wunsch Dienst', 'Gewünschten Dienst oder gewünschte Schicht einplanen.', 40],
  ['familyRule', 'SOFT', 'Familienregel', 'Spätestes Ende, frühester Beginn oder keine Nacht an bestimmten Wochentagen.', 80],
  ['blockLength', 'SOFT', 'Dienstblöcke', 'Bevorzugt 2 bis 4 Dienste am Stück, keine Einzeldienste.', 15],
  ['forwardRotation', 'SOFT', 'Vorwärtsrotation', 'Früh vor Spät vor Nacht innerhalb eines Blocks.', 10],
  ['fairNights', 'SOFT', 'Faire Nachtverteilung', 'Nächte gleichmäßig verteilen, mit Historie der Vormonate.', 25],
  ['fairWeekends', 'SOFT', 'Faire Wochenenden', 'Arbeitswochenenden gleichmäßig verteilen.', 25],
  ['fairUnpopular', 'SOFT', 'Unbeliebte Dienste', 'Unbeliebte Dienste gleichmäßig verteilen.', 15],
  ['restrictionOnRequest', 'SOFT', 'Nur auf Anfrage', 'Schichten, die eine Person nur nach Rückfrage macht.', 50],
].map(([id, level, label, description, defaultWeight]) => ({ id, level, label, description, defaultWeight }));

export const DEFAULT_RULES = {
  minDailyRestHours: 11, minWeeklyRestHours: 36, maxDutyHours: 12, maxWeekHours: 60, maxAverageWeekHours: 46.16,
  minBlock: 2, maxBlock: 4, maxWishesPerPerson: 6, holidayWorkEarnsCompRest: true, nightStart: '22:00', nightEnd: '06:00',
  nightMinHours: 2, accountCarryShare: 0.25, historyMonths: 3, weights: {},
};
