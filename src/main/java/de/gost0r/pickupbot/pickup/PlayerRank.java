package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordRole;
import org.json.JSONObject;

public enum PlayerRank {
    LEET("<:pickup_diamond:415516710708445185>", "525240137538469904"),
    DIAMOND("<:pickup_diamond:415516710708445185>", "933030919198085121"),
    PLATINUM("<:pickup_platinium:415517181674258432>", "934854051911327815"),
    GOLD("<:pickup_gold:415517181783179264>", "934856926020374528"),
    SILVER("<:pickup_silver:415517181481189387>", "934856641810153522"),
    BRONZE("<:pickup_bronze:415517181489709058>", "934856813743071295"),
    WOOD("<:pickup_wood:415517181137387520>", "934858171409895495");

    PlayerRank(String emoji, String roleId) {
        this.emoji = emoji;
        this.roleId = roleId;
    }

    private String emoji;
    private String roleId;

    public String getEmoji() {
        return emoji;
    }

    public DiscordRole getRole() {
        return DiscordRole.getRole(roleId);
    }

    public JSONObject getEmojiJSON() {
        JSONObject emojiJSON = new JSONObject();
        String name = emoji.split(":")[1];
        String id = emoji.split(":")[2].replaceAll(">", "");
        emojiJSON.put("name", name);
        emojiJSON.put("id", id);

        return emojiJSON;
    }
}
