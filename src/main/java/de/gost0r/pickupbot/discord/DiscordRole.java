package de.gost0r.pickupbot.discord;

public interface DiscordRole {

    String getId();

    String getName();

    String getMentionString();

    DiscordGuild getGuild();

}
