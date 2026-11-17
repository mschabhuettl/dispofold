// "Planungsziele" of the rules page: groups of quality rules with one Wichtigkeit each, a factor on the default
// weights of the group's constraints, and the night setting.

/** Group -> constraint ids (backend ConstraintCatalog); ids the catalogue lacks are left out. */
export const GROUPS = {
  blocks: ['blockLength', 'isolatedDayOff'],
  wishes: ['wishes'],
  fairness: ['fairNights', 'fairWeekends', 'fairHolidays', 'fairUnpopular'],
  hours: ['targetHours'],
  family: ['family'],
};

/** Wichtigkeit: level -> factor on the default weight. */
export const LEVELS = { less: 0.5, normal: 1, more: 2 };
/** Weights set by hand that are no level. */
export const CUSTOM = 'custom';

/** The tunable constraints of a group that the catalogue has. */
export function groupConstraints(group, constraints) {
  return constraints.filter((c) => c.tunable && GROUPS[group].includes(c.id));
}

/** The weight of a constraint at a level; weights are whole numbers. */
export const levelWeight = (c, level) => Math.round(c.defaultWeight * LEVELS[level]);

/** The level that all weights of the group match, else CUSTOM; null for a group without constraints. */
export function groupLevel(group, constraints, weights) {
  const list = groupConstraints(group, constraints);
  if (!list.length) return null;
  // normal first: with a default of 1, "weniger" gives the same weight
  return ['normal', 'less', 'more'].find((level) => list.every((c) => weights[c.id] === levelWeight(c, level))) ?? CUSTOM;
}

/** Sets the weights of the group to the level (overwriting weights set by hand). */
export function applyLevel(group, level, constraints, weights) {
  for (const c of groupConstraints(group, constraints)) weights[c.id] = levelWeight(c, level);
  return weights;
}

/** The night setting; rules saved before it existed mean HABIT. */
export const nightDistribution = (rules) => rules.nightDistribution ?? 'HABIT';

/** In HABIT, weight 0 for the usual distribution leaves the people who have one without any night rule. */
export const nightRuleMissing = (mode, weights) => mode === 'HABIT' && weights.shiftMix === 0;
