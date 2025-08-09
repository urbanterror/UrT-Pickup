package de.gost0r.pickupbot.discord;

import lombok.Data;

@Data
public class DiscordComponent {
    private DiscordComponentType type;
    private String customId;
    private boolean disabled;
}



