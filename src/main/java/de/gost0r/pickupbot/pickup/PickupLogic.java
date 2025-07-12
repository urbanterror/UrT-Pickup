package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.PickupBotApplication;
import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.discord.api.DiscordAPI;
import de.gost0r.pickupbot.ftwgl.FtwglAPI;
import de.gost0r.pickupbot.pickup.PlayerBan.BanReason;
import de.gost0r.pickupbot.pickup.server.Server;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
public class PickupLogic {

    public PickupBot bot;
    public Database db;

    private List<Server> serverList;
    private List<Server> gtvServerList;
    private List<GameMap> mapList;

    private Map<PickupRoleType, List<DiscordRole>> roles;
    private Map<PickupChannelType, List<DiscordChannel>> channels;

    private List<Match> ongoingMatches; // ongoing matches (live)
    private List<Team> activeTeams;

    private Queue<Match> awaitingServer;

    private Map<Gametype, Match> curMatch;
    private Map<Team, Gametype> teamsQueued;

    private List<PrivateGroup> privateGroups;

    private boolean locked;
    private boolean dynamicServers;

    private Map<BanReason, String[]> banDuration;
    private Map<Gametype, GameMap> lastMapPlayed;

    public Season currentSeason;

    public PickupLogic(PickupBot bot) {
        this.bot = bot;

        db = new Database(this);
        Player.db = db;
        Player.logic = this;
        Bet.logic = this;

        dynamicServers = true;

        currentSeason = db.getCurrentSeason();

        serverList = db.loadServers();
        roles = db.loadRoles();
        channels = db.loadChannels();
        privateGroups = new ArrayList<PrivateGroup>();

        awaitingServer = new LinkedList<Match>();
        curMatch = new HashMap<Gametype, Match>();
        teamsQueued = new HashMap<Team, Gametype>();
        lastMapPlayed = new HashMap<Gametype, GameMap>();
        for (Gametype gt : db.loadGametypes()) {
            if (gt.getActive()) {
                curMatch.put(gt, null);
                lastMapPlayed.put(getGametypeByString(gt.getName()), new GameMap("null"));
            }
        }
        mapList = db.loadMaps(); // needs current gamemode list
        ongoingMatches = db.loadOngoingMatches(); // need maps, servers and gamemodes
        activeTeams = new ArrayList<Team>();

        createCurrentMatches();

        banDuration = new HashMap<BanReason, String[]>();
        banDuration.put(BanReason.NOSHOW, new String[]{"30m", "1h", "3h", "6h", "12h", "1d", "3d", "1w", "2w", "1M"});
        banDuration.put(BanReason.RAGEQUIT, new String[]{"2h", "6h", "12h", "1d", "2d", "3d", "1w", "1w", "1w", "1w", "1w", "1w", "1M"});


        //Server testGTV = new Server(0, "gtv.b00bs-clan.com", 709, "arkon4bmn", "SevenAndJehar", true, null);
        gtvServerList = new ArrayList<Server>();
        //gtvServerList.add(testGTV);
    }

    public void cmdAddPlayer(Player player, List<Gametype> modes, boolean forced) {
        for (Gametype gt : modes) {
            cmdAddPlayer(player, gt, forced);
        }
    }

    public void cmdAddPlayer(Player player, Gametype gt, boolean forced) {
        if ((dynamicServers || gt.getTeamSize() == 0) && !FtwglAPI.checkIfPingStored(player)) {
            cmdGetPingURL(player);
            return;
        }
        if (player.getEnforceAC() && !FtwglAPI.hasLauncherOn(player)) {
            bot.sendNotice(player.getDiscordUser(), Config.pkup_launcheroff);
            return;
        }

        if (locked && !forced) {
            bot.sendNotice(player.getDiscordUser(), Config.pkup_lock);
            return;
        }
        if (player.isBanned() && !forced) {
            bot.sendMsg(bot.getLatestMessageChannel(), printBanInfo(player));
            return;
        }
        if (playerInActiveMatch(player) != null && playerInActiveMatch(player).getGametype().getTeamSize() > 2) {
            bot.sendNotice(player.getDiscordUser(), Config.player_already_match);
            return;
        }
        for (Team activeTeam : activeTeams) {
            if (activeTeam.isInTeam(player)) {
                bot.sendNotice(player.getDiscordUser(), Config.team_cant_soloqueue);
                return;
            }
        }

        if (gt.getName().equalsIgnoreCase("div1")) {
//			int eloRank = player.getEloRank();
//			int minEloRank = 40;
            float kdr = player.stats.kdr;
            float minKdr = 1.1f;
//			double win = player.stats.ts_wdl.calcWinRatio() * 100d;
//			double minWin = 55;
            if ((kdr < minKdr || Float.isNaN(kdr))) {
                String errmsg = Config.player_notdiv1;
                errmsg = errmsg.replace(".minkdr.", String.format("%.02f", minKdr));
                errmsg = errmsg.replace(".kdr.", String.format("%.02f", kdr));
                bot.sendNotice(player.getDiscordUser(), errmsg);
                return;
            }
        } else if (gt.getName().equalsIgnoreCase("proctf") && !player.getProctf()) {
            bot.sendNotice(player.getDiscordUser(), Config.player_not_proctf);
            return;
        }

        if (forced) {
            player.setLastMessage(System.currentTimeMillis());
        }

        String defmsg = "You are already queued for:";
        StringBuilder msg = new StringBuilder(defmsg);

        if (curMatch.containsKey(gt)) {
            Match m = curMatch.get(gt);
            if (m.getMatchState() != MatchState.Signup || m.isInMatch(player) || playerInActiveMatch(player) != null) {
                msg.append(" ").append(gt.getName());
            } else {
                m.addPlayer(player);
            }
        }

        if (!msg.toString().equals(defmsg)) {
            bot.sendNotice(player.getDiscordUser(), msg.toString());
        }

        checkTeams();
    }

    public void cmdRemovePlayer(Player player, List<Gametype> modes) {

        if (playerInActiveMatch(player) != null) {
            bot.sendNotice(player.getDiscordUser(), Config.player_already_match);
            return;
        }

        // remove from all if null
        if (modes == null) {
            for (Match match : curMatch.values()) {
                match.removePlayer(player, true);
            }
            cmdRemoveTeam(player, false);
            checkTeams();
            return;
        }

        for (Gametype gt : modes) {
            if (gt != null && curMatch.containsKey(gt)) {
                curMatch.get(gt).removePlayer(player, true); // conditions checked within function
            }
        }
        cmdRemoveTeam(player, false);
        checkTeams();
    }

    public void cmdPick(DiscordInteraction interaction, Player player, int pick) {
        for (Match match : ongoingMatches) {
            if (!match.hasSquads() && match.isCaptainTurn(player)) {
                interaction.respond(null);
                match.pick(player, pick);
                return;
            }
        }
        interaction.respond(Config.player_not_captain);
    }

    public void cmdLock() {
        locked = true;
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.lock_enable);
    }

    public void cmdUnlock() {
        locked = false;
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.lock_disable);
    }

    public void cmdRegisterPlayer(DiscordUser user, String urtauth, String msgid) {
        // check whether the user and the urtauth aren't taken
        if (Player.get(user) == null) {
            if (Player.get(urtauth) == null) {
                if (urtauth.matches("^[a-z0-9]*$")) {
                    if (urtauth.length() != 32) {
                        Player p = new Player(user, urtauth);
                        p.setElo(db.getAvgElo());
                        db.createPlayer(p);
                        bot.sendNotice(user, Config.auth_success);
                        bot.sendMsg(user.getDMChannel(), Config.ac_enforced);
                        String admin_msg = Config.auth_success_admin;
                        admin_msg = admin_msg.replace(".user.", user.getMentionString());
                        admin_msg = admin_msg.replace(".urtauth.", urtauth);
                        bot.sendMsg(getChannelByType(PickupChannelType.ADMIN), admin_msg);
                    } else {
                        DiscordAPI.deleteMessage(bot.getLatestMessageChannel(), msgid);
                        bot.sendNotice(user, Config.auth_sent_key);
                    }
                } else {
                    bot.sendNotice(user, Config.auth_invalid);
                }
            } else {
                bot.sendNotice(user, Config.auth_taken_urtauth);
            }
        } else {
            bot.sendNotice(user, Config.auth_taken_user);
        }
    }

    public boolean cmdUnregisterPlayer(Player player) {
        List<Match> matches = playerInMatch(player);
        for (Match m : matches) {
            m.removePlayer(player, true);
        }
        db.removePlayer(player);
        Player.remove(player);
        return true;
    }

    public boolean cmdEnforcePlayerAC(Player player) {
        boolean oldEnforceAC = player.getEnforceAC();
        player.setEnforceAC(!oldEnforceAC);
        db.enforcePlayerAC(player);
        return !oldEnforceAC;
    }

    public boolean cmdSetProctf(Player player) {
        boolean oldProctf = player.getProctf();
        player.setProctf(!oldProctf);
        db.setProctfPlayer(player);
        if (!oldProctf) {
            bot.sendMsg(player.getDiscordUser().getDMChannel(), Config.proctf_dm);
        }
        return !oldProctf;
    }

    public void cmdSetPlayerCountry(DiscordUser user, String str_country) {

        // check if user is already registered
        if (Player.get(user) != null) {
            Player p = db.loadPlayer(user);

            if (p.getCountry().equalsIgnoreCase("NOT_DEFINED") || user.hasAdminRights()) {

                if (Country.isValid(str_country)) {
                    db.updatePlayerCountry(p, str_country);
                    Player.get(user).setCountry(str_country);
                    bot.sendNotice(user, Config.country_added);
                } else {
                    bot.sendNotice(user, "Unknown county code. Check: <https://datahub.io/core/country-list/r/0.html>");
                }
            } else {
                // Region has been set by user, need an admin
                bot.sendNotice(user, "Your country is already set. Corresponding region: " + p.getRegion().toString());
            }
        } else {
            // send register first notice
            bot.sendNotice(user, Config.user_not_registered);
        }

    }

    public void cmdChangePlayerCountry(Player p, String str_country) {

        if (Country.isValid(str_country)) {
            db.updatePlayerCountry(p, str_country);
            p.setCountry(str_country);
            bot.sendMsg(getChannelByType(PickupChannelType.ADMIN), "Country code updated to " + str_country);
        } else {
            bot.sendMsg(getChannelByType(PickupChannelType.ADMIN), "Unknown county code. Check: <https://datahub.io/core/country-list/r/0.html>");
        }
    }

    public void cmdTopElo(int number) {
        StringBuilder embed_rank = new StringBuilder();
        StringBuilder embed_player = new StringBuilder();
        StringBuilder embed_elo = new StringBuilder();
        DiscordEmbed embed = new DiscordEmbed();
        embed.title = "Top 10 players";
        embed.color = 7056881;
        int rank = 1;

        List<Player> players = db.getTopPlayers(number);
        if (players.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), "None");
        } else {
            for (Player p : players) {
                String country;
                if (p.getCountry().equalsIgnoreCase("NOT_DEFINED")) {
                    country = "<:puma:849287183474884628>";
                } else {
                    country = ":flag_" + p.getCountry().toLowerCase() + ":";
                }
                embed_rank.append("**").append(rank).append("**\n");
                embed_player.append(country).append(" \u200b \u200b  ").append(p.getUrtauth()).append('\n');
                embed_elo.append(p.getRank().getEmoji()).append(" \u200b \u200b  ").append(p.getElo()).append("\n");
                rank++;
            }
            embed.addField("\u200b", embed_rank.toString(), true);
            embed.addField("Player", embed_player.toString(), true);
            embed.addField("Elo", embed_elo.toString(), true);

            bot.sendMsg(bot.getLatestMessageChannel(), null, embed);
        }
    }

    public void cmdTopWDL(int number, Gametype gt) {
        if (gt.getName().equalsIgnoreCase("div1")) {
            bot.sendMsg(bot.getLatestMessageChannel(), Config.div1_stats_blocked);
            return;
        }
        StringBuilder embed_rank = new StringBuilder();
        StringBuilder embed_player = new StringBuilder();
        StringBuilder embed_wdl = new StringBuilder();
        DiscordEmbed embed = new DiscordEmbed();
        embed.title = "Top 10 win rate " + gt.getName();
        embed.description = "``Season " + currentSeason.number + "``";
        embed.color = 7056881;


        Map<Player, String> topwdl = db.getTopWDL(number, gt, currentSeason);
        if (topwdl.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), "None");
        } else {
            int rank = 1;
            for (Map.Entry<Player, String> entry : topwdl.entrySet()) {
                String country;
                if (entry.getKey().getCountry().equalsIgnoreCase("NOT_DEFINED")) {
                    country = ":puma:";
                } else {
                    country = ":flag_" + entry.getKey().getCountry().toLowerCase() + ":";
                }
                embed_rank.append("**").append(rank).append("**\n");
                embed_player.append(country).append(" \u200b \u200b  ").append(entry.getKey().getUrtauth()).append('\n');
                embed_wdl.append(entry.getValue()).append("\n");
                rank++;
            }
            embed.addField("\u200b", embed_rank.toString(), true);
            embed.addField("Player", embed_player.toString(), true);
            embed.addField("Win %", embed_wdl.toString(), true);

            bot.sendMsg(bot.getLatestMessageChannel(), null, embed);
        }
    }

    public void cmdTopKDR(int number, Gametype gt) {
        if (gt.getName().equalsIgnoreCase("div1")) {
            bot.sendMsg(bot.getLatestMessageChannel(), Config.div1_stats_blocked);
            return;
        }

        StringBuilder embed_rank = new StringBuilder();
        StringBuilder embed_player = new StringBuilder();
        StringBuilder embed_wdl = new StringBuilder();
        DiscordEmbed embed = new DiscordEmbed();
        embed.title = "Top 10 kill death ratio " + gt.getName();
        embed.description = "``Season " + currentSeason.number + "``";
        embed.color = 7056881;

        Map<Player, Float> topkdr = db.getTopKDR(number, gt, currentSeason);
        if (topkdr.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), "None");
        } else {
            int rank = 1;
            for (Map.Entry<Player, Float> entry : topkdr.entrySet()) {
                String country;
                if (entry.getKey().getCountry().equalsIgnoreCase("NOT_DEFINED")) {
                    country = "<:puma:849287183474884628>";
                } else {
                    country = ":flag_" + entry.getKey().getCountry().toLowerCase() + ":";
                }
                embed_rank.append("**").append(rank).append("**\n");
                embed_player.append(country).append(" \u200b \u200b  ").append(entry.getKey().getUrtauth()).append('\n');
                embed_wdl.append(String.format("%.02f", entry.getValue())).append("\n");
                rank++;
            }
            embed.addField("\u200b", embed_rank.toString(), true);
            embed.addField("Player", embed_player.toString(), true);
            embed.addField("KDR", embed_wdl.toString(), true);

            bot.sendMsg(bot.getLatestMessageChannel(), null, embed);
        }
    }

    public void cmdTopSpree(int number, Gametype gt) {

        StringBuilder embed_rank = new StringBuilder();
        StringBuilder embed_player = new StringBuilder();
        StringBuilder embed_spree = new StringBuilder();
        StringBuilder embed_current_rank = new StringBuilder();
        StringBuilder embed_spree_current = new StringBuilder();
        StringBuilder embed_current_player = new StringBuilder();
        DiscordEmbed embed = new DiscordEmbed();
        embed.title = "Top 10 winning spree " + gt.getName();
        embed.description = "``Season " + currentSeason.number + "``";
        embed.color = 7056881;

        Map<Player, Integer> topSpree = db.getTopSpreeAllTime(gt, number);
        if (topSpree.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), "None");
        } else {
            int rank = 1;
            for (Map.Entry<Player, Integer> entry : topSpree.entrySet()) {
                String country;
                if (entry.getKey().getCountry().equalsIgnoreCase("NOT_DEFINED")) {
                    country = ":flag_white:";
                } else {
                    country = ":flag_" + entry.getKey().getCountry().toLowerCase() + ":";
                }
                embed_rank.append("**").append(rank).append("**\n");
                embed_player.append(country).append(" \u200b \u200b  ").append(entry.getKey().getUrtauth()).append('\n');
                embed_spree.append(entry.getValue()).append("\n");
                rank++;
            }
            embed.addField("\u200b", embed_rank.toString(), true);
            embed.addField("Player", embed_player.toString(), true);
            embed.addField("Spree", embed_spree.toString(), true);

            embed.addField("\u200b", "Top 3 current", false);

            Map<Player, Integer> topSpreeCurrent = db.getTopSpree(gt, 3);
            rank = 1;
            for (Map.Entry<Player, Integer> entry : topSpreeCurrent.entrySet()) {
                String country;
                if (entry.getKey().getCountry().equalsIgnoreCase("NOT_DEFINED")) {
                    country = ":flag_white:";
                } else {
                    country = ":flag_" + entry.getKey().getCountry().toLowerCase() + ":";
                }
                embed_current_rank.append("**").append(rank).append("**\n");
                embed_spree_current.append(entry.getValue()).append("\n");
                embed_current_player.append(country).append(" \u200b \u200b  ").append(entry.getKey().getUrtauth()).append('\n');
                rank++;
            }
            embed.addField("\u200b", embed_current_rank.toString(), true);
            embed.addField("Player", embed_current_player.toString(), true);
            embed.addField("Spree", embed_spree_current.toString(), true);

            bot.sendMsg(bot.getLatestMessageChannel(), null, embed);
        }
    }

    public void cmdTopRich(int number) {
        StringBuilder embed_rank = new StringBuilder();
        StringBuilder embed_player = new StringBuilder();
        StringBuilder embed_rich = new StringBuilder();
        DiscordEmbed embed = new DiscordEmbed();
        embed.title = "Top 10 richest players";
        embed.color = 7056881;

        Map<Player, Long> topRich = db.getTopRich(number);
        if (topRich.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), "None");
        } else {
            int rank = 1;
            for (Map.Entry<Player, Long> entry : topRich.entrySet()) {
                String country;
                if (entry.getKey().getCountry().equalsIgnoreCase("NOT_DEFINED")) {
                    country = "<:puma:849287183474884628>";
                } else {
                    country = ":flag_" + entry.getKey().getCountry().toLowerCase() + ":";
                }
                embed_rank.append("**").append(rank).append("**\n");
                embed_player.append(country).append(" \u200b \u200b  ").append(entry.getKey().getUrtauth()).append('\n');
                JSONObject emoji = Bet.getCoinEmoji(entry.getValue());
                embed_rich.append("<:" + emoji.getString("name") + ":" + emoji.getString("id") + "> " + String.format("%,d", entry.getValue())).append("\n");
                rank++;
            }
            embed.addField("\u200b", embed_rank.toString(), true);
            embed.addField("Player", embed_player.toString(), true);
            embed.addField("Balance", embed_rich.toString(), true);

            bot.sendMsg(bot.getLatestMessageChannel(), null, embed);
        }
    }

    public String cmdGetElo(Player p, Gametype gt) {
        if (p == null) {
            return "";
        }
        String msg = Config.pkup_getelo;
        msg = msg.replace(".urtauth.", p.getUrtauth());
        msg = msg.replace(".elo.", String.valueOf(p.getElo()));
        if (gt.getName().equals("CTF")) {
            msg = msg.replace(".wdl.", String.format("%.02f", p.stats.ctf_wdl.calcWinRatio() * 100d));
            msg = msg.replace(".kdr.", String.format("%.02f", p.stats.ctf_rating));
        } else {
            msg = msg.replace(".wdl.", String.format("%.02f", p.stats.ts_wdl.calcWinRatio() * 100d));
            msg = msg.replace(".kdr.", String.format("%.02f", FtwglAPI.getPlayerRating(p)));
        }

        msg = msg.replace(".position.", String.valueOf(p.getEloRank()));
        msg = msg.replace(".rank.", p.getRank().getEmoji());


        if (p.getCountry().equalsIgnoreCase("NOT_DEFINED")) {
            msg = msg.replace(".country.", ":flag_white:");
        } else {
            msg = msg.replace(".country.", ":flag_" + p.getCountry().toLowerCase() + ":");
        }

        return msg;
    }

    public String getPlayerCountryAuth(Player p) {
        if (p.getCountry().equalsIgnoreCase("NOT_DEFINED")) {
            return ":flag_white: " + p.getUrtauth();
        } else {
            return ":flag_" + p.getCountry().toLowerCase() + ": " + p.getUrtauth();
        }
    }

    public void cmdGetStats(Player p) {
        if (p == null) {
            return;
        }

        DiscordEmbed statsEmbed = getStatsEmbed(p);

        List<DiscordComponent> buttons = new ArrayList<DiscordComponent>();
        DiscordButton buttonSeason = new DiscordButton(DiscordButtonStyle.GREY);
        buttonSeason.custom_id = Config.INT_SEASONLIST + "_" + p.getUrtauth();
        buttonSeason.label = "Select season";
        buttons.add(buttonSeason);
        DiscordButton buttonAlltime = new DiscordButton(DiscordButtonStyle.BLURPLE);
        buttonAlltime.custom_id = Config.INT_SEASONSTATS + "_" + p.getUrtauth() + "_0";
        buttonAlltime.label = "All-time stats";
        buttons.add(buttonAlltime);
        DiscordButton buttomLastGame = new DiscordButton(DiscordButtonStyle.GREY);
        buttomLastGame.custom_id = Config.INT_LASTMATCHPLAYER + "_" + p.getUrtauth();
        buttomLastGame.label = "Last game";
        buttons.add(buttomLastGame);

        bot.sendMsgToEdit(bot.getLatestMessageChannel(), null, statsEmbed, buttons);
    }

    public void showSeasonList(DiscordInteraction interaction, Player p) {
        if (p == null) {
            return;
        }
        ArrayList<DiscordSelectOption> options = new ArrayList<DiscordSelectOption>();
        for (int i = 1; i <= currentSeason.number; i++) {
            DiscordSelectOption option = new DiscordSelectOption("Season " + i, String.valueOf(i));
            options.add(option);
        }

        ArrayList<DiscordComponent> components = new ArrayList<DiscordComponent>();
        DiscordSelectMenu seasonMenu = new DiscordSelectMenu(options);
        seasonMenu.custom_id = Config.INT_SEASONSELECTED + "_" + p.getUrtauth();
        components.add(seasonMenu);
        interaction.respond(null, null, components);
    }

    public void showSeasonStats(DiscordInteraction interaction, Player p, int seasonnumber) {
        if (p == null) {
            return;
        }

        Season season;
        if (seasonnumber == 0) {
            season = Season.AllTimeSeason();
        } else {
            season = db.getSeason(seasonnumber);
        }

        DiscordEmbed statsEmbed = getDetailedStatsEmbed(p, season);
        interaction.respond(null, statsEmbed);
    }

    public DiscordEmbed getStatsEmbed(Player p) {
        PlayerStats stats = db.getPlayerStats(p, currentSeason);
        String country = "<:puma:849287183474884628>";
        if (!p.getCountry().equalsIgnoreCase("NOT_DEFINED")) {
            country = ":flag_" + p.getCountry().toLowerCase() + ":";
        }

        DiscordEmbed statsEmbed = new DiscordEmbed();
        statsEmbed.color = 7056881;
        statsEmbed.title = country + " \u200b \u200b  " + p.getUrtauth();
        statsEmbed.thumbnail = p.getDiscordUser().getAvatarUrl();

        String boostActive = "";
        if (p.hasBoostActive()) {
            boostActive = "\n**ELO BOOST** (Expires <t:" + p.getEloBoost() / 1000 + ":R>)";
        }
        statsEmbed.description = p.getRank().getEmoji() + " \u200b \u200b  **" + p.getElo() + "**  #" + p.getEloRank() + boostActive + "\n\n``Season " + currentSeason.number + "``";

        statsEmbed.footer_icon = "https://cdn.discordapp.com/emojis/" + Bet.getCoinEmoji(p.getCoins()).getString("id");
        statsEmbed.footer = String.valueOf(p.getCoins());

        if (stats.ts_wdl.getTotal() < 5) {
            statsEmbed.addField("\u200b", "**TS**: ``" + stats.ts_wdl.getTotal() + "/5`` placement games", false);
        } else {
            statsEmbed.addField("\u200b", "TS <:lr:401457276478554112>", false);
            statsEmbed.addField("Played", String.valueOf(stats.ts_wdl.getTotal()), true);
            // if (stats.kdrRank == -1) {
            // 	statsEmbed.addField("KDR", String.format("%.02f", stats.kdr), true);

            // } else {
            // 	statsEmbed.addField("KDR", String.format("%.02f", stats.kdr) + " (#" + stats.kdrRank + ")", true);
            // }
            statsEmbed.addField("Rating", String.format("%.02f", FtwglAPI.getPlayerRating(p)), true);
            if (p.stats.wdlRank == -1) {
                statsEmbed.addField("Win %", Math.round(stats.ts_wdl.calcWinRatio() * 100d) + "%", true);

            } else {
                statsEmbed.addField("Win %", Math.round(stats.ts_wdl.calcWinRatio() * 100d) + "% (#" + stats.wdlRank + ")", true);
            }
            if (p.spree.containsKey(getGametypeByString("TS")) && p.spree.get(getGametypeByString("TS")) >= 3) {
                statsEmbed.addField("Win Streak", p.spree.get(getGametypeByString("TS")) + " :fire:", true);
            }
            if (p.spree.containsKey(getGametypeByString("DIV1")) && p.spree.get(getGametypeByString("DIV1")) >= 3) {
                statsEmbed.addField("Div1 Streak", p.spree.get(getGametypeByString("DIV1")) + " :fire:", true);
            }
        }

        if (stats.ctf_wdl.getTotal() < 5) {
            statsEmbed.addField("\u200b", "**CTF**: ``" + stats.ctf_wdl.getTotal() + "/5`` placement games", false);
        } else {
            statsEmbed.addField("\u200b", "CTF <:red_flag:400778174415503371>", false);
            statsEmbed.addField("Played", String.valueOf(stats.ctf_wdl.getTotal()), true);
            if (stats.ctfRank == -1) {
                statsEmbed.addField("Rating", String.format("%.02f", stats.ctf_rating), true);

            } else {
                statsEmbed.addField("Rating", String.format("%.02f", stats.ctf_rating) + " (#" + stats.ctfRank + ")", true);
            }

            if (stats.ctfWdlRank == -1) {
                statsEmbed.addField("Win %", Math.round(stats.ctf_wdl.calcWinRatio() * 100d) + "%", true);

            } else {
                statsEmbed.addField("Win %", Math.round(stats.ctf_wdl.calcWinRatio() * 100d) + "% (#" + stats.ctfWdlRank + ")", true);
            }
        }

        return statsEmbed;
    }

    public DiscordEmbed getDetailedStatsEmbed(Player p, Season season) {
        PlayerStats stats = db.getPlayerStats(p, season);
        String country = "<:puma:849287183474884628>";
        if (!p.getCountry().equalsIgnoreCase("NOT_DEFINED")) {
            country = ":flag_" + p.getCountry().toLowerCase() + ":";
        }

        DiscordEmbed statsEmbed = new DiscordEmbed();
        statsEmbed.color = 7056881;
        statsEmbed.title = country + " \u200b \u200b  " + p.getUrtauth();
        statsEmbed.thumbnail = p.getDiscordUser().getAvatarUrl();
        statsEmbed.description = "Season " + season.number + " (from <t:" + season.startdate / 1000 + ":d> to <t:" + season.enddate / 1000 + ":d>)";
        if (season.startdate == 0) {
            statsEmbed.description = "Season " + season.number + " (until <t:" + season.enddate / 1000 + ":d>)";
        }
        if (season.number == 0) {
            statsEmbed.description = "All time stats";
        }
        if (stats.ts_wdl.getTotal() == 0) {
            statsEmbed.addField("\u200b", "*No TS games this season*", false);
        } else {
            statsEmbed.addField("\u200b", "``Team Survivor``", false);
            statsEmbed.addField("Kills / Assists", stats.kills + "/" + stats.assists, true);
            statsEmbed.addField("Deaths", String.valueOf(stats.deaths), true);
            if (stats.kdrRank == -1) {
                statsEmbed.addField("KDR", String.format("%.02f", stats.kdr), true);

            } else {
                statsEmbed.addField("KDR", String.format("%.02f", stats.kdr) + " (#" + stats.kdrRank + ")", true);
            }

            statsEmbed.addField("Wins", String.valueOf(stats.ts_wdl.win), true);
            statsEmbed.addField("Defeats", String.valueOf(stats.ts_wdl.loss), true);
            if (stats.wdlRank == -1) {
                statsEmbed.addField("Win rate", Math.round(stats.ts_wdl.calcWinRatio() * 100d) + "%", true);

            } else {
                statsEmbed.addField("Win rate", Math.round(stats.ts_wdl.calcWinRatio() * 100d) + "% (#" + stats.wdlRank + ")", true);
            }
        }
        if (stats.ctf_wdl.getTotal() == 0) {
            statsEmbed.addField("\u200b", "*No CTF games this season*", false);
        } else {
            statsEmbed.addField("\u200b", "``Capture the Flag``", false);
            statsEmbed.addField("Caps", String.valueOf(stats.caps), true);
            statsEmbed.addField("Returns", String.valueOf(stats.returns), true);
            if (stats.ctfRank == -1) {
                statsEmbed.addField("Rating", String.format("%.02f", stats.ctf_rating), true);

            } else {
                statsEmbed.addField("Rating", String.format("%.02f", stats.ctf_rating) + " (#" + stats.ctfRank + ")", true);
            }
            statsEmbed.addField("FC kills", String.valueOf(stats.fckills), true);
            statsEmbed.addField("Stopped caps", String.valueOf(stats.stopcaps), true);
            statsEmbed.addField("Protected flags", String.valueOf(stats.protflag), true);
            statsEmbed.addField("Wins", String.valueOf(stats.ctf_wdl.win), true);
            statsEmbed.addField("Defeats", String.valueOf(stats.ctf_wdl.loss), true);
            if (stats.ctfWdlRank == -1) {
                statsEmbed.addField("Win rate", Math.round(stats.ctf_wdl.calcWinRatio() * 100d) + "%", true);

            } else {
                statsEmbed.addField("Win rate", Math.round(stats.ctf_wdl.calcWinRatio() * 100d) + "% (#" + stats.ctfWdlRank + ")", true);
            }
        }

        return statsEmbed;
    }

    public void cmdTopCountries(int number) {
        StringBuilder msg = new StringBuilder(Config.pkup_top5_header);

        ArrayList<CountryRank> countries = db.getTopCountries(number);
        if (countries.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), msg + "  None");
        } else {

            for (int i = 0; i < countries.size(); i++) {
                String ranking = Config.pkup_getelo_country;

                ranking = ranking.replace(".position.", Integer.toString(i + 1));
                ranking = ranking.replace(".country.", Country.getCountryFlag(countries.get(i).country));
                ranking = ranking.replace(".elo.", countries.get(i).elo.toString());

                msg.append("\n").append(ranking);
            }

            bot.sendMsg(bot.getLatestMessageChannel(), msg.toString());
        }
    }

    public void cmdGetMaps(Player player, boolean showZeroVote) {
        Match activeMatch = playerInActiveMatch(player);
        if (activeMatch != null) {
            String msg = Config.pkup_map_list;
            if (activeMatch.getGametype().getPrivate()) {
                msg = msg.replace(".gametype.", ":lock:" + activeMatch.getGametype().getName().toUpperCase());
            } else {
                msg = msg.replace(".gametype.", activeMatch.getGametype().getName().toUpperCase());
            }
            msg = msg.replace(".maplist.", activeMatch.getMapVotes(true));
            bot.sendMsg(bot.getLatestMessageChannel(), msg);
            return;
        }
        StringBuilder msg = new StringBuilder();
        StringBuilder emptyModes = new StringBuilder();
        for (Gametype gametype : curMatch.keySet()) {
            if (gametype.getName().startsWith("SCRIM") && curMatch.get(gametype).getPlayerCount() == 0) {
                continue;
            }
            String votes = curMatch.get(gametype).getMapVotes(!showZeroVote);
            if (votes.equals("None")) {
                if (emptyModes.length() == 0) {
                    if (gametype.getPrivate()) {
                        emptyModes.append(":lock:" + gametype.getName().toUpperCase());
                    } else {
                        emptyModes.append(gametype.getName());
                    }
                } else {
                    emptyModes.append(", ").append(gametype.getName());
                }
                continue;
            }
            if (msg.toString().isEmpty()) {
                msg = new StringBuilder();
            } else {
                msg.append("\n");
            }

            String mapString = Config.pkup_map_list;
            if (gametype.getPrivate()) {
                mapString = mapString.replace(".gametype.", ":lock:" + gametype.getName().toUpperCase());
            } else {
                mapString = mapString.replace(".gametype.", gametype.getName());
            }
            mapString = mapString.replace(".maplist.", votes);
            msg.append(mapString);
        }
        if (emptyModes.length() > 0) {
            msg.append("\n");
            String mapString = Config.pkup_map_list;
            mapString = mapString.replace(".gametype.", emptyModes);
            mapString = mapString.replace(".maplist.", "No votes");
            msg.append(mapString);
        }
        bot.sendMsg(bot.getLatestMessageChannel(), msg.toString());
    }

    public void cmdGetMapsGt(Player player, Gametype gt) {

        StringBuilder msg = new StringBuilder("None");
        for (Gametype gametype : curMatch.keySet()) {
            if (!gametype.equals(gt)) {
                continue;
            }
            if (msg.toString().equals("None")) {
                msg = new StringBuilder();
            } else {
                msg.append("\n");
            }
            String mapString = Config.pkup_map_list;
            mapString = mapString.replace(".gametype.", gametype.getName());
            mapString = mapString.replace(".maplist.", curMatch.get(gametype).getMapVotes(false));
            msg.append(mapString);
        }
        bot.sendMsg(bot.getLatestMessageChannel(), msg.toString());
    }

    public void cmdMapVote(Player player, Gametype gametype, String mapname, int number) {
        // Use boost if the player has it
        boolean bonus = false;
        if (number == 0) {
            if (player.getAdditionalMapVotes() == 0) {
                bot.sendNotice(player.getDiscordUser(), Config.no_additonal_vote);
                return;
            }
            number = player.getAdditionalMapVotes();
            bonus = true;
        }
        Match activeMatch = playerInActiveMatch(player);
        Match m = null;

        if (activeMatch != null) {
            m = activeMatch;
            gametype = m.getGametype();
        } else if (gametype == null) {
            List<Match> matches = playerInMatch(player);
            if (matches.size() == 1) {
                gametype = matches.get(0).getGametype();
            } else if (matches.size() == 0) {
                bot.sendNotice(player.getDiscordUser(), Config.player_not_in_match);
                return;
            } else {
                bot.sendNotice(player.getDiscordUser(), Config.map_specify_gametype);
                return;
            }
        }
        if (curMatch.containsKey(gametype) || m != null) {
            if (m == null) {
                m = curMatch.get(gametype);
            }

            if (m.getMatchState() == MatchState.Signup || m.getMatchState() == MatchState.AwaitingServer) {
                int counter = 0;
                GameMap map = null;
                for (GameMap xmap : m.getMapList()) {
                    if (xmap.name.toLowerCase().contains(mapname.toLowerCase())) {
                        counter++;
                        map = xmap;

                        // Hardcode to make sure !map b8 always votes for tohunga
                        if (mapname.equalsIgnoreCase("b8") && xmap.name.toLowerCase().contains("tohunga_b8")) {
                            counter = 1;
                            break;
                        }
                    }
                }
                if (counter > 1) {
                    bot.sendNotice(player.getDiscordUser(), Config.map_not_unique);
                } else if (counter == 0) {
                    bot.sendNotice(player.getDiscordUser(), Config.map_not_found);
                } else if (!gametype.getPrivate() && lastMapPlayed.get(gametype).name.equals(map.name)) {
                    bot.sendNotice(player.getDiscordUser(), Config.map_played_last_game);
                } else if (map.bannedUntil >= System.currentTimeMillis()) {
                    bot.sendNotice(player.getDiscordUser(), Config.map_banned.replace(".remaining.", String.valueOf(map.bannedUntil / 1000)));
                } else {
                    m.voteMap(player, map, number, bonus); // handles sending a msg itself
                }
            }
        }
    }

    public void cmdStatus() {
        if (curMatch.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), Config.pkup_match_unavi);
            return;
        }
        StringBuilder msg = new StringBuilder("");
        String emptyGametype = "";
        for (Match m : curMatch.values()) {
            if (m.getGametype().getName().startsWith("SCRIM")) {
                if (m.getPlayerCount() == 0) {
                    continue;
                }
            }
            if (m.getPlayerCount() == 0) {
                emptyGametype += "**" + m.getGametype().getName() + "** ";
                continue;
            }
            if (m.getGametype().getTeamSize() == 0) {
                continue;
            }
            if (msg.toString().equals("None")) {
                msg = new StringBuilder();
            } else {
                msg.append("\n");
            }

            msg.append(cmdStatus(m, null, false));
        }
        if (emptyGametype.length() > 0) {
            msg.append("\n" + emptyGametype + " Nobody signed up.");
        }
        bot.sendMsg(bot.getLatestMessageChannel(), msg.toString());
    }

    public String cmdStatus(Match match, Player player, boolean shouldSend) {
        String msg = "";
        int playerCount = match.getPlayerCount();
        if (playerCount == 0 && player == null) {
            msg = Config.pkup_status_noone;
            if (match.getGametype().getPrivate()) {
                msg = msg.replace(".gametype.", ":lock:" + match.getGametype().getName().toUpperCase());
                msg = msg.replace("<gametype>", "private");
            } else {
                msg = msg.replace(".gametype.", match.getGametype().getName().toUpperCase());
                msg = msg.replace("<gametype>", match.getGametype().getName().toLowerCase());
            }
        } else if (match.getMatchState() == MatchState.Signup || match.getMatchState() == MatchState.AwaitingServer) {
            msg = Config.pkup_status_signup;
            if (match.getGametype().getPrivate()) {
                msg = msg.replace(".gametype.", ":lock:" + match.getGametype().getName().toUpperCase());
            } else {
                msg = msg.replace(".gametype.", match.getGametype().getName().toUpperCase());
            }
            msg = msg.replace(".playernumber.", String.valueOf(playerCount));
            int maxplayer = match.getGametype().getTeamSize() == 0 ? 1 : match.getGametype().getTeamSize() * 2;
            msg = msg.replace(".maxplayer.", String.valueOf(maxplayer));

            StringBuilder playernames = new StringBuilder("None");
            if (player == null) {
                for (Player p : match.getPlayerList()) {
                    if (playernames.toString().equals("None")) {
                        playernames = new StringBuilder(getPlayerCountryAuth(p));
                    } else {
                        playernames.append(" ").append(getPlayerCountryAuth(p));
                    }
                }
                for (Team teamQueued : teamsQueued.keySet()) {
                    if (teamsQueued.get(teamQueued).equals(match.getGametype())) {
                        playernames.append("\n__Awaiting team__: ");
                        playernames.append(teamQueued.getTeamStringNoMention());
                    }
                }
            } else {
                playernames = new StringBuilder(player.getDiscordUser().getMentionString());
                playernames.append((match.isInMatch(player)) ? " added." : " removed.");
            }

            msg = msg.replace(".playerlist.", playernames.toString());

        }
        if (shouldSend) {
            bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
        }
        if (match.getMatchState() == MatchState.AwaitingServer && shouldSend && match.getGametype().getTeamSize() > 1) {
            msg = Config.pkup_status_server;
            msg = msg.replace(".gametype.", match.getGametype().getName().toUpperCase());
            msg = msg.replace(".votes.", match.getMapVotes(true));

            bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
        }
        return msg;
    }

    public void cmdSurrender(Player player) {
        Match match = playerInActiveMatch(player);
        if (match != null && match.getMatchState() == MatchState.Live) {
            match.voteSurrender(player);
        } else bot.sendNotice(player.getDiscordUser(), Config.player_not_in_match);
    }

    public void cmdReset(String cmd) {
        cmdReset(cmd, null);
    }

    public void cmdReset(String cmd, String mode) {
        if (cmd.equals("all")) {
            List<Match> toRemove = new ArrayList<Match>();
            for (Match match : ongoingMatches) {
                match.reset();
                toRemove.add(match);
            }
            ongoingMatches.removeAll(toRemove);
            for (Match m : curMatch.values()) {
                m.reset();
                createCurrentMatches();
            }
            bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.pkup_reset_all);
        } else if (cmd.equals("cur")) {
            Gametype gt = getGametypeByString(mode);
            if (gt != null) {
                curMatch.get(gt).reset();
                createMatch(gt);
            } else {
                for (Match m : curMatch.values()) {
                    m.reset();
                }
                createCurrentMatches();
            }
            bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.pkup_reset_cur);
        } else {
            Gametype gt = getGametypeByString(cmd);
            if (gt != null) {
                curMatch.get(gt).reset();
                createMatch(gt);
                bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.pkup_reset_type.replace(".gametype.", gt.getName()));
            } else {
                try {
                    int idx = Integer.parseInt(cmd);
                    for (Match match : ongoingMatches) {
                        if (match.getID() == idx) {
                            match.reset();
                            ongoingMatches.remove(match);
                            bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.pkup_reset_id.replace(".id.", cmd));
                        }
                    }
                } catch (NumberFormatException e) {
                    log.warn("Exception: ", e);
                    Sentry.captureException(e);
                }
            }
        }
    }

    public void cmdGetData(DiscordChannel channel) {
        if (ongoingMatches.isEmpty()) {
            String msg = "No active matches.";
            bot.sendMsg(channel, msg);
        }

        for (Match match : ongoingMatches) {
            String msg = "**" + match.getGametype().getName() + " Match #" + String.valueOf(match.getID()) + "** " + Config.pkup_pw + " RCON: " + match.getServer().rconpassword + " Server #" + match.getServer().id;
            msg = msg.replace(".server.", match.getServer().getAddress());
            msg = msg.replace(".password.", match.getServer().password);
            bot.sendMsg(channel, msg);
        }
    }

    public boolean cmdEnableMap(String mapname, String gametype) {
        Gametype gt = getGametypeByString(gametype);
        if (gt == null) return false;

        GameMap map = null;
        for (GameMap xmap : mapList) {
            if (xmap.name.equals(mapname)) {
                map = xmap;
                break;
            }
        }
        if (map == null) {
            map = new GameMap(mapname);
            map.setGametype(gt, true);
            db.createMap(map, gt);
            mapList.add(map);
        } else {
            map.setGametype(gt, true);
            db.updateMap(map, gt);
        }
        return true;
    }

    public boolean cmdDisableMap(String mapname, String gametype) {
        Gametype gt = getGametypeByString(gametype);
        if (gt == null) return false;

        for (GameMap map : mapList) {
            if (map.name.equals(mapname)) {
                map.setGametype(gt, false);
                db.updateMap(map, gt);
                return true;
            }
        }
        return false;
    }

    public boolean cmdEnableGametype(String gametype, String teamSize) {
        try {
            int i_teamSize = Integer.parseInt(teamSize);
            Gametype gt = getGametypeByString(gametype);
            if (gt == null) {
                gt = new Gametype(gametype.toUpperCase(), i_teamSize, true, false);
            }
            gt.setTeamSize(i_teamSize);
            gt.setActive(true);
            db.updateGametype(gt);
            // checking whether this was active before
            Gametype tmp = getGametypeByString(gametype);
            if (tmp != null) {
                curMatch.get(tmp).reset();
            }
            createMatch(gt);
            return true;
        } catch (NumberFormatException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
            return false;
        }
    }

    public boolean cmdDisableGametype(String gametype) {
        Gametype gt = getGametypeByString(gametype);
        if (gt == null) return false;

        gt.setActive(false);
        db.updateGametype(gt);
        curMatch.get(gt).reset();
        curMatch.remove(gt);
        return true;
    }

    public boolean cmdAddGameConfig(String gametype, String command) {
        Gametype gt = getGametypeByString(gametype);
        if (gt == null) return false;

        gt.addConfig(command);

        return true;
    }

    public boolean cmdRemoveGameConfig(String gametype, String command) {
        Gametype gt = getGametypeByString(gametype);
        if (gt == null) return false;

        gt.removeConfig(command);

        db.updateGametype(gt);
        return true;
    }

    public boolean cmdListGameConfig(DiscordChannel channel, String gametype) {
        Gametype gt = getGametypeByString(gametype);
        if (gt == null) return false;

        StringBuilder configlist = new StringBuilder();
        for (String config : gt.getConfig()) {
            if (configlist.length() > 0) {
                configlist.append("\n");
            }
            configlist.append(config);
        }

        String msg = Config.pkup_config_list;
        msg = msg.replace(".gametype.", gt.getName());
        msg = msg.replace(".configlist.", configlist.toString());
        bot.sendMsg(channel, msg);

        return true;
    }

    public boolean cmdAddServer(String serveraddr, String rcon, String str_region) {
        try {
            String ip = serveraddr;
            int port = 27960;
            if (serveraddr.contains(":")) {
                String[] servers = serveraddr.split(":");
                ip = servers[0];
                port = Integer.parseInt(servers[1]);
            }
            for (Server s : serverList) {
                if (s.IP.equals(ip) && s.port == port) {
                    return false;
                }
            }

            Region region = Region.valueOf(str_region);
            Server server = new Server(-1, ip, port, rcon, "???", true, region);

            db.createServer(server);
            serverList.add(server);
            checkServer();
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
            return false;
        }
    }

    public boolean cmdServerActivation(String id, boolean active) {
        try {
            int idx = Integer.parseInt(id);
            for (Server server : serverList) {
                if (server.id == idx && !server.isTaken() && server.active != active) {
                    server.active = active;
                    db.updateServer(server);
                    checkServer();
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return false;
    }

    public boolean cmdServerChangeRcon(String id, String rcon) {
        try {
            int idx = Integer.parseInt(id);
            for (Server server : serverList) {
                if (server.id == idx) {
                    server.rconpassword = rcon;
                    db.updateServer(server);
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return false;
    }

    public boolean cmdServerSendRcon(String id, String rconString) {
        try {
            int idx = Integer.parseInt(id);
            for (Server server : serverList) {
                if (server.id == idx) {
                    server.sendRcon(rconString);
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return false;
    }

    public void cmdServerList(DiscordChannel channel) {
        StringBuilder msg = new StringBuilder("None");
        for (Server server : serverList) {
            if (msg.toString().equals("None")) {
                msg = new StringBuilder(server.toString());
            } else {
                msg.append("\n").append(server.toString());
            }
        }
        bot.sendMsg(channel, msg.toString());
    }

    public void cmdMatchList(DiscordChannel channel) {
        StringBuilder msg = new StringBuilder("None");
        for (Match match : curMatch.values()) {
            if (msg.toString().equals("None")) {
                msg = new StringBuilder(match.toString());
            } else {
                msg.append("\n").append(match.toString());
            }
        }
        for (Match match : ongoingMatches) {
            if (msg.toString().equals("None")) {
                msg = new StringBuilder(match.toString());
            } else {
                msg.append("\n").append(match.toString());
            }
        }
        bot.sendMsg(channel, msg.toString());
    }

    public void cmdLive(DiscordChannel channel) {
        String msg = "No live matches found.";
        DiscordEmbed scoreBoardLinkEmbed = new DiscordEmbed();
        for (Match match : ongoingMatches) {
            msg = match.getMatchInfo();
            if (match.getMatchState() == MatchState.AwaitingServer || match.getServer().getServerMonitor() == null || match.liveScoreMsgs.isEmpty()) {
                bot.sendMsg(bot.getLatestMessageChannel(), msg);
            } else {
                String scoreBoardLink = "";
                for (DiscordMessage liveScoreMsg : match.liveScoreMsgs) {
                    if (liveScoreMsg.channel.parent_id.equals(channel.id)) {
                        scoreBoardLink = "[Live scoreboard](https://discord.com/channels/" + liveScoreMsg.channel.guild_id + "/" + liveScoreMsg.channel.id + "/" + liveScoreMsg.id + ")";
                        break;
                    }
                }
                StringBuilder embedDescription = new StringBuilder(scoreBoardLink);

                scoreBoardLinkEmbed.color = 7056881;

                if (match.getGtvServer() != null) {
                    embedDescription.append("\n").append(Config.pkup_go_pub_calm);
                }

                scoreBoardLinkEmbed.description = embedDescription.toString();
                bot.sendMsg(bot.getLatestMessageChannel(), msg, scoreBoardLinkEmbed);
            }
        }

        if (msg.equals("No live matches found.")) {
            bot.sendMsg(bot.getLatestMessageChannel(), msg);
        }
    }

    public void cmdDisplayMatch(String matchid) {
        try {
            int idx = Integer.parseInt(matchid);
            for (Match match : ongoingMatches) {
                if (match.getID() == idx) {
                    bot.sendMsg(bot.getLatestMessageChannel(), match.getMatchInfo());
                    return;
                }
            }

            Match match = db.loadMatch(idx); // TODO: cache?
            if (match != null) {
                bot.sendMsg(bot.getLatestMessageChannel(), null, match.getMatchEmbed(true));
                return;
            }

        } catch (NumberFormatException e) {
            bot.sendMsg(bot.getLatestMessageChannel(), "Match not found.");
        }
        bot.sendMsg(bot.getLatestMessageChannel(), "Match not found.");
    }

    public void cmdDisplayLastMatch() {
        // if (!ongoingMatches.isEmpty()){
        // 	bot.sendMsg(bot.getLatestMessageChannel(), "Can't display the match when a game is active.");
        // 	return;
        // }
        try {
            Match match = db.loadLastMatch();
            if (match != null) {
                bot.sendMsg(bot.getLatestMessageChannel(), null, match.getMatchEmbed(true));
                return;
            }

        } catch (NumberFormatException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        bot.sendMsg(bot.getLatestMessageChannel(), "Match not found.");
    }

    public void cmdDisplayLastMatchPlayer(Player p) {
        // if (!ongoingMatches.isEmpty()){
        // 	bot.sendMsg(bot.getLatestMessageChannel(), "Can't display the match when a game is active.");
        // 	return;
        // }
        try {
            Match match = db.loadLastMatchPlayer(p);
            if (match != null) {
                bot.sendMsg(bot.getLatestMessageChannel(), null, match.getMatchEmbed(true));
                return;
            }

        } catch (NumberFormatException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        bot.sendMsg(bot.getLatestMessageChannel(), "Match not found.");
    }

    public void showLastMatchPlayer(DiscordInteraction interaction, Player p) {
        try {
            Match match = db.loadLastMatchPlayer(p);
            if (match != null) {
                interaction.respond(null, match.getMatchEmbed(true));
                return;
            }

        } catch (NumberFormatException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        interaction.respond("Match not found.");
    }

    public void cmdResetElo() {
        db.resetElo();
    }

    // Matchcreation

    private void createCurrentMatches() {
        for (Gametype gametype : curMatch.keySet()) {
            createMatch(gametype);
        }
    }

    private void createMatch(Gametype gametype) {
        List<GameMap> gametypeMapList = new ArrayList<GameMap>();
        for (GameMap map : mapList) {
            Gametype mapGt = gametype;
            if (gametype.getPrivate()) {
                mapGt = getGametypeByString(gametype.getName().split(" ")[0]);
            }
            if (map.isActiveForGametype(mapGt)) {
                gametypeMapList.add(map);
            }
        }
        Match match = new Match(this, gametype, gametypeMapList);

        curMatch.put(gametype, match);
    }

    public void requestServer(Match match) {
        if (!awaitingServer.contains(match)) {
            awaitingServer.add(match);
            checkServer();
        }
    }

    public void cancelRequestServer(Match match) {
        awaitingServer.remove(match);
        if (match.getServer() != null && match.getServer().isTaken()) {
            match.getServer().free();
        }
    }

    public void matchStarted(Match match) {
        createMatch(match.getGametype());
        ongoingMatches.add(match);
    }

    public void matchEnded() {
//		matchRemove(match); // dont remove as this can be called while in a loop
        checkServer();
    }

    public void matchRemove(Match match) {
        ongoingMatches.remove(match);
    }

    private void checkServer() {
        if (!awaitingServer.isEmpty()) {
            Match m = awaitingServer.poll();

            if (m != null) {
                Server bs;
                if (dynamicServers || m.getGametype().getTeamSize() == 0) {
                    bs = FtwglAPI.spawnDynamicServer(m.getPlayerList());
                    if (bs != null) {
                        String spawnMsg = Config.pkup_go_pub_servspawn;
                        spawnMsg = spawnMsg.replace(".flag.", Country.getCountryFlag(bs.country));
                        spawnMsg = spawnMsg.replace(".city.", bs.city);
                        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), spawnMsg);
                    } else {
                        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.pkup_go_pub_noserv);
                        m.reset();
                    }
                } else {
                    bs = getBestServer(m.getPreferredServerRegion());
                    if (bs != null) {
                        String spawnMsg = Config.pkup_go_pub_requestserver;
                        spawnMsg = spawnMsg.replace(".flag.", bs.getRegionFlag(false, false));
                        spawnMsg = spawnMsg.replace(".region.", bs.region.name());
                        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), spawnMsg);
                    }
                }

                if (bs != null && m.getMatchState() == MatchState.AwaitingServer) {
                    m.launch(bs);
                } else {
                    for (Server server : serverList) { // Use NAE server by default when the best region is not avi
                        if (server.active && !server.isTaken() && server.isOnline() && m.getMatchState() == MatchState.AwaitingServer && server.region == Region.NAE) {
                            m.launch(server);
                            return;
                        }
                    }
                    for (Server server : serverList) { // If no NAE servers are avi take the first avi
                        if (server.active && !server.isTaken() && server.isOnline() && m.getMatchState() == MatchState.AwaitingServer) {
                            m.launch(server);
                            return;
                        }
                    }
                }
            }
        }
    }

    private Server getBestServer(Region r) {
        Server bestServer = null;

        for (Server server : serverList) {
            if (server.region == r && server.active && !server.isTaken() && server.isOnline()) {
                bestServer = server;
                break;
            }
        }

        return bestServer;
    }


    // ROLES & CHANNEL

    public boolean addRole(PickupRoleType type, DiscordRole role) {
        if (!roles.containsKey(type)) {
            roles.put(type, new ArrayList<DiscordRole>());
        }
        if (!roles.get(type).contains(role)) {
            roles.get(type).add(role);
            db.updateRole(role, type);
            return true;
        }
        return false;
    }

    public boolean removeRole(PickupRoleType type, DiscordRole role) {
        if (roles.containsKey(type)) {
            roles.get(type).remove(role);
            db.updateRole(role, PickupRoleType.NONE);
            return true;
        }
        return false;
    }

    public boolean addChannel(PickupChannelType type, DiscordChannel channel) {
        if (!channels.containsKey(type)) {
            channels.put(type, new ArrayList<DiscordChannel>());
        }
        if (!channels.get(type).contains(channel)) {
            channels.get(type).add(channel);
            db.updateChannel(channel, type);
            return true;
        }
        return false;
    }

    public boolean removeChannel(PickupChannelType type, DiscordChannel channel) {
        if (channels.containsKey(type)) {
            channels.get(type).remove(channel);
            db.updateChannel(channel, PickupChannelType.NONE);
            return true;
        }
        return false;
    }

    // AFK CHECK

    public void afkCheck() {
        Set<Player> playerList = new HashSet<Player>();
        for (Match m : curMatch.values()) {
            playerList.addAll(m.getPlayerList());
        }

        for (Player p : playerList) {
            if (playerInActiveMatch(p) != null) {
                continue;
            }

            long latestAFKmsg = p.getLastMessage();
            long afkKickTime = latestAFKmsg + 20 * 60 * 1000;
            long afkReminderTime = latestAFKmsg + 17 * 60 * 1000;

            if (afkKickTime < System.currentTimeMillis()) {
                log.info("AFK: REMOVE - {}: {} > {}", p.getUrtauth(), afkKickTime, System.currentTimeMillis());
                cmdRemovePlayer(p, null);
            } else if (afkReminderTime < System.currentTimeMillis() && !p.getAfkReminderSent()) {
                p.setAfkReminderSent(true);
                log.info("AFK: REMINDER - {}: {} > {}", p.getUrtauth(), afkKickTime, System.currentTimeMillis());
                String msg = Config.afk_reminder;
                msg = msg.replace(".user.", p.getDiscordUser().getMentionString());
                bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
            }
        }

        for (Match m : ongoingMatches) {
            if (m.getMatchState() != MatchState.AwaitingServer) {
                continue;
            }

            long lastPickTime = m.getTimeLastPick();
            long pickKickTime = lastPickTime + 3 * 60 * 1000;
            long pickReminderTime = lastPickTime + 2 * 60 * 1000;

            if (pickKickTime < System.currentTimeMillis()) {
                String msg = Config.pick_reset;
                msg = msg.replace(".matchid.", String.valueOf(db.getLastMatchID() + 1));
                msg = msg.replace(".user.", m.getCaptainsTurn().getDiscordUser().getMentionString());
                bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
                autoBanPlayer(m.getCaptainsTurn(), BanReason.NOSHOW);
                m.reset();
                ongoingMatches.remove(m);
            } else if (pickReminderTime < System.currentTimeMillis() && !m.getPickReminderSent()) {
                m.setPickReminderSent(true);
                log.info("PICK: REMINDER - {}: {} > {}", m.getCaptainsTurn().getUrtauth(), pickKickTime, System.currentTimeMillis());
                String msg = Config.pick_reminder;
                msg = msg.replace(".user.", m.getCaptainsTurn().getDiscordUser().getMentionString());
                bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
            }
        }
    }


    public void autoBanPlayer(Player player, BanReason reason) {
        String[] durationString = banDuration.get(reason);
        PlayerBan latestBan = player.getLatestBan();

        int strength = 0;
        if (latestBan != null) {
            long latestBanDuration = latestBan.endTime - latestBan.startTime;
            latestBanDuration = Math.max(latestBanDuration * 2, parseDurationFromString("1M"));
            strength = player.getPlayerBanCountSince(System.currentTimeMillis() - latestBanDuration);
            strength = Math.min(strength, durationString.length - 1);
        }
        long duration = parseDurationFromString(durationString[strength]);

        banPlayer(player, reason, duration);
    }

    public void banPlayer(Player player, BanReason reason, long duration) {

        // add reminaing bantime to the new ban
        long endTime;
        if (player.isBanned()) {
            endTime = player.getLatestBan().endTime + duration;
        } else {
            endTime = System.currentTimeMillis() + duration;
        }
        PlayerBan ban = new PlayerBan();
        ban.player = player;
        ban.startTime = System.currentTimeMillis();
        ban.endTime = endTime;
        ban.reason = reason;

        player.addBan(ban);
        db.createBan(ban);

        bot.sendMsg(getChannelByType(PickupChannelType.ADMIN), printBanInfo(player));
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), printBanInfo(player));

        for (Match match : playerInMatch(player)) {
            match.removePlayer(player, true);
        }
    }

    public void UnbanPlayer(Player player) {

        if (player.isBanned()) {

            player.forgiveBan();

            bot.sendMsg(getChannelByType(PickupChannelType.ADMIN), printUnbanInfo(player));
            bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), printUnbanInfo(player));
        } else {
            // Player is not banned
            bot.sendMsg(getChannelByType(PickupChannelType.ADMIN), printPlayerNotBannedInfo(player));
        }
    }

    public void pardonPlayer(DiscordInteraction interaction, Player pPardon, String reason, Player pAdmin) {

        if (pPardon.isBannedByBot()) {
            interaction.respond(null);

            pPardon.forgiveBotBan();

            String msg = Config.is_pardonned;
            msg = msg.replace(".user.", pPardon.getDiscordUser().getMentionString());
            msg = msg.replace(".urtauth.", pPardon.getUrtauth());

            String msgAdmin = Config.is_pardonned_admin;
            msgAdmin = msgAdmin.replace(".user.", pPardon.getDiscordUser().getMentionString());
            msgAdmin = msgAdmin.replace(".urtauth.", pPardon.getUrtauth());
            msgAdmin = msgAdmin.replace(".userAdmin.", pAdmin.getUrtauth());
            msgAdmin = msgAdmin.replace(".reason.", reason);

            bot.sendMsg(getChannelByType(PickupChannelType.ADMIN), msgAdmin);
            bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
        } else {
            // Player is not banned
            interaction.respond(printPlayerNotBannedInfo(pPardon));
        }
    }


    public String printBanInfo(Player player) {
        PlayerBan ban = player.getLatestBan();

        String msg = Config.not_banned;
        if (ban == null || ban.endTime <= System.currentTimeMillis() || ban.forgiven) {
            msg = msg.replace(".user.", player.getDiscordUser().getMentionString());
            msg = msg.replace(".urtauth.", player.getUrtauth());
        } else {
            String time = parseStringFromDuration(ban.endTime - System.currentTimeMillis());

            msg = Config.is_banned;
            msg = msg.replace(".user.", player.getDiscordUser().getMentionString());
            msg = msg.replace(".urtauth.", player.getUrtauth());
            msg = msg.replace(".reason.", ban.reason.name());
            msg = msg.replace(".time.", time);
        }

        ArrayList<PlayerBan> past_bans = player.getPlayerBanListSince(System.currentTimeMillis() - parseDurationFromString("2M"));
        if (past_bans.size() == 0) {
            return msg;
        }

        msg = msg + "\n\n";
        msg = msg + Config.ban_history;
        String ban_item;
        for (PlayerBan past_ban : past_bans) {
            ban_item = '\n' + Config.ban_history_item;

            ban_item = ban_item.replace(".date.", String.valueOf(past_ban.startTime / 1000));
            ban_item = ban_item.replace(".duration.", parseStringFromDuration(past_ban.endTime - past_ban.startTime));
            ban_item = ban_item.replace(".reason.", past_ban.reason.name());

            if (past_ban.forgiven) {
                ban_item = ban_item + " *(forgiven)*";
            }
            msg = msg + ban_item;
        }

        return msg;
    }

    public String printUnbanInfo(Player player) {
        String msg = Config.is_unbanned;
        msg = msg.replace(".user.", player.getDiscordUser().getMentionString());
        msg = msg.replace(".urtauth.", player.getUrtauth());
        return msg;
    }

    public String printPlayerNotBannedInfo(Player player) {
        String msg = Config.is_notbanned;
        msg = msg.replace(".user.", player.getDiscordUser().getMentionString());
        msg = msg.replace(".urtauth.", player.getUrtauth());
        return msg;
    }

    // HELPER

    public static long parseDurationFromString(String string) {
        long total = 0;

        StringBuilder curDuration = new StringBuilder();
        for (int i = 0; i < string.length(); ++i) {
            if (Character.isDigit(string.charAt(i))) {
                curDuration.append(string.charAt(i));
            } else {
                long duration = Long.parseLong(curDuration.toString());
                switch (string.charAt(i)) {
                    case 'y':
                        duration *= 12;
                    case 'M':
                        duration *= 4;
                    case 'w':
                        duration *= 7;
                    case 'd':
                        duration *= 24;
                    case 'h':
                        duration *= 60;
                    case 'm':
                        duration *= 60;
                    case 's':
                        duration *= 1000;
                }
                total += duration;
                curDuration = new StringBuilder();
            }
        }
        return total;
    }

    public static String parseStringFromDuration(long duration) {
        String string = "";

        int acc = 2;

        // TODO REFACTOR
        long second = 1000;
        long minute = second * 60;
        long hour = minute * 60;
        long day = hour * 24;
        long week = day * 7;
        long month = week * 4;
        long year = month * 12;

        long curAmount;
        if ((curAmount = duration / year) > 0) {
            string += curAmount + "y";
            duration = duration % year;
            --acc;
        }
        if ((curAmount = duration / month) > 0) {
            string += curAmount + "M";
            duration = duration % month;
            --acc;
        }
        if ((curAmount = duration / week) > 0 && acc > 0) {
            string += curAmount + "w";
            duration = duration % week;
            --acc;
        }
        if ((curAmount = duration / day) > 0 && acc > 0) {
            string += curAmount + "d";
            duration = duration % day;
            --acc;
        }
        if ((curAmount = duration / hour) > 0 && acc > 0) {
            string += curAmount + "h";
            duration = duration % hour;
            --acc;
        }
        if ((curAmount = duration / minute) > 0 && acc > 0) {
            string += curAmount + "m";
            duration = duration % minute;
            --acc;
        }
        if ((curAmount = duration / second) > 0 && acc > 0) {
            string += curAmount + "s";
            --acc;
        }

        return string;
    }

    public Gametype getGametypeByString(String mode) {
        for (Gametype gt : curMatch.keySet()) {
            if (gt.getName().equalsIgnoreCase(mode)) {
                return gt;
            }
        }
        return null;
    }

    public List<Match> playerInMatch(Player player) {
        List<Match> matchlist = new ArrayList<Match>();
        for (Gametype gt : curMatch.keySet()) {
            Match m = playerInMatch(gt, player);
            if (m != null) {
                matchlist.add(m);
            }
        }
        return matchlist;
    }

    public Match playerInActiveMatch(Player player) {
        for (Match m : ongoingMatches) {
            if (m.isInMatch(player)) {
                return m;
            }
        }
        return null;
    }

    public Match playerInMatch(Gametype gametype, Player player) {
        if (curMatch.containsKey(gametype)) {
            if (curMatch.get(gametype).isInMatch(player)) {
                return curMatch.get(gametype);
            }
        }
        return null;
    }

    public List<DiscordRole> getStreamerList() {
        List<DiscordRole> list = new ArrayList<DiscordRole>();
        if (roles.containsKey(PickupRoleType.STREAMER)) {
            list.addAll(roles.get(PickupRoleType.STREAMER));
        }
        return list;
    }

    public List<DiscordRole> getAdminList() {
        List<DiscordRole> list = new ArrayList<DiscordRole>();
        if (roles.containsKey(PickupRoleType.ADMIN)) {
            list.addAll(roles.get(PickupRoleType.ADMIN));
        }
        if (roles.containsKey(PickupRoleType.SUPERADMIN)) {
            list.addAll(roles.get(PickupRoleType.SUPERADMIN));
        }
        return list;
    }

    public List<DiscordRole> getSuperAdminList() {
        List<DiscordRole> list = new ArrayList<DiscordRole>();
        if (roles.containsKey(PickupRoleType.SUPERADMIN)) {
            list.addAll(roles.get(PickupRoleType.SUPERADMIN));
        }
        return list;
    }

    public List<DiscordRole> getRoleByType(PickupRoleType type) {
        if (roles.containsKey(type)) {
            return roles.get(type);
        }
        return new ArrayList<DiscordRole>();
    }

    public List<DiscordChannel> getChannelByType(PickupChannelType type) {
        if (channels.containsKey(type)) {
            return channels.get(type);
        }
        return new ArrayList<DiscordChannel>();
    }

    public Set<PickupRoleType> getRoleTypes() {
        return roles.keySet();
    }

    public Set<PickupChannelType> getChannelTypes() {
        return channels.keySet();
    }

    public Server getServerByID(int id) {
        for (Server s : serverList) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    public GameMap getMapByName(String name) {
        for (GameMap m : mapList) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        return null;
    }

    public void setLastMapPlayed(Gametype gt, GameMap map) {
        lastMapPlayed.remove(gt);
        lastMapPlayed.put(gt, map);
    }

    public void removeLastMapPlayed(Gametype gt) {
        lastMapPlayed.remove(gt);
        lastMapPlayed.put(gt, new GameMap("null"));
    }

    public GameMap getLastMapPlayed(Gametype gt) {
        if (gt.getPrivate()) {
            return new GameMap("null");
        }
        return lastMapPlayed.get(gt);
    }

    public Server setupGTV() {
        for (Server gtv : gtvServerList) {
            if (!gtv.isTaken()) {
                gtv.take();
                return gtv;
            }
        }
        return null;
    }

    public void restartApplication() throws URISyntaxException, IOException {
        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final File currentJar = new File(PickupBotApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        /* is it a jar file? */
        if (!currentJar.getName().endsWith(".jar"))
            return;

        /* Build command: java -jar application.jar */
        final ArrayList<String> command = new ArrayList<String>();
        command.add(javaBin);
        command.add("-jar");
        command.add(currentJar.getPath());

        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.start();
        System.exit(0);
    }

    public void cmdLaunchAC(DiscordInteraction interaction, Player player, int matchId, String ip, String password) {
        for (Match match : ongoingMatches) {
            if (match.getID() == matchId) {
                for (Player playerInMatch : match.getPlayerList()) {
                    if (playerInMatch.equals(player)) {
                        String response = FtwglAPI.launchAC(player, ip, password);
                        interaction.respond(response);
                        return;
                    }
                }
                if (player.getDiscordUser().hasStreamerRights() || player.getDiscordUser().hasAdminRights()) {
                    boolean success = match.addStreamerAuth(player.getUrtauth());
                    if (!success) {
                        interaction.respond(Config.ftw_servernotready);
                        return;
                    }
                    String response = FtwglAPI.launchAC(player, ip, password);
                    interaction.respond(response);
                    return;
                }
                interaction.respond(Config.ftw_playernotinmatch);
                return;
            }
        }

        interaction.respond(Config.ftw_matchnotfound);
    }

    public void invitePlayersToTeam(Player captain, List<Player> invitedPlayers) {
        Team team = null;
        for (Team activeTeam : activeTeams) {
            if (activeTeam.getCaptain().equals(captain)) {
                team = activeTeam;
                break;
            } else if (activeTeam.isInTeam(captain)) {
                bot.sendNotice(captain.getDiscordUser(), Config.team_involved_other);
                return;
            }
        }
        if (team == null) {
            team = new Team(this, captain);
            activeTeams.add(team);
        }

        for (Player invitedPlayer : invitedPlayers) {
            if (team.isFull()) {
                bot.sendNotice(captain.getDiscordUser(), Config.team_is_full);
                return;
            }
            if (team.isInTeam(invitedPlayer)) {
                bot.sendNotice(captain.getDiscordUser(), Config.team_already_in.replace(".auth.", invitedPlayer.getUrtauth()));
                continue;
            }
            if (team.isInvitedToTeam(invitedPlayer)) {
                bot.sendNotice(captain.getDiscordUser(), Config.team_already_invited.replace(".auth.", invitedPlayer.getUrtauth()));
                continue;
            }
            boolean alreadyInvolved = false;
            for (Team activeTeam : activeTeams) {
                if (activeTeam.isInTeam(invitedPlayer)) {
                    alreadyInvolved = true;
                    break;
                }
            }
            if (alreadyInvolved) {
                bot.sendNotice(captain.getDiscordUser(), Config.team_already_involved.replace(".auth.", invitedPlayer.getUrtauth()));
                continue;
            }
            team.invitePlayer(invitedPlayer);
        }
        cmdRemovePlayer(captain, null);
    }

    public void leaveTeam(Player player) {
        for (Team activeTeam : activeTeams) {
            if (activeTeam.getCaptain().equals(player)) {
                activeTeams.remove(activeTeam);
                activeTeam.archive();
                bot.sendNotice(player.getDiscordUser(), Config.team_leave_captain);

                cmdRemovePlayer(player, null);
                return;
            } else if (activeTeam.isInTeam(player)) {
                activeTeam.removePlayer(player);
                bot.sendNotice(player.getDiscordUser(), Config.team_leave.replace(".captain.", activeTeam.getCaptain().getUrtauth()));
                cmdRemovePlayer(player, null);
                return;
            }
        }
        bot.sendNotice(player.getDiscordUser(), Config.team_noteam);
    }

    public void answerTeamInvite(DiscordInteraction interaction, Player player, int answer, Player captain, Player invitedPlayer) {
        Team team = null;
        for (Team activeTeam : activeTeams) {
            if (activeTeam.getCaptain().equals(captain)) {
                team = activeTeam;
            }
        }
        if (team == null) {
            interaction.respond(Config.team_error_active);
            interaction.message.delete();
            return;
        }

        if (player.equals(captain) && answer == 2) {
            team.cancelInvitation(invitedPlayer);
            interaction.respond(null);
            interaction.message.delete();
            return;
        }
        if (!player.equals(invitedPlayer)) {
            interaction.respond(Config.team_error_invite.replace(".player.", invitedPlayer.getUrtauth()));
            return;
        }

        if (answer == 1) {
            if (team.isFull()) {
                interaction.respond(Config.team_is_full);
                return;
            }
            team.acceptInvitation(invitedPlayer);

        } else {
            team.declineInvitation(invitedPlayer);
        }
        interaction.respond(null);
        interaction.message.delete();
    }

    public void removeTeamMember(DiscordInteraction interaction, Player player, Player captain, Player playerToRemove) {
        if (!player.equals(playerToRemove) && !player.equals(captain)) {
            interaction.respond(Config.team_error_remove);
            return;
        }

        Team team = null;
        for (Team activeTeam : activeTeams) {
            if (activeTeam.getCaptain().equals(captain)) {
                team = activeTeam;
            }
        }
        if (team == null) {
            interaction.respond(Config.team_error_active);
            return;
        }

        team.removePlayer(playerToRemove);
        interaction.respond(null);
        interaction.message.delete();
        cmdRemovePlayer(playerToRemove, null);
    }

    public void cmdAddTeam(Player player, Gametype gt, boolean forced) {
        Team team = null;
        for (Team activeTeam : activeTeams) {
            if (activeTeam.getCaptain().equals(player) || (forced && activeTeam.isInTeam(player))) {
                team = activeTeam;
                break;
            }
        }
        if (team == null) {
            bot.sendNotice(player.getDiscordUser(), Config.team_noteam_captain);
            return;
        }

//		if (gt.getTeamSize() < 2){
//			bot.sendNotice(player.getDiscordUser(), Config.team_error_wrong_gt);
//			return;
//		}

        if (team.getPlayers().size() != gt.getTeamSize()) {
            bot.sendNotice(player.getDiscordUser(), Config.team_error_teamsize.replace(".teamsize.", String.valueOf(gt.getTeamSize())));
            return;
        }

        Match m = curMatch.get(gt);

        for (Player teamPlayer : team.getPlayers()) {
            if (teamPlayer.isBanned()) {
                bot.sendMsg(bot.getLatestMessageChannel(), printBanInfo(teamPlayer));
                return;
            }
            if (playerInActiveMatch(teamPlayer) != null) {
                bot.sendNotice(teamPlayer.getDiscordUser(), Config.player_already_match);
                return;
            }
            if (curMatch.containsKey(gt)) {
                if (m.getMatchState() != MatchState.Signup || m.isInMatch(teamPlayer) || playerInActiveMatch(teamPlayer) != null) {
                    m.removePlayer(teamPlayer, false);
                }
            }
        }

        teamsQueued.put(team, gt);

        String addedMsg = Config.team_added;
        addedMsg = addedMsg.replace(".gamemode.", gt.getName());
        addedMsg = addedMsg.replace(".team.", team.getTeamString());
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), addedMsg);
        checkTeams();
    }

    public void checkTeams() {
        for (Team queuedTeam : teamsQueued.keySet()) {
            Gametype gt = teamsQueued.get(queuedTeam);
            Match m = curMatch.get(gt);
            if (curMatch.containsKey(gt)) {
                if (m.getPlayerCount() <= gt.getTeamSize()) {
                    m.addSquad(queuedTeam);
                    teamsQueued.remove(queuedTeam);
                    for (Player teamPlayer : queuedTeam.getPlayers()) {
                        m.addPlayer(teamPlayer);
                        teamPlayer.afkCheck();
                    }
                }
            }
        }
    }

    public void cmdRemoveTeam(Player player, boolean shouldSpam) {
        Team team = null;
        for (Team activeTeam : activeTeams) {
            if (activeTeam.isInTeam(player)) {
                team = activeTeam;
                break;
            }
        }
        if (team == null) {
            if (shouldSpam) {
                bot.sendNotice(player.getDiscordUser(), Config.team_noteam);
            }
            return;
        }

        if (playerInActiveMatch(player) != null) {
            if (shouldSpam) {
                bot.sendNotice(player.getDiscordUser(), Config.player_already_match);
            }
            return;
        }

        teamsQueued.remove(team);

        for (Match match : curMatch.values()) {
            for (Player teamPlayer : team.getPlayers()) {
                match.removePlayer(teamPlayer, shouldSpam);
            }
            match.removeSquad(team);
        }

        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), Config.team_removed_queue.replace(".team.", team.getTeamString()));
    }

    public void cmdPrintTeam(Player player) {
        Team team = null;
        for (Team activeTeam : activeTeams) {
            if (activeTeam.isInTeam(player)) {
                team = activeTeam;
                break;
            }
        }
        if (team == null) {
            bot.sendNotice(player.getDiscordUser(), Config.team_noteam);
            return;
        }

        bot.sendNotice(player.getDiscordUser(), Config.team_print_info.replace(".team.", team.getTeamStringNoMention()));
    }

    public void cmdPrintTeams(Player player) {
        String msg = Config.team_print_all;
        for (Team activeTeam : activeTeams) {
            msg = msg + "\n" + activeTeam.getTeamStringNoMention();
        }
        if (msg.equals(Config.team_print_all)) {
            bot.sendMsg(player.getLastPublicChannel(), Config.team_print_noteam);
            return;
        }

        bot.sendMsg(player.getLastPublicChannel(), msg);
    }

    public void cmdEnableDynamicServer() {
        dynamicServers = true;
    }

    public void cmdDisableDynamicServer() {
        dynamicServers = false;
    }

    public boolean getDynamicServers() {
        return dynamicServers;
    }

    public void cmdGetPingURL(Player player) {
        bot.sendNotice(player.getDiscordUser(), Config.ftw_error_noping);
        bot.sendMsg(player.getDiscordUser(), Config.ftw_dm_noping.replace(".url.", FtwglAPI.requestPingUrl(player)));
    }

    public void showBets(DiscordInteraction interaction, int matchId, String color, Player p) {
        Match match = null;
        for (Match m : ongoingMatches) {
            if (m.getID() == matchId) {
                match = m;
            }
        }
        if (match == null || !match.acceptBets()) {
            interaction.respond(Config.bets_notaccepting);
            return;
        }

        String otherTeam = color.equals("red") ? "blue" : "red";
        if (match.isInMatch(p) && match.getTeam(p).equals(otherTeam)) {
            interaction.respond(Config.bets_otherteam);
            return;
        }

        if (p.getCoins() <= 0) {
            interaction.respond(Config.bets_nomoney);
            return;
        }

        ArrayList<DiscordComponent> buttons = new ArrayList<DiscordComponent>();

        if (p.getCoins() > 10) {
            JSONObject coinEmoji = Bet.getCoinEmoji(10);
            DiscordButton button10 = new DiscordButton(DiscordButtonStyle.GREY);
            button10.label = "10";
            button10.custom_id = "bet_" + matchId + "_" + color + "_" + 10;
            button10.emoji = coinEmoji;
            buttons.add(button10);
        }

        if (p.getCoins() > 100) {
            JSONObject coinEmoji = Bet.getCoinEmoji(100);
            DiscordButton button100 = new DiscordButton(DiscordButtonStyle.GREY);
            button100.label = "100";
            button100.custom_id = "bet_" + matchId + "_" + color + "_" + 100;
            button100.emoji = coinEmoji;
            buttons.add(button100);
        }


        if (p.getCoins() > 1000) {
            JSONObject coinEmoji = Bet.getCoinEmoji(1000);
            DiscordButton button1000 = new DiscordButton(DiscordButtonStyle.GREY);
            button1000.label = String.format("%,d", 1000);
            button1000.custom_id = "bet_" + matchId + "_" + color + "_" + 1000;
            button1000.emoji = coinEmoji;
            buttons.add(button1000);
        }

        if (p.getCoins() > 10000) {
            JSONObject coinEmoji = Bet.getCoinEmoji(10000);
            DiscordButton button10000 = new DiscordButton(DiscordButtonStyle.GREY);
            button10000.label = String.format("%,d", 10000);
            button10000.custom_id = "bet_" + matchId + "_" + color + "_" + 10000;
            button10000.emoji = coinEmoji;
            buttons.add(button10000);
        }

        if (p.getCoins() > 100000) {
            JSONObject coinEmoji = Bet.getCoinEmoji(100000);
            DiscordButton button100000 = new DiscordButton(DiscordButtonStyle.GREY);
            button100000.label = String.format("%,d", 100000);
            button100000.custom_id = "bet_" + matchId + "_" + color + "_" + 100000;
            button100000.emoji = coinEmoji;
            buttons.add(button100000);
        }

        if (p.getCoins() > 1000000) {
            JSONObject coinEmoji = Bet.getCoinEmoji(1000000);
            DiscordButton button1000000 = new DiscordButton(DiscordButtonStyle.GREY);
            button1000000.label = String.format("%,d", 1000000);
            button1000000.custom_id = "bet_" + matchId + "_" + color + "_" + 1000000;
            button1000000.emoji = coinEmoji;
            buttons.add(button1000000);
        }

        JSONObject coinEmoji = Bet.getCoinEmoji(p.getCoins());
        DiscordButton buttonallin = new DiscordButton(DiscordButtonStyle.RED);
        buttonallin.label = String.format("%,d", p.getCoins()) + " (ALL IN)";
        buttonallin.custom_id = "bet_" + matchId + "_" + color + "_-1";
        buttonallin.emoji = coinEmoji;
        buttons.add(buttonallin);

        String msg = Config.bets_howmuch;
        msg = msg.replace(".team.", color);
        msg = msg.replace(".balance.", String.valueOf(p.getCoins()));
        msg = msg.replace(".matchid.", String.valueOf(matchId));
        msg = msg.replace(".emojiname.", coinEmoji.getString("name"));
        msg = msg.replace(".emojiid.", coinEmoji.getString("id"));
        interaction.respond(msg, null, buttons);
    }

    public void bet(DiscordInteraction interaction, int matchId, String color, long amount, Player p) {
        boolean allIn = false;
        Match match = null;
        for (Match m : ongoingMatches) {
            if (m.getID() == matchId) {
                match = m;
            }
        }
        if (match == null || !match.acceptBets()) {
            interaction.respond(Config.bets_notaccepting);
            return;
        }

        if (amount > p.getCoins()) {
            interaction.respond(Config.bets_insufficient);
            return;
        }

        if (p.getCoins() <= 0) {
            interaction.respond(Config.bets_nomoney);
            return;
        }

        if (amount > 1000000) {
            interaction.respond(Config.bets_above_limit);
            return;
        }

        // All in
        if (amount == -1) {
            amount = p.getCoins();
            allIn = true;
        }

        String otherTeam = color.equals("red") ? "blue" : "red";
        if (match.isInMatch(p) && match.getTeam(p).equals(otherTeam)) {
            interaction.respond(Config.bets_otherteam);
            return;
        }
        float odds = color.equals("red") ? match.getOdds(0) : match.getOdds(1);
        Bet bet = new Bet(match.getID(), p, color, amount, odds);
        for (Bet matchBet : match.bets) {
            if (matchBet.player.equals(p) && color.equals(matchBet.color)) {
                if (!allIn && amount + matchBet.amount > 1000000) {
                    interaction.respond(Config.bets_above_limit);
                    return;
                }
                matchBet.amount += amount;
                bet.place(match);
                return;
            }
        }
        match.bets.add(bet);
        bet.place(match);

        interaction.respond(null);
    }

    public void showBuys(DiscordInteraction interaction, Player p) {
        ArrayList<DiscordComponent> buttons = new ArrayList<DiscordComponent>();

        int price;
        JSONObject emoji;
        DiscordButton button;

        // Elo boost
        price = 1000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " Elo boost (2h)";
        button.custom_id = "buy_eloboost";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);

        // Elo boost
        price = 1000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " Additional map votes";
        button.custom_id = "buy_showvoteoptions";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);

        // Elo boost
        price = 10000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " Ban a map (2h)";
        button.custom_id = "buy_banmap";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);

        JSONObject coinEmoji = Bet.getCoinEmoji(p.getCoins());
        String msg = Config.buy_show;
        msg = msg.replace(".balance.", String.format("%,d", p.getCoins()));
        msg = msg.replace(".emojiname.", coinEmoji.getString("name"));
        msg = msg.replace(".emojiid.", coinEmoji.getString("id"));
        interaction.respond(msg, null, buttons);
    }

    public void buyBoost(DiscordInteraction interaction, Player p) {
        int price = 1000;
        JSONObject emoji = Bet.getCoinEmoji(price);
        if (p.getCoins() < price) {
            interaction.respond(Config.bets_insufficient);
            return;
        }

        if (p.hasBoostActive()) {
            interaction.respond(Config.buy_boostactive.replace(".remaining.", String.valueOf(p.getEloBoost() / 1000)));
            return;
        }

        p.setEloBoost((long) (System.currentTimeMillis() + 7.2e6)); // 2h
        p.spendCoins(price);
        p.saveWallet();
        interaction.respond(null);

        String msg = Config.buy_boostactivated;
        msg = msg.replace(".player.", p.getDiscordUser().getMentionString());
        msg = msg.replace(".price.", String.valueOf(price));
        msg = msg.replace(".emojiname.", emoji.getString("name"));
        msg = msg.replace(".emojiid.", emoji.getString("id"));
        msg = msg.replace(".remaining.", String.valueOf(p.getEloBoost() / 1000));
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
    }

    public void showAdditionalVoteOptions(DiscordInteraction interaction, Player p) {
        if (p.getAdditionalMapVotes() > 0) {
            interaction.respond(Config.buy_voteoptionsalready.replace(".vote.", String.valueOf(p.getAdditionalMapVotes())));
            return;
        }
        ArrayList<DiscordComponent> buttons = new ArrayList<DiscordComponent>();

        int price;
        JSONObject emoji;
        DiscordButton button;

        price = 1000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " 1 vote";
        button.custom_id = "buy_additionalvote_1";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);

        price = 2000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " 2 votes";
        button.custom_id = "buy_additionalvote_2";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);

        price = 4000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " 3 votes";
        button.custom_id = "buy_additionalvote_3";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);

        price = 8000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " 4 votes";
        button.custom_id = "buy_additionalvote_4";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);

        price = 16000;
        emoji = Bet.getCoinEmoji(price);
        button = new DiscordButton(DiscordButtonStyle.GREY);
        button.label = price + " 5 votes";
        button.custom_id = "buy_additionalvote_5";
        button.emoji = emoji;
        button.disabled = p.getCoins() < price;
        buttons.add(button);


        JSONObject coinEmoji = Bet.getCoinEmoji(p.getCoins());
        String msg = Config.buy_showvoteoptions;
        interaction.respond(msg, null, buttons);
    }

    public void buyAdditionalVotes(DiscordInteraction interaction, Player p, int number) {
        int price = 1000;
        if (number == 2) {
            price = 2000;
        } else if (number == 3) {
            price = 4000;
        } else if (number == 4) {
            price = 8000;
        } else if (number == 5) {
            price = 16000;
        }
        if (p.getAdditionalMapVotes() > 0) {
            interaction.respond(Config.buy_voteoptionsalready.replace(".vote.", String.valueOf(p.getAdditionalMapVotes())));
            return;
        }

        JSONObject emoji = Bet.getCoinEmoji(price);
        if (p.getCoins() < price) {
            interaction.respond(Config.bets_insufficient);
            return;
        }

        p.setAdditionalMapVotes(number);
        p.spendCoins(price);
        p.saveWallet();
        interaction.respond(Config.buy_addvote_purchased);

        String msg = Config.buy_addvotesactivated;
        msg = msg.replace(".player.", p.getDiscordUser().getMentionString());
        msg = msg.replace(".price.", String.valueOf(price));
        msg = msg.replace(".emojiname.", emoji.getString("name"));
        msg = msg.replace(".emojiid.", emoji.getString("id"));
        msg = msg.replace(".vote.", String.valueOf(p.getAdditionalMapVotes()));
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
    }

    public void buyBanMap(DiscordInteraction interaction, Player p) {
        int price = 10000;

        JSONObject emoji = Bet.getCoinEmoji(price);
        if (p.getCoins() < price) {
            interaction.respond(Config.bets_insufficient);
            return;
        }

        p.setMapBans(p.getMapBans() + 1);
        p.spendCoins(price);
        p.saveWallet();
        interaction.respond(Config.buy_banmap_purchased);

        String msg = Config.buy_mapbanactivated;
        msg = msg.replace(".player.", p.getDiscordUser().getMentionString());
        msg = msg.replace(".price.", String.valueOf(price));
        msg = msg.replace(".emojiname.", emoji.getString("name"));
        msg = msg.replace(".emojiid.", emoji.getString("id"));
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
    }

    public void cmdUseMapBan(Player p, String mapname) {
        if (p.getMapBans() == 0) {
            bot.sendNotice(p.getDiscordUser(), Config.no_map_ban);
            return;
        }

        int counter = 0;
        GameMap map = null;
        for (GameMap xmap : mapList) {
            if (xmap.name.toLowerCase().contains(mapname.toLowerCase())) {
                counter++;
                map = xmap;
            }
        }
        if (counter > 1) {
            bot.sendNotice(p.getDiscordUser(), Config.map_not_unique);
            return;
        } else if (counter == 0) {
            bot.sendNotice(p.getDiscordUser(), Config.map_not_found);
            return;
        } else if (map.bannedUntil >= System.currentTimeMillis()) {
            bot.sendNotice(p.getDiscordUser(), Config.map_already_banned.replace(".remaining.", String.valueOf(map.bannedUntil / 1000)));
            return;
        }

        p.setMapBans(p.getMapBans() - 1);
        map.bannedUntil = (long) (System.currentTimeMillis() + 7.2e6);
        db.updateMapBan(map);

        for (Gametype gt : curMatch.keySet()) {
            curMatch.get(gt).banMap(map);
        }

        String msg = Config.used_map_ban;
        msg = msg.replace(".player.", p.getDiscordUser().getMentionString());
        msg = msg.replace(".map.", map.name);
        msg = msg.replace(".time.", String.valueOf(map.bannedUntil / 1000));
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
    }

    public void cmdWallet(Player p) {
        JSONObject coinEmoji = Bet.getCoinEmoji(p.getCoins());
        String msg = Config.buy_show_wallet;
        msg = msg.replace(".player.", p.getDiscordUser().username);
        msg = msg.replace(".balance.", String.format("%,d", p.getCoins()));
        msg = msg.replace(".emojiname.", coinEmoji.getString("name"));
        msg = msg.replace(".emojiid.", coinEmoji.getString("id"));
        bot.sendMsg(bot.getLatestMessageChannel(), msg);
    }

    public void cmdDonate(Player p, Player destP, int amount) {
        if (amount <= 0) {
            bot.sendNotice(p.getDiscordUser(), Config.donate_incorrect_amount);
            return;
        } else if (amount > 10000) {
            bot.sendNotice(p.getDiscordUser(), Config.donate_above_limit);
            return;
        } else if (amount > p.getCoins()) {
            bot.sendNotice(p.getDiscordUser(), Config.bets_insufficient);
            return;
        }

        p.spendCoins(amount);
        p.saveWallet();
        destP.addCoins(amount);
        destP.saveWallet();

        JSONObject coinEmoji = Bet.getCoinEmoji(amount);
        String msg = Config.donate_processed;
        msg = msg.replace(".player.", p.getDiscordUser().getMentionString());
        msg = msg.replace(".otherplayer.", destP.getDiscordUser().getMentionString());
        msg = msg.replace(".amount.", String.format("%,d", amount));
        msg = msg.replace(".emojiname.", coinEmoji.getString("name"));
        msg = msg.replace(".emojiid.", coinEmoji.getString("id"));
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
    }

    public void cmdBetHistory(Player p) {
        ArrayList<Bet> betList = db.getBetHistory(p);

        DiscordEmbed embed = new DiscordEmbed();
        embed.title = "Last 10 bets of " + p.getDiscordUser().username;
        embed.color = 7056881;
        if (betList.size() == 0) {
            embed.description = "No bets yet";
            bot.sendMsg(bot.getLatestMessageChannel(), null, embed);
            return;
        }

        String matchIdField = "";
        String amountField = "";
        String resultField = "";

        boolean firstBet = true;
        for (Bet bet : betList) {
            String linebreak = "\n";
            if (firstBet) {
                linebreak = "";
                firstBet = false;
            }
            matchIdField += linebreak + bet.matchid;

            JSONObject amountEmoji = Bet.getCoinEmoji(bet.amount);
            amountField += linebreak + "<:" + amountEmoji.get("name") + ":" + amountEmoji.get("id") + "> " + String.format("%,d", bet.amount);

            if (bet.won) {
                JSONObject wonEmoji = Bet.getCoinEmoji(Math.round(bet.amount * bet.odds));
                resultField += linebreak + "Won <:" + wonEmoji.get("name") + ":" + wonEmoji.get("id") + "> " + String.format("%,d", Math.round(bet.amount * bet.odds));
            } else {
                resultField += linebreak + "Lost";
            }
        }
        embed.addField("Match id", matchIdField, true);
        embed.addField("Amount", amountField, true);
        embed.addField("Result", resultField, true);

        bot.sendMsg(bot.getLatestMessageChannel(), null, embed);
    }

    public List<Team> getActiveTeams() {
        return activeTeams;
    }

    public PrivateGroup createPrivateGroup(Player p, Gametype gt) {
        if (!playerInPrivateGroup(p)) {
            PrivateGroup pvGroup = new PrivateGroup(p, gt);
            privateGroups.add(pvGroup);
            curMatch.put(pvGroup.gt, null);
            createMatch(pvGroup.gt);
            return pvGroup;
        } else {
            String msg = Config.player_already_group;
            msg = msg.replace(".player.", p.getDiscordUser().username);
            bot.sendMsg(bot.getLatestMessageChannel(), msg);
            return null;
        }
    }

    public boolean playerInPrivateGroup(Player p) {
        for (PrivateGroup pvGroup : privateGroups) {
            if (pvGroup.playerInGroup(p)) {
                return true;
            }
        }
        return false;
    }

    public PrivateGroup getPrivateGroupOwned(Player p) {
        for (PrivateGroup pvGroup : privateGroups) {
            if (pvGroup.captain.equals(p)) {
                return pvGroup;
            }
        }
        return null;
    }

    public PrivateGroup getPrivateGroupMember(Player p) {
        for (PrivateGroup pvGroup : privateGroups) {
            if (pvGroup.playerInGroup(p)) {
                return pvGroup;
            }
        }
        return null;
    }

    public void cmdLeavePrivate(Player p) {
        PrivateGroup pvGroup;
        pvGroup = getPrivateGroupOwned(p);
        if (pvGroup != null) {
            dissolveGroup(pvGroup);
        } else {
            pvGroup = getPrivateGroupMember(p);
            if (pvGroup != null) {
                pvGroup.removePlayer(p);
                String msg = Config.private_left;
                msg = msg.replace(".player.", pvGroup.captain.getUrtauth());
                bot.sendNotice(p.getDiscordUser(), msg);
            }
        }

    }

    public void dissolveGroup(PrivateGroup pvGroup) {
        curMatch.remove(pvGroup.gt);
        privateGroups.remove(pvGroup);
        String msg = Config.private_dissolved;
        msg = msg.replace(".player.", pvGroup.captain.getUrtauth());
        bot.sendMsg(getChannelByType(PickupChannelType.PUBLIC), msg);
    }

    public void cmdShowPrivate() {
        if (privateGroups.isEmpty()) {
            bot.sendMsg(bot.getLatestMessageChannel(), Config.private_none);
            return;
        }
        for (PrivateGroup pvGroup : privateGroups) {
            bot.sendMsg(bot.getLatestMessageChannel(), null, pvGroup.getEmbed());
        }
    }

    public void checkPrivateGroups() {
        if (privateGroups.isEmpty()) {
            return;
        }
        for (PrivateGroup pvGroup : privateGroups) {
            if (Duration.between(pvGroup.timestamp, Instant.now()).toHours() >= 1) {
                dissolveGroup(pvGroup);
                break;
            }
        }
    }

}
