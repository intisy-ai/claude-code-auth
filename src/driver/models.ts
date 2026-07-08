// @ts-nocheck
// NO static/hardcoded Claude model catalog. Hardcoded model ids go stale as Anthropic
// ships/retires models (a false list that no longer works is worse than an empty one),
// so the catalog is live-only: driver/index.ts `fetchModels()` pulls the real list from
// Anthropic /v1/models on login / "Refresh models" and it's cached. Before login there
// are simply no models to show (the account menu hides the Models section until a fetch
// succeeds). Keeping the export (empty) so importers stay valid.
export const models = {};
