package de.gost0r.pickupbot.discord;

import lombok.Builder;

import java.util.List;

@Builder
public record DiscordApplicationCommand(String name,
                                        String description,
                                        List<DiscordCommandOption> options,
                                        List<DiscordApplicationCommand> subcommands) {
}
