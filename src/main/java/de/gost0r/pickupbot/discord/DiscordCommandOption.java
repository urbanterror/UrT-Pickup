package de.gost0r.pickupbot.discord;

import lombok.Builder;

import java.util.List;

@Builder
public record DiscordCommandOption(DiscordCommandOptionType type,
                                   String name,
                                   String description,
                                   boolean required,
                                   List<DiscordCommandOptionChoice> choices) {

}
