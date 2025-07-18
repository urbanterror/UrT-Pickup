package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;

import java.util.ArrayList;
import java.util.List;

public class DiscordInteraction implements InteractionRespond {
    public String id;
    public String token;
    public DiscordUser user;
    public String componentId;
    public DiscordMessage message;
    public List<String> values;

    public DiscordInteraction(String id, String token, String componentId, DiscordUser user, DiscordMessage message, List<String> values) {
        this.id = id;
        this.token = token;
        this.user = user;
        this.componentId = componentId;
        this.message = message;
        this.values = values;
    }

    public void respond(String content) {
        DiscordAPI.interactionRespond(id, token, content, null, null);
    }

    public void respond(String content, DiscordEmbed embed) {
        DiscordAPI.interactionRespond(id, token, content, embed, null);
    }

    public void respond(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components) {
        DiscordAPI.interactionRespond(id, token, content, embed, components);
    }
}