package de.gost0r.pickupbot.discord;

import java.util.List;

public interface DiscordInteraction extends InteractionRespond {
    String getId();

    String getToken();

    DiscordUser getUser();

    String getComponentId();

    DiscordMessage getMessage();

    List<String> getValues();
}