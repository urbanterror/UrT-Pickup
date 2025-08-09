package de.gost0r.pickupbot.discord;

import java.util.List;

public record DiscordCommandOption(DiscordCommandOptionType type,
                                   String name,
                                   String description,
                                   List<DiscordCommandOptionChoice> choices) {

}
