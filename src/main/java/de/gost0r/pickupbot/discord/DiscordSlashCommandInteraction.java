package de.gost0r.pickupbot.discord;

import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.util.List;

public interface DiscordSlashCommandInteraction extends InteractionRespond {
    
    String getName();

    DiscordUser getUser();

    List<OptionMapping> getOptions();
}