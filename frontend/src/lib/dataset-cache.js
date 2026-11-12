// The master data shared by the views. Loaded once per view render: another user or tab may have imported new
// master data meanwhile, so a navigation never shows an older copy. A failed load is not kept.
export function datasetCache(load) {
  let pending = null;
  return {
    get: () => (pending ??= load().catch((e) => { pending = null; throw e; })),
    invalidate: () => { pending = null; },
  };
}
