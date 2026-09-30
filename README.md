[![urtpickup](https://i.imgur.com/f9DaZDT.png)](https://discord.gg/An8hxdM)

# UrT-Pickup
[![Discord](https://img.shields.io/discord/117622053061787657)](https://discord.gg/An8hxdM)

This repo has been archived. To contribute to further development, please check out [urbanterror/UrT-Pickup](https://github.com/urbanterror/UrT-Pickup)

## About
This is a network of Urban Terror servers associating players through [Discord](https://discord.gg/An8hxdM) thanks to a simple java bot.

From this discord server you can:
- play competitive matchmaking in any team-based game type,
- challenge other users in individual modes such as GunGame, LMS or KnockOut matches :resurgence:,
- improve your skills with the SkeetShoot and AimTraining modes both with record tracking :resurgence:,
- request a game server for a few hours for your personal use.


## Live-game previews

The bot automatically creates a **`live-games-N`** text channel in each guild with a
configured PUBLIC pickup channel. `N` is the number of public matches in the Live
state (private matches and matches awaiting a server are excluded). Each match has
a preview with teams, map, game status and score; click its title to open the
existing live scoreboard when available. Finished previews are removed, and the
channel is deleted when no public live matches remain.

- Previews refresh approximately every **30 seconds**, slowing down as match count
  or Discord traffic increases. Unchanged previews do not cause edits.
- Channel names update at most once every **10 minutes**, because Discord applies
  particularly restrictive limits to renames. The count in the name can lag; the
  previews continue updating. Rename deadlines survive restarts.
- The channel denies sending messages, creating/sending in threads, application
  commands and adding reactions to everyone except the bot. Conflicting role/member
  sending overrides are repaired. Discord administrators can bypass these denies.
- The bot needs **Manage Channels**, **Manage Permissions**, **View Channel**,
  **Send Messages**, **Embed Links** and **Read Message History**. Missing permissions
  produce a log warning and, when possible, a warning in the configured ADMIN
  channel (at most hourly per guild).
- Restarts rediscover owned channels by their topic marker and recover preview
  messages from history. Keep the bot-managed topic intact.

Set `DISCORD_LIVE_GAMES_ENABLED=false` to disable channel management (existing
channels remain). `DISCORD_LIVE_STATE_DIRECTORY` defaults to `./data/discord-live`;
mount that directory on persistent storage, separately for each bot instance.

### Discord request metrics

All Discord REST attempts made by JDA, including retries, are counted. JDA still
enforces Discord's route/global limits. Live-channel work adds a conservative
one-operation-per-two-seconds budget, pauses above 600 requests in the trailing
minute, and honors observed `Retry-After` / `X-RateLimit-Reset-After` feedback.
There is only one live-channel worker, so slow REST calls cannot accumulate a
refresh queue or block pickup commands.

Counters are restored from `state.properties` and atomically checkpointed every
five seconds and on shutdown (an abrupt crash can lose up to five seconds of
counter increments). `discord.prom` in the same directory is a periodically updated
Prometheus textfile that can be polled directly or scraped through the Prometheus
node exporter's textfile collector. It includes:

- `urt_discord_requests_total`, `urt_discord_rate_limited_total`,
  `urt_discord_errors_total` and `urt_discord_network_errors_total`
- `urt_discord_live_operations_total`, `urt_discord_live_deferred_total`,
  `urt_discord_live_errors_total`
- `urt_discord_requests_last_minute`, `urt_discord_live_refresh_interval_seconds`,
  `urt_discord_live_blocked_until_seconds`, `urt_discord_metrics_written_timestamp_seconds`

For example, `rate(urt_discord_requests_total[5m])` shows requests/second and
`increase(urt_discord_rate_limited_total[15m])` shows recent HTTP 429s. Cumulative
counters alone cannot predict Discord's available quota: the refresh policy also
uses the recent request window and actual response headers.

## GlitchTip error reporting

Sentry Java SDK reporting is initialized before Spring starts. Set `SENTRY_DSN` in
the deployment environment to send events to your GlitchTip project. Reporting is
disabled when no DSN is supplied; the repository and built JAR contain no DSN.
All **WARN and ERROR** logs become events, including handled database, FTW API,
Discord, match and server-monitor exceptions. INFO logs are attached as breadcrumbs.
Uncaught thread exceptions and startup failures are also captured. Error events
are not sampled; the 1% tracing rate applies only to transactions, when created.
The SDK flushes pending events on normal JVM shutdown.

Exception grouping uses the deepest cause's type, throwing method and nearest
application method, excluding changing messages, IDs, wrapper exceptions and line
numbers. Different exception types or originating methods remain separate. For
message-only logs, grouping uses the logger and unformatted message template.
Full messages, parameters and stack traces remain visible on individual events.

Defaults are packaged in `src/main/resources/sentry.properties`. Override them with:

- `SENTRY_DSN`: the GlitchTip DSN; an empty value disables reporting.
- `SENTRY_RELEASE`: the deployed application version or commit.
- `SENTRY_ENVIRONMENT`: the environment (default `production`).
- `SENTRY_TRACES_SAMPLE_RATE`: transaction sampling rate (default `0.01`).

You can also use a working-directory `sentry.properties` file or `-Dsentry.*`
system properties. Automated tests disable reporting to avoid sending test failures.

The local s89 deployment uses an ignored, mode-600 `.sentry.env` file containing
`SENTRY_DSN`. `deploy-local.sh` copies it to `/home/shawn/PickupDiscord/.sentry.env`
and recreates the bot container with `--env-file` so the Java process receives the
variable. Set `SENTRY_ENV_FILE` to use another local file. If no local file exists,
the script uses the deployment's existing remote `.sentry.env`.

Verify delivery without starting the Discord bot or opening the database:

```bash
./gradlew bootJar
SENTRY_DSN='<your GlitchTip DSN>' SENTRY_ENVIRONMENT=integration-test java -jar build/libs/PickupBot.jar --sentry-test
```

This sends one `Test GlitchTip warning!` and two errors with different match IDs,
prints the error event IDs and flushes before exiting. Check that the two errors
appear in one GlitchTip issue, separate from the warning.

## Commands

### User Commands
- !add <gametype>
- !remove <gametype>
- !maps displays the map list for each gametype.
- !map <gametype> <mapname>
- !status to get information on the queues.
- !help <command>
- !surrender to abandon your match.
- !live sends info on the live matches.
- !pick <1/2>
- !votes to get the current votes.
- !register <urtauth>
- !country <COUNTRY CODE> See:` <https://datahub.io/core/country-list/r/0.html>
- !elo </@DiscordUser|urtauth/>
- !stats </@DiscordUser|urtauth/>
- !top10 displays the top 10 players
- !topcountries ordered by average ELO
- !topwin: players with the best win ratio
- !topkdr: players with the best KDR
- !topban: top 10 auths by all-time ban count, with active ban counts in parentheses
- !match <id>
- !last </@DiscordUser|urtauth/>


### Admin Commands

- !lock to prevent commands from PUBLIC channel.
- !unlock
- !reset <all/cur/type/id>
- !reboot
- !getdata <match id>
- !enablemap <ut4_map> <gametype>
- !disablemap <ut4_map> <gametype>
- !rcon <serverid> <rconstring>
- !forceadd <gamemode> </@DiscordUser|urtauth/>
- !enablegametype <name> <teamsize>
- !disablegametype <name>
- !showgameconfig <gametype>
- !ban <urtauth> <reason> <duration> (duration=1y1M1w1d1h1m1s)
- !unban <urtauth>
- !baninfo </@DiscordUser|urtauth/>
- !showservers
- !addserver <ip:port> <rcon> <region>
- !enableserver <id>
- !disableserver <id>
- !updateserver <id> <rcon>
- !showmatches displays the queues AND live matches
- !unregister <urtauth>
- !country <urtauth> <COUNTRY CODE>
- !addchannel <#name> <public/admin>
- !removechannel <#name> <public/admin>
- !addrole <@role> <admin/superadmin>
- !removerole <@role> <admin/superadmin>


[![love](https://forthebadge.com/images/badges/built-with-love.svg)](https://forthebadge.com) [![java](https://forthebadge.com/images/badges/made-with-java.svg)](https://forthebadge.com) [![forthebadge](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://forthebadge.com) 
