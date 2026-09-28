package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;

import java.util.Locale;

public class PlayerBan {

    public enum BanReason {
        NOSHOW,
        RAGEQUIT,
        INSULT,
        TROLL,
        AFK,
        CHEAT,
        DEMO,
        FAKE,
        TK,
        RACISM;

        public static BanReason fromStorage(String reason) {
            if (reason == null) {
                return null;
            }

            try {
                return valueOf(reason.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public Player player;
    public long startTime;
    public long endTime;

    public BanReason reason;

    public DiscordUser pardon = null;

    public Boolean forgiven = false;

}
