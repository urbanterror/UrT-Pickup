package de.gost0r.pickupbot.pickup;

import org.json.JSONObject;

public class Bet {
    public int matchid;
    public Player player;
    public String color;
    public long amount;
    public float odds;
    public boolean open;
    public boolean won;

    public static PickupLogic logic;
    public static JSONObject bronzeEmoji = new JSONObject().put("name", "pugcoin_bronze").put("id", "1081604558381400064");
    public static JSONObject silverEmoji = new JSONObject().put("name", "pugcoin_silver").put("id", "1081604664568578128");
    public static JSONObject goldEmoji = new JSONObject().put("name", "pugcoin_gold").put("id", "1081604760249053296");
    public static JSONObject amberEmoji = new JSONObject().put("name", "pugcoin_amber").put("id", "1081605085450219623");
    public static JSONObject rubyEmoji = new JSONObject().put("name", "pugcoin_ruby").put("id", "1081605151598583848");
    public static JSONObject pearlEmoji = new JSONObject().put("name", "pugcoin_pearl").put("id", "1081605198071480451");
    public static JSONObject amethystEmoji = new JSONObject().put("name", "pugcoin_amethyst").put("id", "1081605266535108739");
    public static JSONObject diamondEmoji = new JSONObject().put("name", "pugcoin_diamond").put("id", "1081605316262772826");
    public static JSONObject smaragdEmoji = new JSONObject().put("name", "pugcoin_smaragd").put("id", "1081605371367534672");
    public static JSONObject prismaEmoji = new JSONObject().put("name", "pugcoin_prisma").put("id", "1081605422764527768");

    public Bet(int matchid, Player p, String color, long amount, float odds) {
        this.matchid = matchid;
        this.player = p;
        this.color = color;
        this.amount = amount;
        this.odds = odds;
        this.open = true;
    }

    public void place(Match match) {
        boolean allIn = amount == player.getCoins();
        player.spendCoins(amount);
        JSONObject emoji = getCoinEmoji(amount);
        String msg = Config.bets_place;
        msg = msg.replace(".player.", player.getDiscordUser().getMentionString());
        msg = msg.replace(".amount.", String.format("%,d", amount));
        msg = msg.replace(".emojiname.", emoji.getString("name"));
        msg = msg.replace(".emojiid.", emoji.getString("id"));

        if (allIn) {
            msg += " **ALL IN!!**";
            logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), msg);
        }
        logic.bot.sendMsg(match.threadChannels, msg);
    }

    public void enterResult(boolean result) {
        won = result;
        open = false;

        if (won) {
            int wonAmount = Math.round(amount * odds);
            player.addCoins(wonAmount);
        }
        player.saveWallet();
        logic.db.createBet(this);
    }

    public void refund(Match match) {
        player.addCoins(amount);
        open = false;
        String msg = Config.bets_refund;
        JSONObject emoji = getCoinEmoji(amount);
        msg = msg.replace(".player.", player.getDiscordUser().getMentionString());
        msg = msg.replace(".amount.", String.format("%,d", amount));
        msg = msg.replace(".emojiname.", emoji.getString("name"));
        msg = msg.replace(".emojiid.", emoji.getString("id"));
        logic.bot.sendMsg(match.threadChannels, msg);
    }

    public static JSONObject getCoinEmoji(long amount) {
        if (amount < 500) {
            return bronzeEmoji;
        } else if (amount < 1000) {
            return silverEmoji;
        } else if (amount < 10000) {
            return goldEmoji;
        } else if (amount < 25000) {
            return amberEmoji;
        } else if (amount < 50000) {
            return rubyEmoji;
        } else if (amount < 100000) {
            return pearlEmoji;
        } else if (amount < 250000) {
            return amethystEmoji;
        } else if (amount < 500000) {
            return diamondEmoji;
        } else if (amount < 1000000) {
            return smaragdEmoji;
        }
        return prismaEmoji;
    }
}
