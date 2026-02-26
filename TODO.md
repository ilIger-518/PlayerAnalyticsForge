# Playeranalytics TODO - Plan Player Analytics Feature Parity

## ✅ Completed Features
- [x] Player join/leave tracking
- [x] SQLite database persistence
- [x] Kill/Death ratio tracking
- [x] Detailed kill tracking (per victim type)
- [x] Web dashboard at http://127.0.0.1:8804
- [x] Basic JSON API endpoints (/api/summary, /api/events, /api/players, /api/kills)

## 🎯 Core Analytics Features

### Session & Playtime Tracking
- [x] Session duration calculation (time between join and leave)
- [x] Total playtime per player
- [x] Active playtime (exclude AFK time)
- [x] AFK detection (no movement/interaction for X minutes)
- [ ] Session insights (average session length, sessions per day)
- [ ] Login/logout timestamps with timezone support

### Player Retention & Activity
- [ ] New vs returning player classification
- [ ] Player retention metrics (1-day, 7-day, 30-day)
- [ ] Last seen timestamps
- [ ] Activity calendar heatmap (days active per month)
- [ ] Hourly activity graph (peak hours)
- [ ] Daily/weekly/monthly activity trends
- [ ] Player churn analysis

### Combat Statistics
- [x] Separate PvP and PvE kills
- [x] Weapon usage tracking
- [x] Mob kill breakdown by type
- [x] Death causes tracking
- [x] Player vs player kill matrix
- [x] Longest kill streak tracking
- [x] Most deadly player rankings

## 📊 Web Dashboard Enhancements

### UI Components
- [x] Advanced graphs and charts (Chart.js or similar)
  - [ ] Player count over time (line graph)
  - [ ] Join/leaves per day (bar chart)
  - [x] Playtime distribution (pie chart)
  - [ ] Hourly activity heatmap
  - [ ] Geographic distribution map
- [x] Player profile pages (individual player stats)
- [ ] Server overview dashboard
- [ ] Real-time player list (currently online)
- [ ] Leaderboards (most active, highest K/D, longest playtime)
- [ ] Sorting and filtering for tables
- [ ] Search functionality
- [ ] Date range selectors for graphs

### API Endpoints (REST)
- [x] `/api/player/{uuid}` - Individual player details
- [x] `/api/player/{uuid}/sessions` - Player session history
- [ ] `/api/player/{uuid}/kills` - Player kill history
- [ ] `/api/server/performance` - TPS, RAM, CPU stats
- [ ] `/api/server/activity` - Activity graphs data
- [ ] `/api/leaderboard/{type}` - Top players by metric
- [ ] `/api/network` - Multi-server network stats (future)

## 🔧 Server Performance Monitoring

### System Metrics
- [x] TPS (Ticks Per Second) tracking
- [x] RAM usage tracking
- [x] CPU usage tracking
- [x] Entity count monitoring
- [ ] Chunk load count
- [ ] Average ping/latency per player

### World Statistics
- [ ] Per-world player counts
- [ ] Per-dimension statistics (Overworld, Nether, End)
- [ ] World-specific playtime
- [ ] Per-world kill statistics

## 🌍 Network & Connection Tracking

### Player Connection Data
- [ ] GeoIP location tracking (country, city)
- [ ] Join address/IP tracking
- [ ] Ping/latency history
- [ ] Connection quality metrics
- [ ] Nickname/username change history
- [ ] First join date tracking

## 💾 Data Management

### Database Features
- [ ] Add MySQL support (alternative to SQLite)
- [ ] Add PostgreSQL support
- [ ] Database migrations system
- [ ] Automatic database backups
- [ ] Configurable retention window (auto-delete old data)
- [ ] Data cleanup jobs (scheduled tasks)
- [ ] Database optimization/vacuum commands

### Export & Import
- [ ] Export data to CSV
- [ ] Export data to JSON
- [ ] Batch data export (all players, date range)
- [ ] Import historical data
- [ ] API endpoint for bulk data export

## 🔔 Integrations & Notifications

### Discord Integration
- [ ] Discord webhook support
- [ ] Configurable notifications (player joins, milestones, etc.)
- [ ] Daily/weekly summary reports to Discord
- [ ] Player achievement notifications

### Other Integrations
- [ ] Command block support for in-game stats display
- [ ] Scoreboard integration
- [ ] Placeholder API support (for other mods)

## 🎮 In-Game Features

### Commands
- [ ] `/analytics` - Show own stats
- [ ] `/analytics <player>` - Show other player stats
- [ ] `/analytics reload` - Reload config
- [ ] `/analytics export` - Export data
- [ ] `/analytics debug` - Print diagnostics
- [ ] `/analytics compare <player1> <player2>` - Compare players
- [ ] `/analytics leaderboard [category]` - Show top players

### Player Notes & Moderation
- [ ] Add notes to player profiles (admin only)
- [ ] Track bans/kicks in database
- [ ] Link to punishment history
- [ ] Flagging system for suspicious activity

## ⚙️ Configuration & Customization

### Config File
- [ ] Web server host/port configuration
- [ ] Access token/authentication for web UI
- [ ] IP allowlist for web access
- [ ] Enable/disable specific features
- [ ] Data retention settings
- [ ] Database connection settings
- [ ] Webhook URLs and settings
- [ ] Privacy settings (anonymize IPs, etc.)

### Customization
- [ ] Configurable web theme/colors
- [ ] Custom metrics/tracking
- [ ] Plugin hook system for extensions
- [ ] Custom SQL queries support

## 🧪 Quality & Testing

### Testing
- [ ] Integration tests for database queries
- [ ] Unit tests for analytics calculations
- [ ] Performance benchmarks
- [ ] Load testing for web server

### Documentation
- [ ] API documentation (OpenAPI/Swagger)
- [ ] User guide for web dashboard
- [ ] Admin setup guide
- [ ] Troubleshooting guide

## 🚀 Advanced Features (Plan Premium Features)

### Player Insights
- [ ] Player journey tracking (progression milestones)
- [ ] Play pattern analysis (when players are most active)
- [ ] Prediction of player churn
- [ ] Engagement score calculation

### Network Features (Multi-Server)
- [ ] Cross-server player tracking
- [ ] Network-wide statistics
- [ ] Server comparison views
- [ ] Player server transfers tracking

### Analytics
- [ ] Cohort analysis
- [ ] Funnel analysis (player progression)
- [ ] Custom event tracking
- [ ] A/B testing support

## 📝 Notes

### Current Implementation Status
- Basic analytics working in dev environment
- SQLite driver loaded via URLClassLoader fallback
- Web server accessible at http://127.0.0.1:8804
- Kill/death tracking functional
- Ready for feature expansion

### Next Priority Features (Recommended Order)
1. Session duration tracking (enhance existing join/leave logic)
2. Playtime calculation and display
3. Enhanced web dashboard with charts
4. Player profile pages
5. Server performance monitoring (TPS)
6. Activity graphs (hourly/daily)
7. AFK detection
8. PvP vs PvE separation
9. GeoIP integration
10. Export functionality
