package de.gost0r.pickupbot.discord;

import java.util.List;

public interface DiscordUser {

    String getMentionString();

    void sendPrivateMessage(String message);

    void sendPrivateMessage(String message, DiscordEmbed embed, List<DiscordComponent> components);

    String getAvatarUrl();

    String getId();

    String getUsername();
}
