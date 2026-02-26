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

1. Create a bot in the Discord Developer Portal and add it to your server.
2. Give it permissions to Send Messages and Embed Links in the target channel.
3. Configure the Discord section in app/run/config/playeranalytics-common.toml:

```toml
[playeranalytics.discord]
	enabled = true
	botToken = "YOUR_BOT_TOKEN"
	channelId = "YOUR_CHANNEL_ID"
	guildId = "YOUR_GUILD_ID"
	notifyJoins = true
	notifyLeaves = true
	notifyKills = false
	notifyDeaths = false
	notifyMilestones = true
	notifyStats = false
```

Notes:
- The bot uses JDA and loads runtime libraries from run/libs during dev.
- If messages do not appear, re-check channel permissions and IDs.
- Known issue: JDA beta may log NoSuchMethodException for EmbedBuilder.setTimestamp; embeds will not send until fixed.

## Dev runtime outputs
When running the dev client, Forge writes runtime files under app/run/. This is ignored by git.
