package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.util.ArrayList;
import java.util.List;

public record DiscordSlashCommandInteraction(String id,
                                             String token,
                                             String name,
                                             DiscordUser user,
                                             List<OptionMapping> options) implements InteractionRespond {

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