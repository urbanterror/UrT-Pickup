package de.gost0r.pickupbot.discord;

import java.util.List;

public interface DiscordMessage {

    String getId();

    DiscordUser getUser();

    DiscordChannel getChannel();

    String getContent();

    List<DiscordUser> getMentionedUser();

    void edit(DiscordEmbed embed);

    void delete();
}
