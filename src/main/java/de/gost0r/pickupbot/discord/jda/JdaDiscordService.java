package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class JdaDiscordService implements DiscordService {

    private final JDA jda;

    public JdaDiscordService(JDA jda) {
        this.jda = jda;
    }

    @Override
    public DiscordUser getMe() {
        return new JdaDiscordUser(jda.getSelfUser());
    }

    @Nullable
    @Override
    public DiscordUser getUserFromMention(String mention) {
        String id = mention.replaceAll("[^\\d.]", "");
        if (id.isEmpty()) {
            return null;
        }
        return getUserById(id);
    }

    @Nullable
    @Override
    public DiscordUser getUserById(String userId) {
        try {
            Guild guild = jda.getGuildById("117622053061787657");
            if (guild != null) {
                try {
                    Member member = guild.retrieveMemberById(userId).complete();
                    if (member != null) {
                        return new JdaDiscordUser(member);
                    }
                } catch (ErrorResponseException e) {
                    log.warn("Failed to retrieve member with id {} - {}", userId, e.getMessage());
                }
            }
            User user = jda.getUserById(userId);
            if (user == null) {
                user = jda.retrieveUserById(userId).complete();
            }
            return new JdaDiscordUser(user);
        } catch (Exception e) {
            log.error("Failed to retrieve user with id {}", userId, e);
            return null;
        }
    }

    @Nullable
    @Override
    public DiscordRole getRoleFromMention(String mention) {
        String id = mention.replaceAll("[^\\d.]", "");
        if (id.isEmpty()) {
            return null;
        }
        return getRoleById(id);
    }

    @Nullable
    @Override
    public DiscordRole getRoleById(String roleId) {
        Role role = jda.getRoleById(roleId);
        return role == null ? null : new JdaDiscordRole(role);
    }

    @Nullable
    @Override
    public DiscordChannel getChannelFromMention(String mention) {
        String id = mention.replaceAll("[^\\d.]", "");
        if (id.isEmpty()) {
            return null;
        }
        return getChannelById(id);
    }

    @Nullable
    @Override
    public DiscordChannel getChannelById(@NotNull String channelId) {
        TextChannel channel = jda.getTextChannelById(channelId);
        return channel == null ? null : new JdaDiscordChannel(channel);
    }

    @Override
    public void createOrUpdateApplicationCommand(DiscordApplicationCommand command) {
        jda.getGuilds().forEach(guild -> {
            guild.upsertCommand(toCommandData(command))
                    .queue(
                            cmd -> log.info("Created or updated application command {} on guild {}", cmd.getName(), guild.getName()),
                            e -> log.error("Failed to create or update application command {} on guild {}", command.name(), guild.getName(), e)
                    );
        });

    }

    @Override
    public void registerApplicationCommands(List<DiscordApplicationCommand> commands) {
        List<SlashCommandData> data = commands.stream().map(this::toCommandData).toList();
        jda.getGuilds().forEach(guild -> {
            guild.updateCommands()
                    .addCommands(data)
                    .queue(
                            unused -> log.info("Registered {} application commands on guild {}", data.size(), guild.getName()),
                            e -> log.error("Failed to register application commands on guild {}", guild.getName(), e)
                    );
        });
    }

    private SlashCommandData toCommandData(DiscordApplicationCommand command) {
        SlashCommandData slashCommandData = Commands.slash(command.name(), command.description());
        if (command.subcommands() != null) {
            slashCommandData.addSubcommands(command.subcommands().stream().map(JdaUtils::mapToSubcommand).toList());
        }
        if (command.options() != null) {
            slashCommandData.addOptions(command.options().stream().map(JdaUtils::mapToCommandOption).toList());
        }
        return slashCommandData;
    }
}
