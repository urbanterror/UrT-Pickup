package de.gost0r.pickupbot.discord;

public record DiscordEmoji(String id, String name) {
    public String getMentionString() {
        return "<:" + name + ":" + id + ">";
    }
}
