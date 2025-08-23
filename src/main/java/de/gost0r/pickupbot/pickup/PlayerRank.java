package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordEmoji;
import lombok.Getter;

public enum PlayerRank {
    LEET(new DiscordEmoji("415516710708445185", "pickup_diamond"), "525240137538469904"),
    DIAMOND(new DiscordEmoji("415516710708445185", "pickup_diamond"), "933030919198085121"),
    PLATINUM(new DiscordEmoji("415517181674258432", "pickup_platinium"), "934854051911327815"),
    GOLD(new DiscordEmoji("415517181783179264", "pickup_gold"), "934856926020374528"),
    SILVER(new DiscordEmoji("415517181481189387", "pickup_silver"), "934856641810153522"),
    BRONZE(new DiscordEmoji("415517181489709058", "pickup_bronze"), "934856813743071295"),
    WOOD(new DiscordEmoji("415517181137387520", "pickup_wood"), "934858171409895495");

    PlayerRank(DiscordEmoji emoji, String roleId) {
        this.emoji = emoji;
        this.roleId = roleId;
    }

    @Getter
    private final DiscordEmoji emoji;
    @Getter
    private final String roleId;

    public static PlayerRank getRankByElo(int elo) {
        return switch (Integer.valueOf(elo)) {
            case Integer i when i >= 1600 -> PlayerRank.DIAMOND;
            case Integer i when i >= 1400 -> PlayerRank.PLATINUM;
            case Integer i when i >= 1200 -> PlayerRank.GOLD;
            case Integer i when i >= 1000 -> PlayerRank.SILVER;
            case Integer i when i >= 800 -> PlayerRank.BRONZE;
            default -> PlayerRank.WOOD;
        };
    }
}
