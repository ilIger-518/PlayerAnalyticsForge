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
- [x] Session insights (average session length, sessions per day)
- [x] Login/logout timestamps with timezone support

### Player Retention & Activity
- [x] New vs returning player classification
- [x] Player retention metrics (1-day, 7-day, 30-day)
- [x] Last seen timestamps
- [x] Activity calendar heatmap (days active per month)
- [x] Hourly activity graph (peak hours)
- [x] Daily/weekly/monthly activity trends
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
  - [x] Player count over time (line graph)
  - [x] Join/leaves per day (bar chart)
  - [x] Playtime distribution (pie chart)
  - [x] Hourly activity heatmap
  - [ ] Geographic distribution map
- [x] Player profile pages (individual player stats)
- [x] Server overview dashboard
- [x] Real-time player list (currently online)
- [x] Leaderboards (most active, highest K/D, longest playtime)
- [x] Sorting and filtering for tables
- [x] Search functionality (table filtering)
- [ ] Date range selectors for graphs

### API Endpoints (REST)
- [x] `/api/player/{uuid}` - Individual player details
- [x] `/api/player/{uuid}/sessions` - Player session history
- [x] `/api/player/{uuid}/kills` - Player kill history
- [x] `/api/server/performance` - TPS, RAM, CPU stats
- [x] `/api/server/activity` - Activity graphs data
- [x] `/api/sessions/insights` - Session analytics
- [x] `/api/activity/trends` - Daily activity trends
- [x] `/api/activity/hourly` - Hourly activity distribution
- [x] `/api/leaderboard/{type}` - Top players by metric
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
- [x] Per-world player counts
- [x] Per-dimension statistics (Overworld, Nether, End)
- [x] World-specific playtime
- [x] Per-world kill statistics

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
- [x] Discord bot support (token + channel ID)
- [x] Configurable notifications (player joins, milestones, etc.)
- [ ] Daily/weekly summary reports to Discord
- [ ] Player achievement notifications

### Other Integrations
- [ ] Command block support for in-game stats display
- [ ] Scoreboard integration
- [ ] Placeholder API support (for other mods)

## 🎮 In-Game Features

### Commands
- [x] `/analytics` - Show own stats
- [x] `/analytics <player>` - Show other player stats
- [x] `/analytics reload` - Reload config
- [x] `/analytics export` - Export data
- [x] `/analytics debug` - Print diagnostics
- [x] `/analytics compare <player1> <player2>` - Compare players
- [x] `/analytics leaderboard [category]` - Show top players

### Player Notes & Moderation
- [ ] Add notes to player profiles (admin only)
- [ ] Track bans/kicks in database
- [ ] Link to punishment history
- [ ] Flagging system for suspicious activity

## ⚙️ Configuration & Customization

### Config File
- [x] Web server host/port configuration
- [x] Enable/disable specific features (combat, sessions, playtime, AFK, activity)
- [x] Performance settings (refresh intervals, AFK timeout)
- [x] Data retention settings (with auto-cleanup)
- [x] Security settings (access token, IP allowlist) - defined but not enforced yet
- [ ] Implement access token authentication in web server
- [ ] Implement IP allowlist enforcement in web server
- [ ] Database connection settings
- [x] Discord bot token and channel settings
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
- [x] Cross-server player tracking
- [x] Network-wide statistics
- [x] Server comparison views
- [x] Player server transfers tracking

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
