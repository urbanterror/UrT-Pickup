package de.gost0r.pickupbot.discord;

import java.util.List;

public record DiscordApplicationCommand(String name, String description, List<DiscordCommandOption> options) {
}
