package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordEmoji;

public class Bet {
    public int matchid;
    public Player player;
    public String color;
    public long amount;
    public float odds;
    public boolean open;
    public boolean won;

    public static PickupLogic logic;
    public static final DiscordEmoji bronzeEmoji = new DiscordEmoji("1081604558381400064", "pugcoin_bronze");
    public static final DiscordEmoji silverEmoji = new DiscordEmoji("1081604664568578128", "pugcoin_silver");
    public static final DiscordEmoji goldEmoji = new DiscordEmoji("1081604760249053296", "pugcoin_gold");
    public static final DiscordEmoji amberEmoji = new DiscordEmoji("1081605085450219623", "pugcoin_amber");
    public static final DiscordEmoji rubyEmoji = new DiscordEmoji("1081605151598583848", "pugcoin_ruby");
    public static final DiscordEmoji pearlEmoji = new DiscordEmoji("1081605198071480451", "pugcoin_pearl");
    public static final DiscordEmoji amethystEmoji = new DiscordEmoji("1081605266535108739", "pugcoin_amethyst");
    public static final DiscordEmoji diamondEmoji = new DiscordEmoji("1081605316262772826", "pugcoin_diamond");
    public static final DiscordEmoji smaragdEmoji = new DiscordEmoji("1081605371367534672", "pugcoin_smaragd");
    public static final DiscordEmoji prismaEmoji = new DiscordEmoji("1081605422764527768", "pugcoin_prisma");

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
        DiscordEmoji emoji = getCoinEmoji(amount);
        String msg = Config.bets_place;
        msg = msg.replace(".player.", player.getDiscordUser().getMentionString());
        msg = msg.replace(".amount.", String.format("%,d", amount));
        msg = msg.replace(".emojiname.", emoji.name());
        msg = msg.replace(".emojiid.", emoji.id());

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
        DiscordEmoji emoji = getCoinEmoji(amount);
        msg = msg.replace(".player.", player.getDiscordUser().getMentionString());
        msg = msg.replace(".amount.", String.format("%,d", amount));
        msg = msg.replace(".emojiname.", emoji.name());
        msg = msg.replace(".emojiid.", emoji.id());
        logic.bot.sendMsg(match.threadChannels, msg);
    }

    public static DiscordEmoji getCoinEmoji(long amount) {
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
