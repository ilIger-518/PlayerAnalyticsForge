# Playeranalytics (Forge 1.20.1)

Player analytics mod for Minecraft Forge 1.20.1.

## Server-side only
This mod is intended to run on the server only. Players do not need to install it on their client to join the server.

If a client does install it, nothing special happens on the client: the mod loads but does not add any client-only features.

## What it stores
Join/leave events are recorded to a local SQLite database.

- Location: config/playeranalytics.sqlite
- Table: player_sessions

## Discord bot integration
This mod can send notifications to a Discord channel using a bot token (not webhooks).

Status: Working with the required Gateway Intents enabled in the Discord Developer Portal.

1. Create a bot in the Discord Developer Portal and add it to your server.
2. **Enable Privileged Gateway Intents** in Bot settings:
  - **MESSAGE CONTENT INTENT** (required)
  - **GUILD MESSAGES** (required)
3. Give it permissions to Send Messages, Embed Links, and View Channel in the target channel.
4. Configure the Discord section in app/run/config/playeranalytics-common.toml:

```toml
[playeranalytics.discord]
  enabled = true
  botToken = "YOUR_BOT_TOKEN"
  channelId = "YOUR_CHANNEL_ID"
  guildId = "YOUR_GUILD_ID"
  bridgeChat = true
  notifyJoins = true
  notifyLeaves = true
  notifyKills = false
  notifyDeaths = false
  notifyMilestones = true
  notifyStats = false
```

**Features**:
- Event notifications (joins, leaves, kills, deaths, milestones)
- **Bidirectional chat bridge**: Minecraft chat ↔ Discord messages
  - In-game messages forwarded to Discord
  - Discord messages sent to Minecraft chat
  - Set `bridgeChat = true` to enable

Notes:
- The bot uses JDA and loads runtime libraries from run/libs during dev.
- **IMPORTANT**: MESSAGE CONTENT INTENT must be enabled in Discord Developer Portal for the bot to work
- If messages still do not appear, verify:
  - Gateway intents are enabled in Developer Portal
  - Bot has Send Messages permission in the channel
  - channelId and guildId are correct
  - Bot is online (green status in Discord)

## Dev runtime outputs
When running the dev client, Forge writes runtime files under app/run/. This is ignored by git.
