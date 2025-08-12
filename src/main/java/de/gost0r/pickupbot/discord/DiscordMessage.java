package de.gost0r.pickupbot.discord;

public interface DiscordMessage {

    String getId();

    DiscordUser getUser();

    DiscordChannel getChannel();

    String getContent();

    void edit(DiscordEmbed embed);

    void delete();
}
