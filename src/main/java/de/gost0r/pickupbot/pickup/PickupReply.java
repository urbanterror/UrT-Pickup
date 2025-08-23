package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordMessage;

import java.util.List;


public class PickupReply {

    private final String message;
    private final DiscordEmbed embed;
    private final List<DiscordComponent> components;

    public PickupReply(String message) {
        this.message = message;
        embed = null;
        components = null;
    }

    public PickupReply(String message, DiscordEmbed embed) {
        this.message = message;
        this.embed = embed;
        this.components = null;
    }

    public PickupReply(String message, DiscordEmbed embed, List<DiscordComponent> components) {
        this.message = message;
        this.embed = embed;
        this.components = components;
    }

    static final PickupReply NONE = new PickupReply(null);

    public void replyTo(DiscordMessage message) {
        if (message != null) {
            if (this.embed != null && this.components != null) {
                message.reply(this.message, this.embed, this.components);
                return;
            }
            if (this.embed != null) {
                message.reply(this.message, this.embed);
                return;
            }
            if (this.message != null) {
                message.reply(this.message);
            }
        }
    }
}
