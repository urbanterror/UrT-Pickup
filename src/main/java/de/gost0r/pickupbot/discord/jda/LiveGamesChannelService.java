package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.pickup.Match;
import de.gost0r.pickupbot.pickup.PickupBot;
import de.gost0r.pickupbot.pickup.PickupChannelType;
import de.gost0r.pickupbot.pickup.PickupLogic;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One low-priority REST operation per pass. No refresh backlog can build up in JDA. */
@Slf4j
@Service
public class LiveGamesChannelService {
    static final String PREVIEW_MARKER = "urt-pickup-live:";
    static final long WRITE_PERMISSIONS = Permission.getRaw(Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS,
            Permission.CREATE_PRIVATE_THREADS, Permission.USE_APPLICATION_COMMANDS,
            Permission.MESSAGE_ADD_REACTION);
    static final long BOT_PERMISSIONS = Permission.getRaw(Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY);
    private final JDA jda;
    private final PickupBot bot;
    private final DiscordRequestBudget budget;
    private final boolean enabled;
    private final Clock clock;
    private final Map<String, ChannelState> channels = new HashMap<>();
    private final Map<String, Long> warnings = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker;
    private int guildCursor;

    @Autowired
    public LiveGamesChannelService(JDA jda, PickupBot bot, DiscordRequestBudget budget,
                                  @Value("${app.discord.live-games.enabled:true}") boolean enabled) {
        this(jda, bot, budget, enabled, Clock.systemUTC(), Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "live-games-channel");
            thread.setDaemon(true);
            return thread;
        }));
    }

    LiveGamesChannelService(JDA jda, PickupBot bot, DiscordRequestBudget budget,
                            boolean enabled, Clock clock, ExecutorService worker) {
        this.jda = jda;
        this.bot = bot;
        this.budget = budget;
        this.enabled = enabled;
        this.clock = clock;
        this.worker = worker;
    }

    @Scheduled(fixedDelay = 2_000)
    public void tick() {
        if (!enabled || bot.getLogic() == null || jda.getStatus() != JDA.Status.CONNECTED
                || worker.isShutdown() || !running.compareAndSet(false, true)) return;
        try {
            worker.execute(this::refresh);
        } catch (RejectedExecutionException e) {
            running.set(false);
            if (!worker.isShutdown()) log.warn("Unable to schedule live-games refresh", e);
        }
    }

    private void refresh() {
        try {
            PickupLogic logic = bot.getLogic();
            List<String> guildIds = logic.getChannelByType(PickupChannelType.PUBLIC).stream()
                    .map(DiscordChannel::getGuildId).filter(Objects::nonNull).distinct().sorted().toList();
            if (guildIds.isEmpty()) return;
            List<Match> matches = logic.getPublicLiveMatches();
            long interval = budget.refreshMillis(matches.size() * guildIds.size());
            // Rotate guilds so a busy guild cannot starve the others.
            int start = Math.floorMod(guildCursor++, guildIds.size());
            for (int i = 0; i < guildIds.size(); i++) {
                Guild guild = jda.getGuildById(guildIds.get((start + i) % guildIds.size()));
                if (guild != null) {
                    try {
                        reconcile(logic, guild, matches, interval);
                    } catch (Exception e) {
                        // A timed-out send might still have succeeded. Recover history before sending again.
                        channels.remove(guild.getId());
                        budget.failedOperation();
                        warn(logic, guild, "Live-games refresh failed; check the bot's channel permissions. Details: " + e.getMessage());
                        log.warn("Live-games refresh failed in guild {}", guild.getId(), e);
                    }
                }
            }
        } catch (Exception e) {
            budget.failedOperation();
            log.warn("Live-games channel refresh failed; retrying with backoff", e);
        } finally {
            running.set(false);
        }
    }

    void reconcile(PickupLogic logic, Guild guild, List<Match> matches, long interval) throws IOException {
        String marker = "urt-pickup:live-games:" + jda.getSelfUser().getId() + ":" + guild.getId();
        ChannelState state = channels.get(guild.getId());
        if (state != null && guild.getTextChannelById(state.channel.getId()) == null
                && clock.millis() - state.createdAt > 30_000) {
            channels.remove(guild.getId());
            state = null;
        }
        if (state == null) {
            TextChannel existing = guild.getTextChannels().stream()
                    .filter(channel -> marker.equals(channel.getTopic())).findFirst().orElse(null);
            if (existing != null) {
                state = new ChannelState(existing, false, clock.millis());
                channels.put(guild.getId(), state);
            }
        }
        if (state == null && matches.isEmpty()) return;

        var self = guild.getSelfMember();
        boolean canManage = state == null
                ? self.hasPermission(Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS)
                : self.hasPermission(state.channel, Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
        if (!canManage) {
            warn(logic, guild, "Live-games channel needs Manage Channels and Manage Permissions. "
                    + "The bot cannot create/manage a read-only channel with its current permissions.");
            return;
        }

        if (matches.isEmpty()) {
            if (budget.tryAcquire()) {
                state.channel.delete().reason("No public live matches").complete();
                channels.remove(guild.getId());
            }
            return;
        }
        if (state == null) {
            if (!self.hasPermission(Permission.getPermissions(BOT_PERMISSIONS))) {
                warn(logic, guild, "Live-games channel needs View Channel, Send Messages, Embed Links and Read Message History.");
                return;
            }
            if (budget.tryAcquire()) {
                budget.reserveRename(guild.getId());
                TextChannel channel = guild.createTextChannel(channelName(matches.size()))
                        .setTopic(marker)
                        .addPermissionOverride(guild.getPublicRole(), Permission.VIEW_CHANNEL.getRawValue(), WRITE_PERMISSIONS)
                        .addPermissionOverride(self, BOT_PERMISSIONS, 0)
                        .reason("Read-only live match previews").complete();
                channels.put(guild.getId(), new ChannelState(channel, true, clock.millis()));
            }
            return;
        }

        TextChannel channel = state.channel;
        if (repairPermissions(channel)) return;
        if (!self.hasPermission(channel, Permission.getPermissions(BOT_PERMISSIONS))) {
            warn(logic, guild, "Cannot publish live previews: check View Channel, Send Messages, Embed Links and Read Message History.");
            return;
        }
        if (!state.recovered) {
            if (budget.tryAcquire()) recoverPage(state);
            return;
        }

        // Finished matches and duplicate messages recovered after an uncertain send are removed first.
        var ids = matches.stream().map(Match::getID).toList();
        for (var entry : new ArrayList<>(state.previews.entrySet())) {
            if (!ids.contains(entry.getValue().matchId) && !state.obsolete.contains(entry.getKey())) {
                state.obsolete.add(entry.getKey());
            }
        }
        if (!state.obsolete.isEmpty()) {
            if (budget.tryAcquire()) {
                String id = state.obsolete.getFirst();
                try {
                    channel.deleteMessageById(id).complete();
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw e;
                }
                state.obsolete.removeIf(id::equals);
                state.previews.remove(id);
            }
            return;
        }

        String name = channelName(matches.size());
        if (!channel.getName().equals(name) && budget.canRename(guild.getId())) {
            if (budget.tryAcquire()) {
                budget.reserveRename(guild.getId());
                channel.getManager().setName(name).complete();
            }
            return;
        }

        ChannelState current = state;
        List<Match> ordered = matches.stream().sorted(Comparator.comparingLong(match ->
                current.previews.values().stream().filter(p -> p.matchId == match.getID())
                        .mapToLong(p -> p.checkedAt).findFirst().orElse(0))).toList();
        for (Match match : ordered) {
            var entry = state.previews.entrySet().stream().filter(e -> e.getValue().matchId == match.getID()).findFirst();
            Preview preview = entry.map(Map.Entry::getValue).orElse(null);
            long now = clock.millis();
            if (preview != null && now - preview.checkedAt < interval) continue;
            MessageEmbed embed = preview(match, guild.getId());
            if (preview != null && embed.equals(preview.embed)) {
                preview.checkedAt = now;
                continue;
            }
            if (!budget.tryAcquire()) return;
            if (preview == null) {
                Message message = channel.sendMessageEmbeds(embed).setAllowedMentions(List.of()).complete();
                state.previews.put(message.getId(), new Preview(match.getID(), embed, now));
            } else {
                try {
                    channel.editMessageEmbedsById(entry.orElseThrow().getKey(), embed).complete();
                    preview.embed = embed;
                    preview.checkedAt = now;
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw e;
                    state.previews.remove(entry.orElseThrow().getKey());
                }
            }
            return;
        }
    }

    /** New channels have no category inheritance; also remove any later conflicting role/member allows. */
    boolean repairPermissions(TextChannel channel) {
        var manager = channel.getManager();
        boolean changed = false;
        long self = channel.getGuild().getSelfMember().getIdLong();
        long everyone = channel.getGuild().getPublicRole().getIdLong();
        boolean foundEveryone = false;
        boolean foundSelf = false;
        for (var override : channel.getPermissionOverrides()) {
            long allow = override.getAllowedRaw();
            long deny = override.getDeniedRaw();
            if (override.isMemberOverride() && override.getIdLong() == self) {
                foundSelf = true;
                allow |= BOT_PERMISSIONS;
                deny &= ~BOT_PERMISSIONS;
            } else {
                allow &= ~WRITE_PERMISSIONS;
                if (override.getIdLong() == everyone) {
                    foundEveryone = true;
                    deny |= WRITE_PERMISSIONS;
                }
            }
            if (allow != override.getAllowedRaw() || deny != override.getDeniedRaw()) {
                if (override.isMemberOverride()) manager.putMemberPermissionOverride(override.getIdLong(), allow, deny);
                else manager.putRolePermissionOverride(override.getIdLong(), allow, deny);
                changed = true;
            }
        }
        if (!foundEveryone) {
            manager.putRolePermissionOverride(everyone, Permission.VIEW_CHANNEL.getRawValue(), WRITE_PERMISSIONS);
            changed = true;
        }
        if (!foundSelf) {
            manager.putMemberPermissionOverride(self, BOT_PERMISSIONS, 0);
            changed = true;
        }
        if (changed && budget.tryAcquire()) manager.complete();
        else manager.reset();
        return changed;
    }

    private void recoverPage(ChannelState state) {
        List<Message> messages = state.before == null
                ? state.channel.getHistory().retrievePast(100).complete()
                : state.channel.getHistoryBefore(state.before, 100).complete().getRetrievedHistory();
        for (Message message : messages) {
            if (!message.getAuthor().getId().equals(jda.getSelfUser().getId()) || message.getEmbeds().isEmpty()) continue;
            MessageEmbed embed = message.getEmbeds().getFirst();
            String footer = embed.getFooter() == null ? null : embed.getFooter().getText();
            if (footer == null || !footer.startsWith(PREVIEW_MARKER)) continue;
            try {
                int matchId = Integer.parseInt(footer.substring(PREVIEW_MARKER.length()));
                if (state.previews.values().stream().anyMatch(p -> p.matchId == matchId)) state.obsolete.add(message.getId());
                else state.previews.put(message.getId(), new Preview(matchId, embed, 0));
            } catch (NumberFormatException ignored) {
                // A message without our exact marker is not managed by this service.
            }
        }
        state.recovered = messages.size() < 100;
        if (!messages.isEmpty()) state.before = messages.getLast().getId();
    }

    static String channelName(int count) { return "live-games-" + count; }

    static MessageEmbed preview(Match match, String guildId) {
        String description = match.getMatchInfo();
        String link = match.liveScoreMsgs.stream()
                .filter(message -> guildId.equals(message.getChannel().getGuildId()))
                .map(message -> "https://discord.com/channels/" + guildId + "/" + message.getChannel().getId() + "/" + message.getId())
                .findFirst().orElse(null);
        return new EmbedBuilder().setTitle("Live match #" + match.getID(), link)
                .setDescription(description.length() > 4096 ? description.substring(0, 4093) + "..." : description)
                .setColor(7056881).setFooter(PREVIEW_MARKER + match.getID()).build();
    }

    private void warn(PickupLogic logic, Guild guild, String message) {
        long now = clock.millis();
        if (now < warnings.getOrDefault(guild.getId(), 0L)) return;
        warnings.put(guild.getId(), now + 3_600_000);
        log.warn("Guild {}: {}", guild.getId(), message);
        for (DiscordChannel admin : logic.getChannelByType(PickupChannelType.ADMIN)) {
            TextChannel target = jda.getTextChannelById(admin.getId());
            if (target != null && guild.getId().equals(target.getGuild().getId())
                    && guild.getSelfMember().hasPermission(target, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
                    && budget.tryAcquire()) {
                target.sendMessage("⚠ " + message).setAllowedMentions(List.of()).complete();
                break;
            }
        }
    }

    @PreDestroy
    public void shutdown() { worker.shutdownNow(); }

    private static final class ChannelState {
        final TextChannel channel;
        final long createdAt;
        final Map<String, Preview> previews = new HashMap<>();
        final List<String> obsolete = new ArrayList<>();
        boolean recovered;
        String before;

        ChannelState(TextChannel channel, boolean recovered, long createdAt) {
            this.channel = channel;
            this.recovered = recovered;
            this.createdAt = createdAt;
        }
    }

    private static final class Preview {
        final int matchId;
        MessageEmbed embed;
        long checkedAt;

        Preview(int matchId, MessageEmbed embed, long checkedAt) {
            this.matchId = matchId;
            this.embed = embed;
            this.checkedAt = checkedAt;
        }
    }
}
