# PlayerAnalytics Mod Documentation

**Version**: 1.1.1  
**Minecraft Version**: 1.20.1  
**Mod Loader**: Minecraft Forge 47.3.0  
**License**: MIT  

---

## Table of Contents

1. [Overview](#overview)
2. [Features](#features)
3. [Installation & Setup](#installation--setup)
4. [Configuration](#configuration)
5. [Discord Integration](#discord-integration)
6. [In-Game Commands](#in-game-commands)
7. [Web Dashboard](#web-dashboard)
8. [REST API Reference](#rest-api-reference)
9. [Database Schema](#database-schema)
10. [Multi-Server Network](#multi-server-network)
11. [Troubleshooting](#troubleshooting)
12. [Development](#development)

---

## Overview

**PlayerAnalytics** is a comprehensive server analytics and player tracking mod for Minecraft Forge servers. It provides real-time metrics, detailed player statistics, combat tracking, and multi-server network monitoring through an intuitive web dashboard and REST API.

### Key Highlights

- 📊 **Real-time Analytics**: Track player activity, playtime, and engagement metrics
- ⚔️ **Combat Statistics**: Monitor PvP/PvE kills, death causes, weapon usage
- 🌍 **Multi-Server Support**: Track players across multiple connected servers
- 💻 **Web Dashboard**: Beautiful, interactive dashboard at `http://server:8804`
- 🔌 **REST API**: Comprehensive API for integrating analytics into external tools
- 💬 **Discord Integration**: Send real-time notifications via a Discord bot
- ⚙️ **Highly Configurable**: TOML-based configuration with ~25 settings
- 🗄️ **SQLite Database**: Local persistent storage with automatic maintenance

---

## Features

### Core Analytics

#### Session & Playtime Tracking
- Automatic join/leave event recording
- Total playtime calculation (total, active, AFK)
- Session duration tracking
- Login/logout timestamps with UTC timezone support
- New vs returning player classification
- Player retention metrics (1-day, 7-day, 30-day)

#### Combat Statistics
- Kill/death tracking with K/D ratio calculation
- PvP vs PvE kill separation
- Weapon usage tracking (sword, bow, axe, etc.)
- Death cause recording (fall, mob, player, etc.)
- Kill streak tracking (current and maximum)
- Player vs player kill matrix (who killed whom)
- Most deadly player rankings

#### Player Activity
- AFK detection (5 minutes default, configurable)
- Daily activity trends (joins/leaves per day)
- Hourly activity distribution (peak hours detection)
- Last seen timestamp tracking
- Activity calendar heatmap

#### Server Performance Monitoring
- TPS (Ticks Per Second) tracking
- RAM usage monitoring
- CPU usage tracking
- Entity count monitoring
- Real-time server metrics

#### World Analytics
- Per-world player tracking
- Per-dimension statistics (Overworld, Nether, End, custom)
- World-specific playtime breakdown
- Per-world kill statistics (PvP/PvE by world)

#### Multi-Server Network Features
- Cross-server player tracking
- Player server transfer detection and logging
- Network-wide statistics aggregation
- Server comparison by player count and activity
- Server registry and online status tracking

#### Discord Notifications
- Real-time Discord bot integration
- Player join/leave notifications with session duration
- Kill notifications (PvP/PvE with weapon info)
- Death notifications with death cause
- Server milestone alerts
- Periodic server stats summaries
- Color-coded embed formatting for easy recognition
- Configurable notification categories

---

## Installation & Setup

### Prerequisites

- Minecraft Forge 1.20.1 (version 47.3.0 or compatible)
- Java 17 or higher
- 50 MB+ disk space for database

### Installation Steps

1. **Download the Mod**
   - Place `PlayerAnalytics-1.20.1-1.1.1.jar` in your server's `mods` folder

2. **Start Server**
   ```bash
   ./start.sh  # or your server launcher
   ```

3. **Initial Configuration**
   - The mod will create configuration files on first run
   - Files are located in: `config/playeranalytics-common.toml`
   - Database will be created at: `config/playeranalytics.sqlite`

4. **Access Web Dashboard**
   - Open browser to: `http://localhost:8804` (default)
   - Customize host/port in configuration

### First Run

On first launch, the mod will:
- Create SQLite database with 13+ tables
- Generate `playeranalytics-common.toml` config file
- Initialize web server
- Start tracking player events

---

## Configuration

### Configuration File Location

```
config/playeranalytics-common.toml
```

### Configuration Categories

#### Web Server Configuration

```toml
[playeranalytics.webserver]
  # Host address (0.0.0.0 = all interfaces, 127.0.0.1 = localhost only)
  host = "0.0.0.0"
  
  # Port for web dashboard (1024-65535)
  port = 8804
  
  # Enable or disable the web server
  enabled = true
```

#### Security Configuration

```toml
[playeranalytics.security]
  # Require authentication for web dashboard access
  requireAuth = false
  
  # Access token (only used if requireAuth = true)
  accessToken = ""
  
  # List of allowed IP addresses (empty = allow all)
  # Example: ["127.0.0.1", "192.168.1.0/24"]
  ipAllowlist = []
```

#### Feature Toggles

```toml
[playeranalytics.features]
  # Track player playtime and sessions
  trackPlaytime = true
  
  # Track combat statistics (kills, deaths, weapons)
  trackCombat = true
  
  # Track player sessions (join/leave events)
  trackSessions = true
  
  # Track AFK time and periods
  trackAFK = true
  
  # Track daily activity trends and patterns
  trackActivity = true
```

#### Performance Configuration

```toml
[playeranalytics.performance]
  # Dashboard auto-refresh interval in milliseconds (1000-60000)
  dashboardRefreshInterval = 5000
  
  # Server metrics recording interval in ticks (20 ticks = 1 second)
  metricsRecordingInterval = 200
  
  # Activity update interval in ticks
  activityUpdateInterval = 6000
  
  # Seconds of inactivity before player is considered AFK (60-3600)
  afkTimeoutSeconds = 300
```

#### Data Retention Configuration

```toml
[playeranalytics.dataRetention]
  # Number of days to keep old data (0 = keep forever)
  retentionDays = 365
  
  # Automatically delete old data based on retention period
  autoCleanupEnabled = false
  
  # How often to run cleanup (in hours, 1-168)
  cleanupIntervalHours = 24
```

#### Network Configuration

```toml
[playeranalytics.network]
  # Enable network features for multi-server setups
  enabled = false
  
  # Name of the server network (e.g., "MyNetwork")
  networkName = "DefaultNetwork"
  
  # Unique ID for this server (e.g., "survival-1", "pvp-main")
  serverId = "server-1"
  
  # URL of central server for statistics sync
  centralServerUrl = "http://localhost:8805"
  
  # How often to sync stats to central server (60-3600 seconds)
  syncIntervalSeconds = 300
```

---

## Discord Integration

PlayerAnalytics can send real-time event notifications to Discord using a bot token. This allows server admins and moderators to stay informed about player activity without accessing the web dashboard.

**Status**: Discord bot login works, but embed sending is currently blocked by a JDA beta method mismatch (see troubleshooting).

### Setting Up the Discord Bot

1. **Create a Discord App and Bot**:
  - Go to: [https://discord.com/developers/applications](https://discord.com/developers/applications)
  - Create a new application
  - Add a **Bot**
  - Copy the **Bot Token**

2. **Invite the Bot to Your Server**:
  - Generate an invite with `Send Messages` and `Embed Links` permissions
  - Add the bot to your Discord server

3. **Configure the Mod**:
  - Open `config/playeranalytics-common.toml`
  - Find or create the `[playeranalytics.discord]` section
  - Set `enabled = true`
  - Paste your bot token into `botToken = "..."`
  - Set your target channel ID in `channelId = "..."`

### Discord Configuration

```toml
[playeranalytics.discord]
  # Enable Discord bot integration
  enabled = false
  
  # Discord bot token (keep this secret)
  botToken = ""
  
  # Target Discord channel ID for notifications
  channelId = ""
  
  # Optional Discord guild ID (improves channel lookup reliability)
  guildId = ""
  
  # Send notifications when players join
  notifyJoins = true
  
  # Send notifications when players leave (includes session duration)
  notifyLeaves = true
  
  # Send notifications for kills (PvP and PvE)
  notifyKills = false
  
  # Send notifications when players die (includes death cause)
  notifyDeaths = false
  
  # Send notifications for server milestones
  notifyMilestones = true
  
  # Send periodic server stats summaries
  notifyStats = false
```

### Notification Examples

**Player Join Notification**:
```
⬇️ Player Joined
Player1
[Timestamp]
```

**Player Leave Notification**:
```
⬆️ Player Left
Player1
Session Duration: 2h 30m 45s
[Timestamp]
```

**PvP Kill Notification**:
```
⚔️ PvP Kill
Player1 eliminated Player2
Weapon: Diamond Sword
Type: PvP
[Timestamp]
```

**PvE Kill Notification**:
```
⚔️ PvE Kill
Player1 eliminated Zombie
Weapon: Iron Sword
Type: PvE
[Timestamp]
```

**Death Notification**:
```
💀 Player Death
Player1 died
Cause: Fall Damage
[Timestamp]
```

**Milestone Notification**:
```
🎉 Server Milestone
Player1 reached 1,000 total kills!
[Timestamp]
```

**Server Stats Notification**:
```
📊 Server Stats - Survival
Players Online: 8
TPS: 🟢 19.8
[Timestamp]
```

### Customization

Discord notifications include:
- **Embedded format** with color coding:
  - 🟢 Green for joins and milestones
  - 🔴 Red for PvP kills
  - 🟡 Yellow for PvE kills
  - 🟣 Purple for deaths and milestones
  - 🔵 Blue for server stats
- **Timestamps** in ISO 8601 format (UTC)
- **Escape sequence handling** for special characters in player names

### Troubleshooting Discord

**Problem**: Bot token invalid

**Solutions**:
1. Verify the bot token is correct
2. Regenerate the token in the Discord developer portal
3. Restart the server after updating the token

**Problem**: Notifications not appearing

**Solutions**:
1. Enable the feature: `enabled = true`
2. Ensure the bot is in your server and online
3. Set correct `channelId` and (optional) `guildId`
4. Check the bot has `Send Messages` and `Embed Links` permissions
5. Enable specific notification types: `notifyJoins = true`, etc.
6. Look for error messages in server logs

**Problem**: `NoSuchMethodException: net.dv8tion.jda.api.EmbedBuilder.setTimestamp`

**Solutions**:
1. This indicates a JDA beta API mismatch with embed timestamp handling
2. Temporarily disable timestamp setting in the Discord embed builder
3. Update the integration to use the correct method name for the JDA version in use

---

## In-Game Commands

All commands start with `/analytics`:

### `/analytics`
Shows your own player statistics in chat.

**Usage**: `/analytics`

**Output**:
- Total playtime (with active/AFK breakdown)
- Session statistics (joins/leaves)
- Combat statistics (kills, deaths, K/D ratio, PvP/PvE, streaks)
- First join date
- Last seen timestamp
- Link to web dashboard

**Permissions**: Everyone (default)

---

### `/analytics <player>`
Shows another player's statistics.

**Usage**: `/analytics Player1`

**Output**: Same as `/analytics` but for the specified player

**Permissions**: Everyone (no permission check in current version)

---

### `/analytics reload`
Triggers configuration reload (server restart recommended for full effect).

**Usage**: `/analytics reload`

**Output**: Confirmation message

**Permissions**: Op/Admin (recommended)

---

### `/analytics export`
Exports all player statistics to a CSV file.

**Usage**: `/analytics export`

**Output**:
- Creates timestamped CSV file: `config/playeranalytics_export_[timestamp].csv`
- Shows player count and file location

**CSV Columns**:
```
UUID, Name, Total Playtime (sec), Active Playtime (sec), AFK Time (sec),
Joins, Leaves, Kills, PvP Kills, PvE Kills, Deaths, Current Streak,
Max Streak, First Join, Last Seen
```

**Permissions**: Op/Admin (recommended)

---

### `/analytics debug`
Displays server diagnostics and configuration status.

**Usage**: `/analytics debug`

**Output**:
- Web server status (host:port, enabled/disabled)
- Feature toggle status (✓/✗ for each feature)
- Database file path
- Database size (KB)
- Players tracked
- Total events recorded
- Total kills recorded
- Database connection status

**Permissions**: Op/Admin (recommended)

---

### `/analytics compare <player1> <player2>`
Compares two players' statistics side-by-side.

**Usage**: `/analytics compare Player1 Player2`

**Output**:
- Kills comparison (highlights higher)
- Deaths comparison (highlights lower)
- K/D Ratio (highlights better)
- Total playtime comparison

**Permissions**: Everyone (default)

---

### `/analytics leaderboard [category]`
Shows top 10 players by specified metric.

**Usage**: `/analytics leaderboard kills`

**Categories**:
- `kills` - Most total kills
- `pvp-kills` - Most player kills
- `pve-kills` - Most mob kills
- `deaths` - Most deaths
- `kd-ratio` - Best K/D ratio
- `playtime` - Most playtime
- `sessions` - Most sessions

**Permissions**: Everyone (default)

---

## Web Dashboard

### Access

- **URL**: `http://server:8804` (default, configurable)
- **Default Port**: 8804
- **Default Host**: 0.0.0.0 (all interfaces)

### Dashboard Features

#### Server Overview
- Current player count
- TPS (Ticks Per Second)
- RAM/CPU usage
- Entity count
- Uptime metrics

#### Player Statistics
- Real-time player list
- Playtime distribution (pie chart)
- Player count over time (line graph)
- Join/leave activity (bar chart)

#### Leaderboards
- Top 10 most active players
- Top 10 highest K/D ratio
- Top 10 longest playtime
- Top 10 most kills (total, PvP, PvE)

#### Combat Analytics
- Total kills/deaths graph
- PvP vs PvE breakdown
- Weapon usage statistics
- Death cause breakdown
- Kill matrix (heatmap of player vs player kills)

#### World Analytics
- Players per world distribution
- Playtime by world/dimension
- Kills by world
- Per-world statistics

#### Activity Analysis
- Hourly activity heatmap (when are players active?)
- Daily activity trends
- Weekly patterns
- Monthly overview

#### Table Features
- **Sorting**: Click column headers to sort ascending/descending
- **Search**: Real-time filtering with search box
- **Result Count**: Shows filtered result count with badge

---

## REST API Reference

### Base URL

```
http://server:8804/api
```

### Response Format

All responses return JSON. Errors include `"error"` field with description.

### Available Endpoints

#### Server Summary

**`GET /api/summary`**  
Returns overall server statistics.

**Response**:
```json
{
  "joins": 150,
  "leaves": 145,
  "unique_players": 25,
  "total_sessions": 150,
  "total_kills": 1200,
  "last_event_time": "2026-02-26T12:30:45Z"
}
```

---

#### Players

**`GET /api/players?limit=50`**  
Lists all tracked players with pagination.

**Query Parameters**:
- `limit` (optional): Number of players to return (default: 50)

**Response**:
```json
[
  {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Player1",
    "total_playtime": 86400,
    "total_kills": 15,
    "total_deaths": 5
  }
]
```

---

#### Player Details

**`GET /api/player/{uuid}`**  
Detailed statistics for a specific player.

**Response**:
```json
{
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Player1",
  "total_playtime_seconds": 86400,
  "active_playtime_seconds": 72000,
  "total_afk_seconds": 14400,
  "total_joins": 5,
  "total_leaves": 5,
  "total_kills": 15,
  "pvp_kills": 3,
  "pve_kills": 12,
  "total_deaths": 5,
  "kd_ratio": 3.0,
  "current_streak": 2,
  "max_streak": 7,
  "first_join": "2026-01-15T10:30:45Z",
  "last_seen": "2026-02-26T12:30:45Z"
}
```

---

#### Online Players

**`GET /api/players/online`**  
Currently online players.

**Response**:
```json
[
  {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Player1",
    "joined_time": "2026-02-26T10:00:00Z"
  }
]
```

---

#### Leaderboards

**`GET /api/leaderboard/{type}?limit=10`**  
Leaderboard for specified metric.

**Types**:
- `kills` - Total kills
- `pvp` - PvP kills
- `pve` - PvE kills
- `deaths` - Total deaths
- `kd_ratio` - K/D ratio
- `playtime` - Total playtime
- `sessions` - Total sessions

**Response**:
```json
[
  {
    "rank": 1,
    "player_uuid": "550e8400-e29b-41d4-a716-446655440000",
    "player_name": "Player1",
    "value": 42
  }
]
```

---

#### Combat Statistics

**`GET /api/combat`**  
Overall combat statistics.

**Response**:
```json
{
  "total_kills": 1200,
  "total_deaths": 800,
  "pvp_kills": 300,
  "pve_kills": 900,
  "average_kd_ratio": 1.5
}
```

---

#### Weapon Statistics

**`GET /api/weapons`**  
Weapon usage breakdown.

**Response**:
```json
[
  {
    "weapon": "diamond_sword",
    "kills": 450
  },
  {
    "weapon": "bow",
    "kills": 120
  }
]
```

---

#### World Statistics

**`GET /api/worlds`**  
Player and playtime distribution across worlds.

**Response**:
```json
[
  {
    "worldName": "minecraft:overworld",
    "playerCount": 8,
    "totalPlaytime": 86400,
    "currentPlayers": 0
  },
  {
    "worldName": "minecraft:the_nether",
    "playerCount": 2,
    "totalPlaytime": 14400,
    "currentPlayers": 0
  }
]
```

**`GET /api/world/{worldName}`**  
Detailed statistics for a specific world.

---

#### Activity Trends

**`GET /api/activity/trends?limit=30`**  
Daily activity over recent period.

**Response**:
```json
[
  {
    "date": "2026-02-26",
    "unique_players": 15,
    "total_sessions": 25,
    "joins": 25,
    "avg_duration": 3600
  }
]
```

---

#### Hourly Activity

**`GET /api/activity/hourly`**  
Player joins by hour of day (UTC).

**Response**:
```json
[
  {
    "hour": 0,
    "join_count": 5
  },
  {
    "hour": 1,
    "join_count": 2
  }
]
```

---

#### Server Performance Metrics

**`GET /api/metrics`**  
Current server metrics.

**Response**:
```json
{
  "tps": 19.8,
  "ram_used_mb": 1024,
  "ram_max_mb": 2048,
  "cpu_usage": 45.2,
  "entity_count": 250,
  "chunk_count": 1250
}
```

---

#### Multi-Server Network

**`GET /api/network/stats/{networkName}`**  
Network-wide statistics.

**Response**:
```json
{
  "networkName": "MyNetwork",
  "serverCount": 3,
  "uniquePlayers": 42,
  "totalTransfers": 128
}
```

---

**`GET /api/network/comparison/{networkName}`**  
Compare all servers in network.

**Response**:
```json
[
  {
    "serverId": "survival-1",
    "serverName": "Survival Server",
    "uniquePlayers": 15,
    "currentPlayers": 8,
    "totalVisits": 45
  }
]
```

---

**`GET /api/player/servers/{playerUuid}`**  
Player's server history across network.

**Response**:
```json
[
  {
    "serverId": "survival-1",
    "joinedTime": "2026-02-26T12:00:00Z",
    "leftTime": "2026-02-26T13:00:00Z",
    "durationSeconds": 3600,
    "current": false
  }
]
```

---

## Database Schema

### Tables Overview

| Table | Purpose | Rows |
|-------|---------|------|
| `player_stats` | Player statistics summary | ~Players |
| `player_sessions` | Join/leave events | ~Events |
| `player_session_data` | Session duration details | ~Sessions |
| `kill_details` | Individual kill records | ~Kills |
| `weapon_stats` | Weapon usage tracking | ~Weapons |
| `death_causes` | Death cause records | ~Deaths |
| `player_kill_matrix` | Player vs player kills | ~PvP kills |
| `server_metrics` | TPS/RAM/CPU history | ~Growing |
| `daily_activity` | Per-day statistics | ~Days |
| `hourly_activity` | Per-hour statistics | ~Hours (24) |
| `world_playtime` | Per-world playtime | ~Players × Worlds |
| `world_kills` | Per-world kill records | ~Kills |
| `world_sessions` | Per-world sessions | ~Sessions |
| `servers` | Multi-server network registry | ~Servers |
| `player_server_history` | Player server transfers | ~Transfers |
| `network_sync_log` | Sync operation logs | ~Syncs |

### Key Indexes

All tables have indexes on frequently queried columns:
- `player_uuid` (for fast player lookups)
- `world_name` (for world analytics)
- `server_id` (for network features)
- Date/time fields for range queries

---

## Multi-Server Network

### Setup Example

Create a cluster with 3 servers sharing analytics:

**Survival Server** (`config/playeranalytics-common.toml`):
```toml
[playeranalytics.network]
  enabled = true
  networkName = "MyNetwork"
  serverId = "survival-1"
  centralServerUrl = "http://central.example.com:8804"
  syncIntervalSeconds = 300
```

**PvP Server**:
```toml
[playeranalytics.network]
  enabled = true
  networkName = "MyNetwork"
  serverId = "pvp-1"
  centralServerUrl = "http://central.example.com:8804"
  syncIntervalSeconds = 300
```

**Creative Server**:
```toml
[playeranalytics.network]
  enabled = true
  networkName = "MyNetwork"
  serverId = "creative-1"
  centralServerUrl = "http://central.example.com:8804"
  syncIntervalSeconds = 300
```

### Player Tracking Flow

1. Player joins **Survival Server** → Creates server session record
2. Player uses `/server pvp` → Automatically:
   - Marks Survival session as ended
   - Records transfer event to PvP server
   - Creates new PvP session
3. Player can query `/analytics` on any server → Shows full history
4. Admin queries `/api/network/stats/MyNetwork` → Gets combined network stats

### Network Queries

```bash
# Get network overview
curl http://central.example.com:8804/api/network/stats/MyNetwork

# Compare servers in network
curl http://central.example.com:8804/api/network/comparison/MyNetwork

# Get specific player's server history
curl http://central.example.com:8804/api/player/servers/550e8400-e29b-41d4-a716-446655440000
```

---

## Troubleshooting

### Web Dashboard Not Accessible

**Problem**: Cannot reach `http://localhost:8804`

**Solutions**:
1. Check if web server is enabled:
   ```toml
   [playeranalytics.webserver]
     enabled = true
   ```

2. Check if port is correct:
   ```toml
   port = 8804
   ```

3. Check if firewall allows port 8804

4. Try `http://127.0.0.1:8804` instead

5. Check server logs for errors:
   ```bash
   grep -i "analytics web" latest.log
   ```

---

### Database Errors

**Problem**: "Failed to retrieve player stats"

**Solutions**:
1. Check database file exists: `config/playeranalytics.sqlite`
2. Verify permissions: `ls -la config/playeranalytics.sqlite`
3. Check SQLite JDBC driver in logs:
   ```bash
   grep -i "sqlite" latest.log
   ```
4. Try backing up and deleting database (will regenerate):
   ```bash
   mv config/playeranalytics.sqlite config/playeranalytics.sqlite.bak
   ```

---

### Commands Not Working

**Problem**: `/analytics` command not recognized

**Solutions**:
1. Verify mod is loaded:
   ```bash
   grep -i "PlayerAnalytics initialized" latest.log
   ```
2. Check for error messages in logs
3. Restart server after mod update

---

### Performance Issues

**Problem**: Server TPS drops after installing mod

**Solutions**:
1. Reduce metrics recording frequency:
   ```toml
   [playeranalytics.performance]
     metricsRecordingInterval = 400  # Increase from 200
   ```

2. Disable unused features:
   ```toml
   [playeranalytics.features]
     trackCombat = false  # If not needed
   ```

3. Increase AFK timeout:
   ```toml
   afkTimeoutSeconds = 600  # 10 minutes instead of 5
   ```

4. Enable auto-cleanup:
   ```toml
   [playeranalytics.dataRetention]
     autoCleanupEnabled = true
     retentionDays = 90  # Delete old data
   ```

---

### Multi-Server Issues

**Problem**: Player transfers not being recorded

**Solutions**:
1. Verify network is enabled on both servers:
   ```toml
   [playeranalytics.network]
     enabled = true
   ```

2. Check server IDs are unique (not same on multiple servers)

3. Verify central server URL is correct and reachable

4. Check logs for sync errors:
   ```bash
   grep -i "network\|sync" latest.log
   ```

---

## Development

### Building from Source

```bash
cd path/to/mod
./gradlew build
```

Build output: `app/build/libs/app.jar`

### Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/playeranalyticsforge/
│   │   │   ├── PlayeranalyticsForgeMod.java     # Main mod class
│   │   │   ├── AnalyticsConfig.java             # Configuration system
│   │   │   ├── PlayerAnalyticsDb.java           # Database operations
│   │   │   ├── AnalyticsWebServer.java          # Web server & API
│   │   │   ├── AnalyticsCommands.java           # In-game commands
│   │   │   └── PlayerEvents.java                # Event handlers
│   │   └── resources/
│   └── test/java/
└── build.gradle
```

### Key Classes

- **PlayeranalyticsForgeMod.java** (15 lines)
  - Mod initialization
  - Config registration
  - SQLite driver loading

- **AnalyticsConfig.java** (190 lines)
  - ForgeConfigSpec-based configuration
  - 5 categories with 20+ settings
  - Auto-generation of TOML file

- **PlayerAnalyticsDb.java** (1900+ lines)
  - Database initialization and queries
  - Player stats tracking
  - Combat statistics recording
  - World and network tracking

- **AnalyticsWebServer.java** (1600+ lines)
  - HTTP server implementation
  - REST API endpoints
  - Web dashboard HTML/CSS/JS
  - Table sorting/filtering functionality

- **AnalyticsCommands.java** (450+ lines)
  - In-game command implementations
  - Player stat display
  - Export and debug commands
  - Player comparison

- **PlayerEvents.java** (210+ lines)
  - Join/leave tracking
  - Combat event handling
  - AFK detection
  - Server transfer detection (network mode)

### Adding New Features

1. **New Configuration**: Add to `AnalyticsConfig.java` static block
2. **New Metrics**: Add database table in `PlayerAnalyticsDb.init()`
3. **New Events**: Add handler in `PlayerEvents.java`
4. **New API**: Add endpoint in `AnalyticsWebServer.java`
5. **Database Query**: Add method in `PlayerAnalyticsDb.java`

### Testing

```bash
# Run development client
./gradlew runClient

# Run dedicated server
./gradlew runServer

# Build JAR
./gradlew build
```

---

## Version History

### v1.1 (Current)
- Discord bot integration (real-time notifications)
- Enhanced event notifications for joins, leaves, kills, deaths
- Session duration tracking in Discord notifications
- Configurable Discord notification categories
- Milestone and stats summary notifications

### v1.0
- Core analytics framework
- Combat statistics
- Web dashboard with sorting/filtering
- Configuration system
- In-game commands (show/export/debug/compare/reload)
- World analytics
- Multi-server network support
- REST API with 20+ endpoints

### Planned Features
- GeoIP location tracking
- Advanced cohort analysis
- A/B testing framework
- Custom event tracking
- Plugin API for extensions
- MySQL/PostgreSQL support
- Telegram bot integration
- Live Twitch alerts

---

## Support & Contributing

### Reporting Issues

Please report bugs on the GitHub issues page with:
- Server version (1.20.1, etc.)
- Forge version (47.3.0, etc.)
- Description of issue
- Relevant log entries
- Steps to reproduce

### Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create feature branch
3. Make changes
4. Test thoroughly
5. Submit pull request

---

## License

PlayerAnalytics is released under the MIT License. See LICENSE file for details.

---

## Credits

**Author**: PlayerAnalyticsForge Team  
**Minecraft Version**: 1.20.1  
**Forge Version**: 47.3.0+  
**Database**: SQLite 3  

---

## FAQ

### Q: Will this mod impact server performance?
**A**: Minimal impact. Default settings record metrics every 10 ticks (0.5 seconds) and use efficient database indexing. Can be tuned further in config.

### Q: Can I use this with other analytics mods?
**A**: Yes, PlayerAnalytics is designed to coexist with other mods. It only tracks its own data.

### Q: How long does data retention take?
**A**: By default, data is kept for 365 days. Older data can be automatically cleaned up if `autoCleanupEnabled = true`.

### Q: Does this mod work in single-player?
**A**: Yes, though most features are designed for servers. Web dashboard works in single-player too.

### Q: Can I backup my analytics data?
**A**: Yes, simply backup the SQLite file: `config/playeranalytics.sqlite`

### Q: How do I reset analytics data?
**A**: Stop server, delete `config/playeranalytics.sqlite`, restart server. New database will be created.

### Q: Is the `/analytics export` command safe?
**A**: Yes, it exports to a new CSV file without modifying the database.

### Q: Can I access the API from external services?
**A**: Yes, the REST API is fully accessible via HTTP. Consider using IP allowlist for security.

---

**Last Updated**: February 26, 2026  
**Documentation Version**: 1.1.1
