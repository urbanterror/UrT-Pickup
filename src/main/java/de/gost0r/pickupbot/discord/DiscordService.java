package de.gost0r.pickupbot.discord;

import java.util.List;

public interface DiscordService {

    DiscordUser getMe();

    DiscordUser getUserFromMention(String mention);

    DiscordUser getUserById(String userId);

    DiscordRole getRoleFromMention(String mention);

    DiscordRole getRoleById(String roleId);

    DiscordChannel getChannelFromMention(String mention);

    DiscordChannel getChannelById(String channelId);

    void registerApplicationCommands(List<DiscordApplicationCommand> commands);

    void createOrUpdateApplicationCommand(DiscordApplicationCommand command);
}
