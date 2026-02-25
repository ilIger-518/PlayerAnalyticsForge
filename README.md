# Playeranalytics (Forge 1.20.1)

Player analytics mod for Minecraft Forge 1.20.1.

## Server-side only
This mod is intended to run on the server only. Players do not need to install it on their client to join the server.

If a client does install it, nothing special happens on the client: the mod loads but does not add any client-only features.

## What it stores
Join/leave events are recorded to a local SQLite database.

- Location: config/playeranalytics.sqlite
- Table: player_sessions

## Dev runtime outputs
When running the dev client, Forge writes runtime files under app/run/. This is ignored by git.
