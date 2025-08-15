package de.gost0r.pickupbot.discord;

import java.util.List;

public interface DiscordChannel {

    String getId();

    String getName();

    String getParentId();

    String getGuildId();

    boolean isThreadChannel();

    boolean isPrivateChannel();

    void sendMessage(String message);

    void sendMessage(String message, DiscordEmbed embed);

    DiscordMessage sendMessage(String message, DiscordEmbed embed, List<DiscordComponent> component);

    DiscordChannel createThread(String name, boolean autoArchive);

    String getMentionString();

    void archive();

    void delete();
}
