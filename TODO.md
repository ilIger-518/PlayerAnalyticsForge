# Playeranalytics TODO

## Web endpoint (PLAN-inspired)
- Add optional access token and IP allowlist
- Expose /api/summary, /api/events, /api/players (done) and keep schemas stable
- Add player session duration rollups
- Add per-world and per-dimension breakdowns
- Add simple charts (joins per day, active players)

## Data management
- Configurable retention window and cleanup job
- Export endpoints (CSV/JSON)
- SQLite migrations for schema updates

## Quality
- Add integration test for SQLite queries
- Add command to print diagnostics in-game
- Add config file to change web host/port
