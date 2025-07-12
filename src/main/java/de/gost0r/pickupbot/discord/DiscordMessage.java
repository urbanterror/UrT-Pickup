package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;

public class DiscordMessage {

    public String id;
    public DiscordUser user;
    public DiscordChannel channel;
    public String content;

    public DiscordMessage(String id, DiscordUser user, DiscordChannel channel, String content) {
        this.id = id;
        this.user = user;
        this.channel = channel;
        this.content = content;
    }

    public void edit(String content, DiscordEmbed embed) {
        DiscordAPI.editMessage(this, content, embed);
        this.content = content;
    }

    public void delete() {
        DiscordAPI.deleteMessage(channel, id);
    }
}
