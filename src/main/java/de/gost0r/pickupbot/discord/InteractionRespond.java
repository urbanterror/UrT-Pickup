package de.gost0r.pickupbot.discord;

import java.util.ArrayList;

public interface InteractionRespond {

    void deleteDeferredReply();

    void deferReply();

    void respondEphemeral(String content);

    void respondEphemeral(String content, DiscordEmbed embed);

    void respondEphemeral(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components);

}