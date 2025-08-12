package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
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
        User user = jda.getUserById(userId);
        if (user == null) {
            try { user = jda.retrieveUserById(userId).complete(); }
            catch (Exception ignored) { return null; }
        }
        return new JdaDiscordUser(user);
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

    public void registerApplicationCommands(List<DiscordApplicationCommand> commands) {
        jda.updateCommands().addCommands(commands.stream().map(this::toCommandData).toList()).queue();
    }

    private SlashCommandData toCommandData(DiscordApplicationCommand command) {
        return Commands.slash(command.name(), command.description()).addOptions(command.options().stream().map(JdaUtils::mapToCommandOption).toList());
    }
}
