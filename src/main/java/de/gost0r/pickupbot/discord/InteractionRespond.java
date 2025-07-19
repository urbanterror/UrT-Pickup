package de.gost0r.pickupbot.discord;

import java.util.ArrayList;

public interface InteractionRespond {

    void respond(String content);

    void respond(String content, DiscordEmbed embed);

    void respond(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components);

}