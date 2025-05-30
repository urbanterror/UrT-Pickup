package de.gost0r.pickupbot.pickup;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.pickup.PlayerBan.BanReason;

public class PickupBot extends DiscordBot {
	private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private DiscordChannel latestMessageChannel;

	public static PickupLogic logic;
	public String env;

	@Override
	public void init(String env) {
		super.init(env);
		this.env = env;
	
		logic = new PickupLogic(this);
		createApplicationCommands();
		sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), Config.bot_online);
	}

	@Override
	protected void tick() {
		super.tick();

		if (logic != null) {
			logic.afkCheck();
			logic.checkPrivateGroups();
		}
	}

	@Override
	protected void recvMessage(DiscordMessage msg) {
		LOGGER.info("RECV #" + ((msg.channel == null || msg.channel.name == null) ?  "null" : msg.channel.name) + " " + msg.user.username + ": " + msg.content);

		this.latestMessageChannel = msg.channel;

		if (msg.user.equals(self) || logic == null) {
			return;
		}

		String[] data = msg.content.split(" ");

		if (isChannel(PickupChannelType.PUBLIC, msg.channel))
		{
			Player p = Player.get(msg.user);

			if (p != null) {
				p.afkCheck();
				p.setLastPublicChannel(msg.channel);
			}

			// Execute code according to cmd
			switch (data[0].toLowerCase()) 
			{
				case Config.CMD_ADD:
					if (data.length > 1)
					{
						if (p != null)
						{
							List<Gametype> gametypes = new ArrayList<Gametype>();
							String[] modes = Arrays.copyOfRange(data, 1, data.length);
							for (String mode : modes) {
								Gametype gt = logic.getGametypeByString(mode);
								if (gt != null) {
									gametypes.add(gt);
								}
							}
							if (gametypes.size() > 0) {
								logic.cmdAddPlayer(p, gametypes, false);
							} else {
								sendNotice(msg.user, Config.no_gt_found);
							}
						}
						else sendNotice(msg.user, Config.user_not_registered);
					}
					else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADD));
					break;

				case Config.CMD_TS:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("TS");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);
							
							if (data.length > 1) {
								logic.cmdMapVote(p, gt, data[1], 1);
							}
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_CTF:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("CTF");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);

							if (data.length > 1) {
								logic.cmdMapVote(p, gt, data[1], 1);
							}
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_BM:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("BM");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);

							if (data.length > 1) {
								logic.cmdMapVote(p, gt, data[1], 1);
							}
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_1v1:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("1v1");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);

							if (data.length > 1) {
								logic.cmdMapVote(p, gt, data[1], 1);
							}
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_2v2:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("2v2");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);

							if (data.length > 1) {
								logic.cmdMapVote(p, gt, data[1], 1);
							}
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_DIV1:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("div1");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);

							if (data.length > 1) {
								logic.cmdMapVote(p, gt, data[1], 1);
							}
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_PROCTF:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("proctf");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);

							if (data.length > 1) {
								logic.cmdMapVote(p, gt, data[1], 1);
							}
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_SKEET:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("SKEET");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_AIM:
					if (p != null)
					{
						Gametype gt = logic.getGametypeByString("aim");
						if (gt != null) {
							logic.cmdAddPlayer(p, gt, false);
						} else {
							sendNotice(msg.user, Config.no_gt_found);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_REMOVE:
					Player player = p;
					int startindex = 1;
					if (msg.user.hasAdminRights() && data.length > 1)
					{
						DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
						if (u != null)
						{
							player = Player.get(u);
							if (data.length > 2)
							{
								startindex = 2;
							}
							else
							{
								startindex = -1;
							}
						}
					}

					List<Gametype> gametypes = new ArrayList<Gametype>();
					if (data.length == 1 || startindex == -1) 
					{
						gametypes = null;
					}
					else
					{
						String[] modes = Arrays.copyOfRange(data, startindex, data.length);
						for (String mode : modes) {
							Gametype gt = logic.getGametypeByString(mode);
							if (gt != null) {
								gametypes.add(gt);
							}
						}
					}
					if (player != null){
						logic.cmdRemovePlayer(player, gametypes);
						player.setLastPublicChannel(p.getLastPublicChannel());
					}

					break;

				case Config.CMD_FORCEADD:
					if (!msg.user.hasAdminRights())
					{
						sendNotice(msg.user, Config.player_not_admin);
						return;
					}
					if (data.length >= 3)
					{
						for (int i = 2; i < data.length; i ++) {
							if (data[i].trim().length() == 0) {
								continue;
							}
							DiscordUser u = DiscordUser.getUser(data[i].replaceAll("[^\\d.]", ""));
							Player playerToAdd = null;
							if (u != null)
							{
								playerToAdd = Player.get(u);
							}
							else {
								playerToAdd = Player.get(data[i]);
							}
							if (playerToAdd != null)
							{
								playerToAdd.setLastPublicChannel(p.getLastPublicChannel());
								gametypes = new ArrayList<Gametype>();
								String[] modes = Arrays.copyOfRange(data, 1, data.length);
								for (String mode : modes) {
									Gametype gt = logic.getGametypeByString(mode);
									if (gt != null) {
										gametypes.add(gt);
									}
								}
								if (gametypes.size() > 0) {
									for (Team activeTeam : logic.getActiveTeams()){
										if (activeTeam.isInTeam(playerToAdd)){
											logic.cmdAddTeam(playerToAdd, gametypes.get(0), true);
											return;
										}
									}
									logic.cmdAddPlayer(playerToAdd, gametypes, true);
								} else {
									sendNotice(msg.user, Config.no_gt_found);
								}
							}
							else sendNotice(msg.user, Config.other_user_not_registered.replace(".user.", data[i]));
						}
					}
					else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_FORCEADD));
					break;

				case Config.CMD_MAPS:
					if (p != null)
					{
						if (data.length == 2)
						{
							Gametype gt = logic.getGametypeByString(data[1]);
							logic.cmdGetMapsGt(p, gt);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_MAPS));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_MAP:
					if (p != null)
					{
						if (data.length == 2)
						{
							logic.cmdMapVote(p, null, data[1], 1);
						}
						else if (data.length == 3)
						{
							Gametype gt = logic.getGametypeByString(data[1]);
							logic.cmdMapVote(p, gt, data[2], 1);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_MAP));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_ADDVOTE:
					if (p != null)
					{
						if (data.length == 2)
						{
							logic.cmdMapVote(p, null, data[1], 0);
						}
						else if (data.length == 3)
						{
							Gametype gt = logic.getGametypeByString(data[1]);
							logic.cmdMapVote(p, gt, data[2], 0);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDVOTE));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_BANMAP:
					if (p != null)
					{
						if (data.length == 2)
						{
							logic.cmdUseMapBan(p, data[1]);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_BANMAP));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_STATUS:
					if (data.length == 1)
					{
						logic.cmdStatus();
					}
					else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_STATUS));
					break;

				case Config.CMD_VOTES:
					if (data.length == 1)
					{
						logic.cmdGetMaps(p, false);
					}
					else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_VOTES));
					break;

				case Config.CMD_SURRENDER:
					if (data.length == 1)
					{
						if (p != null)
						{
							logic.cmdSurrender(p);
						}
					}
					else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SURRENDER));
					break;

				case Config.CMD_RESET:
					if (msg.user.hasAdminRights())
					{
						if (data.length == 1)
						{
							logic.cmdReset("all");
						}
						else if (data.length == 2)
						{
							logic.cmdReset(data[1]);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_RESET));
					}
					break;

				case Config.CMD_LOCK:
					if (msg.user.hasAdminRights())
					{
						if (data.length == 1)
						{
							logic.cmdLock();
						}
						else super.sendMsg(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LOCK));
					}
					break;

				case Config.CMD_UNLOCK:
					if (msg.user.hasAdminRights())
					{
						if (data.length == 1)
						{
							logic.cmdUnlock();
						}
						else super.sendMsg(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_UNLOCK));
					}
					break;

				case Config.CMD_GETELO:
					if (p != null)
					{
						if (data.length == 1)
						{
							logic.cmdGetStats(p);
						}
						else if (data.length == 2)
						{
							Player pOther;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								pOther = Player.get(u);
							}
							else
							{
								pOther = Player.get(data[1].toLowerCase());
							}
							
							if (pOther != null)
							{
								logic.cmdGetStats(pOther);
							}
							else sendNotice(msg.user, Config.player_not_found);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_GETELO));	
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_GETSTATS:
					if (p != null)
					{
						if (data.length == 1)
						{
							logic.cmdGetStats(p);
						}
						else if (data.length == 2)
						{
							Player pOther;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								pOther = Player.get(u);
							}
							else
							{
								pOther = Player.get(data[1].toLowerCase());
							}
							if (pOther != null)
							{
								logic.cmdGetStats(pOther);
							}
							else sendNotice(msg.user, Config.player_not_found);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_GETSTATS));	
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_TOP_PLAYERS: 
					if (p != null)
					{
						if (data.length == 1)
						{
							logic.cmdTopElo(10);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP10));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_TOP_COUNTRIES: 
					if (p != null)
					{
						if (data.length == 1)
						{
							logic.cmdTopCountries(5);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP10));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_TOP_WDL: 
					if (p != null)
					{
						if (data.length == 2)
						{
							Gametype gt = logic.getGametypeByString(data[1]);
							if (gt != null) {
								logic.cmdTopWDL(10, gt);
							}
							else {
								sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_WDL));
							}
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_WDL));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_TOP:
				case Config.CMD_TOP_KDR: 
					if (p != null)
					{
						if (data.length == 2)
						{
							Gametype gt = logic.getGametypeByString(data[1]);
							if (gt != null) {
								logic.cmdTopKDR(10, gt);
							}
							else {
								sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_KDR));
							}
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_KDR));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;	

				case Config.CMD_REGISTER:
					if (data.length == 2)
					{
						logic.cmdRegisterPlayer(msg.user, data[1].toLowerCase(), msg.id);
					}
					else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_REGISTER));
					break;

				case Config.CMD_COUNTRY:
					if (data.length == 2)
					{
						logic.cmdSetPlayerCountry(msg.user, data[1].toUpperCase());
					}
					else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_COUNTRY));
					break;

				case Config.CMD_LIVE:
					if (p != null)
					{
						if (data.length == 1)
						{
							logic.cmdLive(msg.channel);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LIVE));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_MATCH:
					if (p != null)
					{
						if (data.length == 2)
						{
							logic.cmdDisplayMatch(data[1]);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_MATCH));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_LAST:
					if (p != null)
					{
						if (data.length == 1)
						{
							logic.cmdDisplayLastMatch();
						}
						else if (data.length == 2)
						{
							Player pOther;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								pOther = Player.get(u);
							}
							else
							{
								pOther = Player.get(data[1].toLowerCase());
							}
							
							if (pOther != null)
							{
								logic.cmdDisplayLastMatchPlayer(pOther);
							}
							else sendNotice(msg.user, Config.player_not_found);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LAST));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_BANINFO:
					if (p != null)
					{
						if (data.length == 1)
						{
							sendMsg(msg.channel, logic.printBanInfo(p));
						}
						else if (data.length == 2)
						{
							Player pOther;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								pOther = Player.get(u);
							}
							else
							{
								pOther = Player.get(data[1].toLowerCase());
							}
							
							if (pOther != null)
							{
								sendMsg(msg.channel, logic.printBanInfo(pOther));
							}
							else sendNotice(msg.user, Config.player_not_found);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_BANINFO));	
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;

				case Config.CMD_TEAM:
					if (p != null) {
						if (data.length >= 2) {
							List<Player> invitedPlayers = new ArrayList<Player>();
							boolean noMentions = false;
							for (int i = 1; i < data.length; i++) {
								if (data[i].trim().length() == 0) {
									continue;
								}
								DiscordUser u = DiscordUser.getUser(data[i].replaceAll("[^\\d.]", ""));
								Player playerToInvite = null;
								if (u != null) {
									playerToInvite = Player.get(u);
								}
								else {
									noMentions = true;
									continue;
								}
								if (playerToInvite != null) {
									invitedPlayers.add(playerToInvite);
								}
								else{
									logic.bot.sendNotice(p.getDiscordUser(), Config.other_user_not_registered.replace(".user.", u.getMentionString()));
								}
							}
							if (noMentions){
								logic.bot.sendNotice(p.getDiscordUser(), Config.team_only_mentions);
							}
							if (!invitedPlayers.isEmpty()){
								logic.invitePlayersToTeam(p, invitedPlayers);
							}
						}
						else{
							logic.cmdPrintTeam(p);
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_LEAVETEAM:
					if (p != null){
						logic.leaveTeam(p);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_SCRIM:
					if (p != null){
						if (data.length == 2) {
							Gametype gt;
							if (data[1].equalsIgnoreCase("2V2")){
								gt = logic.getGametypeByString("2V2");
							}
							else gt = logic.getGametypeByString( "SCRIM " + data[1]);

							if (gt == null || !gt.isTeamGamemode()) {
								sendNotice(msg.user, Config.team_error_wrong_gt);
								return;
							}

							logic.cmdAddTeam(p, gt, false);
						}
						else {
							sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SCRIM));
						}
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_REMOVETEAM:
					if (p != null){
						logic.cmdRemoveTeam(p, true);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_TEAMS:
					if (p != null){
						logic.cmdPrintTeams(p);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_PING:
					if (p != null){
						logic.cmdGetPingURL(p);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_TOP_RICH:
					if (p != null){
						logic.cmdTopRich(10);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_WALLET:
					if (p != null){
						if (data.length == 1){
							logic.cmdWallet(p);
						}

						else if (data.length == 2)
						{
							Player pOther;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								pOther = Player.get(u);
							}
							else
							{
								pOther = Player.get(data[1].toLowerCase());
							}

							if (pOther != null)
							{
								logic.cmdWallet(pOther);
							}
							else sendNotice(msg.user, Config.player_not_found);
						}

					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_DONATE:
					if (p != null){
						if (data.length == 3){

							Player pOther;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								pOther = Player.get(u);
							}
							else
							{
								pOther = Player.get(data[1].toLowerCase());
							}

							if (pOther != null)
							{
								int amount;
								try {
									amount = Integer.parseInt(data[2]);
									pOther.setLastPublicChannel(msg.channel);
									logic.cmdDonate(p, pOther, amount);
								} catch (NumberFormatException nfe) {
									sendNotice(p.getDiscordUser(), Config.donate_incorrect_amount);
								}
							}
							else sendNotice(msg.user, Config.player_not_found);
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_DONATE));

					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_BETHISTORY:
					if (p != null){
						if (data.length == 1){
							logic.cmdBetHistory(p);
						}

						else if (data.length == 2)
						{
							Player pOther;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								pOther = Player.get(u);
							}
							else
							{
								pOther = Player.get(data[1].toLowerCase());
							}

							if (pOther != null)
							{
								logic.cmdBetHistory(pOther);
							}
							else sendNotice(msg.user, Config.player_not_found);
						}

					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_CREATE_PRIVATE:
					if (p != null){
						if (data.length >= 2)
						{
							Gametype gt = logic.getGametypeByString(data[1]);
							if (gt == null) {
								sendNotice(msg.user, Config.no_gt_found);
							} else {
								PrivateGroup pvGroup = logic.createPrivateGroup(p, gt);

								if (pvGroup != null) {
									if (data.length >= 3) {
										String[] players = Arrays.copyOfRange(data, 2, data.length);
										for (String newP : players) {
											Player pOther;
											DiscordUser u = DiscordUser.getUser(newP.replaceAll("[^\\d.]", ""));
											if (u != null)
											{
												pOther = Player.get(u);
											}
											else
											{
												pOther = Player.get(newP.toLowerCase());
											}

											if (pOther != null)
											{
												pvGroup.addPlayer(pOther);
											}
											else sendNotice(msg.user, Config.player_not_found);
										}
									}
									sendNotice(msg.user, Config.player_create_group, pvGroup.getEmbed());
								}

							}
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_CREATE_PRIVATE));
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_ADD_PLAYER_PRIVATE:
					if (p != null)
					{
						PrivateGroup pvGroup = logic.getPrivateGroupOwned(p);
						if (pvGroup != null) {
							if (data.length >= 2)
							{
								String[] players = Arrays.copyOfRange(data, 1, data.length);
								boolean changesMade = false;
								for (String newP : players) {
									Player pOther;
									DiscordUser u = DiscordUser.getUser(newP.replaceAll("[^\\d.]", ""));
									if (u != null)
									{
										pOther = Player.get(u);
									}
									else
									{
										pOther = Player.get(newP.toLowerCase());
									}

									if (pOther != null)
									{
										if (!logic.playerInPrivateGroup(pOther)){
											pvGroup.addPlayer(pOther);
											changesMade = true;
										}

										else {
											String newMsg = Config.player_already_group;
											newMsg = newMsg.replace(".player.", pOther.getUrtauth());
											sendNotice(p.getDiscordUser(), newMsg);
										}

									}
									else sendNotice(msg.user, Config.player_not_found);
								}
								if (changesMade) {
									sendNotice(msg.user, Config.player_added_group);
								}
							}
							else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADD_PLAYER_PRIVATE));
						}
						else sendNotice(msg.user, Config.player_no_owned_group);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_REMOVE_PLAYER_PRIVATE:
					if (p != null)
					{
						PrivateGroup pvGroup = logic.getPrivateGroupOwned(p);
						if (pvGroup != null) {
							if (data.length >= 2)
							{
								String[] players = Arrays.copyOfRange(data, 1, data.length);
								boolean changesMade = false;
								for (String newP : players) {
									Player pOther;
									DiscordUser u = DiscordUser.getUser(newP.replaceAll("[^\\d.]", ""));
									if (u != null)
									{
										pOther = Player.get(u);
									}
									else
									{
										pOther = Player.get(newP.toLowerCase());
									}

									if (pOther != null)
									{
										if (pOther.equals(p)) {
											logic.dissolveGroup(pvGroup);
										} else {
											pvGroup.removePlayer(pOther);
											changesMade = true;
										}
									}
									else sendNotice(msg.user, Config.player_not_found);
								}
								if (changesMade) {
									sendNotice(msg.user, Config.player_removed_group);
								}
							}
							else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_REMOVE_PLAYER_PRIVATE));
						}
						else sendNotice(msg.user, Config.player_no_owned_group);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_LEAVE_PRIVATE:
					if (p != null){
						if (logic.playerInPrivateGroup(p)) {
							logic.cmdLeavePrivate(p);
						}
						else sendNotice(msg.user, Config.player_no_group);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_PRIVATE:
					if (p != null){
						if (logic.playerInPrivateGroup(p)) {
							PrivateGroup pvGroup = logic.getPrivateGroupMember(p);
							logic.cmdAddPlayer(p, pvGroup.gt, false);
							pvGroup.updateTimestamp();

							if (data.length > 1) {
								logic.cmdMapVote(p, pvGroup.gt, data[1], 1);
							}
						}
						else sendNotice(msg.user, Config.player_no_group);
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
				case Config.CMD_SHOW_PRIVATE:
					if (p != null){
						logic.cmdShowPrivate();
					}
					else sendNotice(msg.user, Config.user_not_registered);
					break;
			}
		}

		if (msg.channel.isThread)
		{
			Player p = Player.get(msg.user);

			// AFK CHECK CODE
			if (p != null) {
				p.afkCheck();
			}
		}

		// use admin channel or DM for super admins
		if (isChannel(PickupChannelType.ADMIN, msg.channel)
				|| msg.channel.type == DiscordChannelType.DM && msg.user.hasSuperAdminRights())
		{
			if (msg.user.hasAdminRights())
			{
				// Execute code according to cmd
				switch (data[0].toLowerCase())
				{
					case Config.CMD_REBOOT:
						try {
							super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							logic.restartApplication();
						} catch (URISyntaxException | IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						break;

					case Config.CMD_GETDATA:
						if (data.length == 1)
						{
							logic.cmdGetData(msg.channel);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_GETDATA));
						break;

					case Config.CMD_ENABLEMAP:
						if (data.length == 3)
						{
							if (logic.cmdEnableMap(data[1], data[2]))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLEMAP));
						break;

					case Config.CMD_DISABLEMAP:
						if (data.length == 3)
						{
							if (logic.cmdDisableMap(data[1], data[2]))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_DISABLEMAP));
						break;

					case Config.CMD_ENABLEGAMETYPE:
						if (data.length == 3)
						{
							if (logic.cmdEnableGametype(data[1], data[2]))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLEGAMETYPE));	
						break;

					case Config.CMD_DISABLEGAMETYPE:
						if (data.length == 2)
						{
							if (logic.cmdDisableGametype(data[1]))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLEGAMETYPE));
						break;

					case Config.CMD_LISTGAMECONFIG:
						if (data.length == 2)
						{
							if (!logic.cmdListGameConfig(msg.channel, data[1]))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
							}
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LISTGAMECONFIG));
						break;

					case Config.CMD_ADDSERVER:
						if (data.length == 4)
						{
							if (logic.cmdAddServer(data[1], data[2], data[3]))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDSERVER));
						break;

					case Config.CMD_ENABLESERVER:
						if (data.length == 2)
						{
							if (logic.cmdServerActivation(data[1], true))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLESERVER));
						break;

					case Config.CMD_DISABLESERVER:
						if (data.length == 2)
						{
							if (logic.cmdServerActivation(data[1], false))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_DISABLESERVER));
						break;

					case Config.CMD_UPDATESERVER:
						if (data.length == 3)
						{
							if (logic.cmdServerChangeRcon(data[1], data[2]))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_UPDATESERVER));
						break;

					case Config.CMD_SHOWSERVERS:
						if (data.length == 1)
						{
							super.sendMsg(msg.channel, Config.wait_testing_server);
							logic.cmdServerList(msg.channel);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SHOWSERVERS));
						break;

					case Config.CMD_RCON:
						if (data.length > 2)
						{
							if (logic.cmdServerSendRcon(data[1], msg.content.substring(Config.CMD_RCON.length() + data[1].length() + 2)))
							{
								super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
							}
							else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_RCON));
						break;

					case Config.CMD_SHOWMATCHES:
						if (data.length == 1)
						{
							logic.cmdMatchList(msg.channel);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SHOWMATCHES));
						break;

					case Config.CMD_UNREGISTER:
						if (data.length == 2)
						{
							Player player = Player.get(data[1].toLowerCase());
							if (player != null)
							{
								if (logic.cmdUnregisterPlayer(player))
								{
									super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
								}
								else super.sendMsg(msg.channel, Config.admin_cmd_unsuccessful + msg.content);
							}
							else sendMsg(msg.channel, Config.player_not_found);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_UNREGISTER));
						break;

					case Config.CMD_ENFORCEAC:
						if (data.length == 2)
						{
							Player player = Player.get(data[1].toLowerCase());
							if (player != null)
							{
								if (logic.cmdEnforcePlayerAC(player))
								{
									super.sendMsg(msg.channel, Config.admin_enforce_ac_on.replace(".urtauth.", player.getUrtauth()));
								}
								else super.sendMsg(msg.channel, Config.admin_enforce_ac_off.replace(".urtauth.", player.getUrtauth()));
							}
							else sendMsg(msg.channel, Config.player_not_found);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENFORCEAC));
						break;

					case Config.CMD_SETPROCTF:
						if (data.length >= 2)
						{
							for (int i = 1; i < data.length; i ++) {
								Player player = Player.get(data[i].toLowerCase());
								if (player != null)
								{
									if (logic.cmdSetProctf(player))
									{
										super.sendMsg(msg.channel, Config.admin_proctf_on.replace(".urtauth.", player.getUrtauth()));
									}
									else super.sendMsg(msg.channel, Config.admin_proctf_off.replace(".urtauth.", player.getUrtauth()));
								}
								else sendMsg(msg.channel, Config.player_not_found);
							}
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SETPROCTF));
						break;

					case Config.CMD_ADDBAN:
						if (data.length == 4)
						{
							Player p;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								p = Player.get(u);
							}
							else
							{
								p = Player.get(data[1].toLowerCase());
							}
							
							if (p != null)
							{
								BanReason reason = null;
								for (BanReason banReason : BanReason.values()) {
									if (banReason.name().equals(data[2].toUpperCase())) {
										reason = banReason;
										break;
									}
								}
								if (reason != null) {
									long duration = PickupLogic.parseDurationFromString(data[3]);
									if (duration > 0L) {
										logic.banPlayer(p, reason, duration);
										// no need to send msg due to banmsg being sent in that case
									}
									else sendMsg(msg.channel, Config.banduration_invalid);
								}
								else sendMsg(msg.channel, Config.banreason_not_found.replace(".banreasons.", Arrays.toString(BanReason.values())));
							}
							else sendMsg(msg.channel, Config.player_not_found);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDBAN));
						break;

					case Config.CMD_REMOVEBAN:
						if (data.length == 2)
						{
							Player p;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								p = Player.get(u);
							}
							else
							{
								p = Player.get(data[1].toLowerCase());
							}
							
							if (p != null)
							{
								logic.UnbanPlayer(p);
							}
							else sendMsg(msg.channel, Config.player_not_found);
						}
						else super.sendMsg(msg.channel, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDBAN));
						break;

					case Config.CMD_COUNTRY:
						if (data.length == 3)
						{
							Player p;
							DiscordUser u = DiscordUser.getUser(data[1].replaceAll("[^\\d.]", ""));
							if (u != null)
							{
								p = Player.get(u);
							}
							else
							{
								p = Player.get(data[1].toLowerCase());
							}
							
							if (p != null)
							{
								logic.cmdChangePlayerCountry(p, data[2].toUpperCase());
							}
							else sendMsg(msg.channel, Config.player_not_found);
							
						}
						else sendNotice(msg.user, Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_CHANGE_COUNTRY));
						break;

					case Config.CMD_RESETELO:
						logic.cmdResetElo();
						sendNotice(msg.user, Config.elo_reset);
						break;

					case Config.CMD_ENABLEDYNSERVER:
						logic.cmdEnableDynamicServer();
						super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
						break;

					case Config.CMD_DISABLEDYNSERVER:
						logic.cmdDisableDynamicServer();
						super.sendMsg(msg.channel, Config.admin_cmd_successful + msg.content);
						break;
				}
			}
		}

		if (isChannel(PickupChannelType.PUBLIC, msg.channel)
				|| isChannel(PickupChannelType.ADMIN, msg.channel)
				|| msg.channel.type == DiscordChannelType.DM)
		{
			switch (data[0].toLowerCase()) {
				case Config.CMD_HELP:
					if (data.length == 2) {
						String cmd = (!data[1].startsWith("!") ? "!" : "") + data[1];
						switch (cmd) {
							case Config.CMD_ADD:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADD));
								break;
							case Config.CMD_REMOVE:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVE));
								break;
							case Config.CMD_MAPS:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_MAPS));
								break;
							case Config.CMD_MAP:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_MAP));
								break;
							case Config.CMD_STATUS:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_STATUS));
								break;
							case Config.CMD_HELP:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_HELP));
								break;
							case Config.CMD_LOCK:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_LOCK));
								break;
							case Config.CMD_UNLOCK:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_UNLOCK));
								break;
							case Config.CMD_RESET:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_RESET));
								break;
							case Config.CMD_GETDATA:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_GETDATA));
								break;
							case Config.CMD_ENABLEMAP:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ENABLEMAP));
								break;
							case Config.CMD_DISABLEMAP:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_DISABLEMAP));
								break;
							case Config.CMD_RCON:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_RCON));
								break;
							case Config.CMD_REGISTER:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_REGISTER));
								break;
							case Config.CMD_GETELO:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_GETELO));
								break;
							case Config.CMD_GETSTATS:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_GETSTATS));
								break;
							case Config.CMD_TOP_PLAYERS:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP10));
								break;
							case Config.CMD_TOP_COUNTRIES:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP_COUNTRIES));
								break;
							case Config.CMD_MATCH:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_MATCH));
								break;
							case Config.CMD_SURRENDER:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_SURRENDER));
								break;
							case Config.CMD_LIVE:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_LIVE));
								break;
							case Config.CMD_ADDBAN:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDBAN));
								break;
							case Config.CMD_REMOVEBAN:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVEBAN));
								break;
							case Config.CMD_BANINFO:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_BANINFO));
								break;
							case Config.CMD_SHOWSERVERS:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_SHOWSERVERS));
								break;
							case Config.CMD_ADDSERVER:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDSERVER));
								break;
							case Config.CMD_ENABLESERVER:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ENABLESERVER));
								break;
							case Config.CMD_DISABLESERVER:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_DISABLESERVER));
								break;
							case Config.CMD_UPDATESERVER:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_UPDATESERVER));
								break;
							case Config.CMD_ENABLEGAMETYPE:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ENABLEGAMETYPE));
								break;
							case Config.CMD_DISABLEGAMETYPE:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_DISABLEGAMETYPE));
								break;
							case Config.CMD_ADDCHANNEL:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDCHANNEL));
								break;
							case Config.CMD_REMOVECHANNEL:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVECHANNEL));
								break;
							case Config.CMD_ADDROLE:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDROLE));
								break;
							case Config.CMD_REMOVEROLE:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVEROLE));
								break;
							case Config.CMD_SHOWMATCHES:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_SHOWMATCHES));
								break;
							case Config.CMD_LISTGAMECONFIG:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_LISTGAMECONFIG));
								break;
							case Config.CMD_COUNTRY:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_COUNTRY));
								break;
							case Config.CMD_BM:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_BM));
								break;
							case Config.CMD_CTF:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_CTF));
								break;
							case Config.CMD_TS:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_TS));
								break;
							case Config.CMD_DIV1:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_DIV1));
								break;
							case Config.CMD_PROCTF:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_PROCTF));
								break;
							case Config.CMD_VOTES:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_VOTES));
								break;
							case Config.CMD_TOP_WDL:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP_WDL));
								break;
							case Config.CMD_TOP_KDR:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP_KDR));
								break;
							case Config.CMD_LAST:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_LAST));
								break;
							case Config.CMD_SCRIM:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_SCRIM));
								break;
							case Config.CMD_REMOVETEAM:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVETEAM));
								break;
							case Config.CMD_TEAM:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_TEAM));
								break;
							case Config.CMD_LEAVETEAM:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_LEAVETEAM));
								break;
							case Config.CMD_TEAMS:
								super.sendMsg(msg.channel, Config.help_prefix.replace(".cmd.", Config.USE_CMD_TEAMS));
								break;								
								
							default:
								super.sendMsg(msg.channel, Config.help_unknown);
								break;
						}
					} else {
						if (isChannel(PickupChannelType.PUBLIC, msg.channel)) {
							super.sendMsg(msg.channel, Config.help_cmd_avi.replace(".cmds.", Config.PUB_LIST));
						}
						if (msg.user.hasAdminRights() && (isChannel(PickupChannelType.ADMIN, msg.channel) || msg.channel.type == DiscordChannelType.DM)) {
							super.sendMsg(msg.channel, Config.help_cmd_avi.replace(".cmds.", Config.ADMIN_LIST));
						}
					}
					break;

				case Config.CMD_ADDCHANNEL:
					if (msg.user.hasSuperAdminRights()) {
						if (data.length == 3) {
							DiscordChannel targetChannel = DiscordChannel.findChannel(data[1].replaceAll("[^\\d.]", ""));
							if (targetChannel != null) {
								try {
									PickupChannelType type = PickupChannelType.valueOf(data[2].toUpperCase());
									if (!logic.getChannelByType(type).contains(targetChannel)) {
										if (logic.addChannel(type, targetChannel)) {
											sendNotice(msg.user, "successfully added the channel");
										} else sendNotice(msg.user, "unsuccessfully added the channel");
									}
								} catch (IllegalArgumentException e) {
									sendNotice(msg.user, "unknown channel type");
								}
							} else sendNotice(msg.user, "invalid channel");
						} else sendNotice(msg.user, "invalid options");
					} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
					break;

				case Config.CMD_REMOVECHANNEL:
					if (msg.user.hasSuperAdminRights()) {
						if (data.length == 3) {
							DiscordChannel targetChannel = DiscordChannel.findChannel(data[1].replaceAll("[^\\d.]", ""));
							if (targetChannel != null) {
								try {
									PickupChannelType type = PickupChannelType.valueOf(data[2].toUpperCase());
									if (logic.getChannelByType(type).contains(targetChannel)) {
										if (logic.removeChannel(type, targetChannel)) {
											sendNotice(msg.user, "successfully removed the channel.");
										} else sendNotice(msg.user, "unsuccessfully removed the channel.");
									}

								} catch (IllegalArgumentException e) {
									sendNotice(msg.user, "unknown role type");
								}
							} else sendNotice(msg.user, "invalid channel");
						} else sendNotice(msg.user, "invalid options");
					} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
					break;

				case Config.CMD_ADDROLE:
					if (msg.user.hasSuperAdminRights()) {
						if (data.length == 3) {
							DiscordRole targetRole = DiscordRole.getRole(data[1].replaceAll("[^\\d.]", ""));
							if (targetRole != null) {
								try {
									PickupRoleType type = PickupRoleType.valueOf(data[2].toUpperCase());
									if (!logic.getRoleByType(type).contains(targetRole)) {
										if (logic.addRole(type, targetRole)) {
											sendNotice(msg.user, "successfully added the role.");
										} else sendNotice(msg.user, "unsuccessfully removed the role.");
									}

								} catch (IllegalArgumentException e) {
									sendNotice(msg.user, "unknown role type");
								}
							} else sendNotice(msg.user, "invalid channel");
						} else sendNotice(msg.user, "invalid options");
					} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
					break;

				case Config.CMD_REMOVEROLE:
					if (msg.user.hasSuperAdminRights()) {
						if (data.length == 3) {
							DiscordRole targetRole = DiscordRole.getRole(data[1].replaceAll("[^\\d.]", ""));
							if (targetRole != null) {
								try {
									PickupRoleType type = PickupRoleType.valueOf(data[2].toUpperCase());
									if (logic.getRoleByType(type).contains(targetRole)) {
										if (logic.removeRole(type, targetRole)) {
											sendNotice(msg.user, "successfully removed the role.");
										} else sendNotice(msg.user, "unsuccessfully removed the role.");
									}
								} catch (IllegalArgumentException e) {
									sendNotice(msg.user, "unknown role type");
								}
							} else sendNotice(msg.user, "invalid role");
						} else sendNotice(msg.user, "invalid options");
					} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
					break;


				case Config.CMD_SHOWROLES:
					if (msg.user.hasSuperAdminRights()) {
						DiscordUser u = msg.user;
						if (data.length == 2)
						{
							DiscordUser testUser = super.parseMention(data[1]);
							if (testUser != null)
							{
								u = testUser;
							}
						}
						List<DiscordRole> list = u.getRoles(DiscordBot.getGuilds());
						StringBuilder message = new StringBuilder();
						for (DiscordRole role : list)
						{
							if (msg.channel.type == DiscordChannelType.DM){
								message.append(role.getMentionString()).append(" ");
							}
							else{
								message.append(role.getMentionString()).append(" ");
							}
						}
						sendNotice(u, message.toString());
					} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
					break;


				case Config.CMD_SHOWKNOWNROLES:
					if (msg.user.hasSuperAdminRights()) {
						StringBuilder message = new StringBuilder("Roles: ");
						for (PickupRoleType type : logic.getRoleTypes()) {
							message.append("\n**").append(type.name()).append("**:");

							for (DiscordRole role : logic.getRoleByType(type))
							{
								if (msg.channel.type == DiscordChannelType.DM){
									message.append(role.getMentionString() + " (``" + role.id + "``)").append(" ");
								}
								else{
									message.append(role.getMentionString()).append(" ");
								}
							}
						}
						sendMsg(msg.channel, message.toString());
					} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
				break;

				case Config.CMD_SHOWKNOWNCHANNELS:
					if (msg.user.hasSuperAdminRights()) {
						StringBuilder message = new StringBuilder("Channels: ");
						for (PickupChannelType type : logic.getChannelTypes()) {
							message.append("\n**").append(type.name()).append("**:");

							for (DiscordChannel channel : logic.getChannelByType(type))
							{
								message.append(" ").append(channel.getMentionString()).append(" ");
							}
						}
						sendMsg(msg.channel, message.toString());
					} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
				break;

				case Config.CMD_GODROLE:
				if (msg.user.hasSuperAdminRights()) {
					if (logic.getRoleByType(PickupRoleType.SUPERADMIN).isEmpty()) {
						if (data.length == 2) {
							DiscordRole role = DiscordRole.getRole(data[1].replaceAll("[^\\d.]", ""));
							if (role != null) {
								logic.addRole(PickupRoleType.SUPERADMIN, role);
								sendNotice(msg.user, "*" + role.getMentionString() + " set as SUPERADMIN role*");
							}
						}
					} else sendNotice(msg.user, "A DiscordRole is already set as SUPERADMIN, check the DB.");
				} else sendNotice(msg.user, "You need SuperAdmin rights to use this.");
				break;
			}
		}
	}

	@Override
	protected void recvInteraction(DiscordInteraction interaction) {
		LOGGER.info("RECV #" + ((interaction.message.channel == null || interaction.message.channel.name == null) ?  "null" : interaction.message.channel.name) + " " + interaction.user.username + ": " + interaction.custom_id);

		Player p = Player.get(interaction.user);
		if (p == null) {
			interaction.respond(Config.user_not_registered);
			return;
		}

		if (interaction.message != null && interaction.message.channel != null && isChannel(PickupChannelType.PUBLIC, interaction.message.channel)){
			p.setLastPublicChannel(interaction.message.channel);
		}

		String[] data = interaction.custom_id.split("_");

		switch (data[0].toLowerCase()) 
		{
		case Config.INT_PICK:
			logic.cmdPick(interaction, p, Integer.parseInt(data[1]));
			break;

		case Config.INT_LAUNCHAC:
			logic.cmdLaunchAC(interaction, p, Integer.parseInt(data[1]), data[2], data[3]);
			break;

		case Config.INT_TEAMINVITE:
			logic.answerTeamInvite(interaction, p, Integer.parseInt(data[1]), Player.get(data[2]), Player.get(data[3]));
			break;

		case Config.INT_TEAMREMOVE:
			logic.removeTeamMember(interaction, p, Player.get(data[1]), Player.get(data[2]));
			break;

		case Config.INT_SEASONSTATS:
			logic.showSeasonStats(interaction, Player.get(data[1]), Integer.parseInt(data[2]));
			break;

		case Config.INT_SEASONLIST:
			logic.showSeasonList(interaction, Player.get(data[1]));
			break;

		case Config.INT_LASTMATCHPLAYER:
			logic.showLastMatchPlayer(interaction, Player.get(data[1]));
			break;

		case Config.INT_SEASONSELECTED:
			logic.showSeasonStats(interaction, Player.get(data[1]), Integer.parseInt(interaction.values.get(0)));
			break;

		case Config.INT_SHOWBET:
			logic.showBets(interaction, Integer.parseInt(data[2]), data[1], p);
			break;

		case Config.INT_BET:
			logic.bet(interaction, Integer.parseInt(data[1]), data[2], Integer.parseInt(data[3]), p);
			break;

//		case Config.INT_BUY:
//			switch(data[1]){
//				case Config.INT_BUY_BOOST:
//					logic.buyBoost(interaction, p);
//					break;
//				case Config.INT_BUY_SHOWVOTEOPTIONS:
//					logic.showAdditionalVoteOptions(interaction, p);
//					break;
//				case Config.INT_BUY_ADDVOTES:
//					logic.buyAdditionalVotes(interaction, p, Integer.parseInt(data[2]));
//					break;
//				case Config.INT_BUY_MAPBAN:
//					logic.buyBanMap(interaction, p);
//					break;
//			}
//			break;
		}
	}

	@Override
	protected void recvApplicationCommand(DiscordInteraction interaction) {
		LOGGER.info("RECV #" + interaction.name + " " + interaction.user.username);

		Player p = Player.get(interaction.user);
		if (p == null) {
			interaction.respond(Config.user_not_registered);
			return;
		}

		switch (interaction.name)
		{
		case Config.APP_BET:
			logic.bet(interaction, Integer.parseInt(interaction.options.get(0).value), interaction.options.get(1).value, Integer.parseInt(interaction.options.get(2).value), p);
			break;

		case Config.APP_PARDON:
			Player pPardon = Player.get(DiscordUser.getUser(interaction.options.get(0).value));
			if (pPardon == null) {
				interaction.respond(Config.player_not_found);
				return;
			}
			logic.pardonPlayer(interaction, pPardon, interaction.options.get(1).value, p);
			break;

//		case Config.APP_BUY:
//			logic.showBuys(interaction, p);
//			break;
		}
	}

	public boolean isChannel(PickupChannelType type, DiscordChannel channel) {
		return logic.getChannelByType(type).contains(channel);
	}



	public void sendNotice(DiscordUser user, String msg) {
		sendMsg(getLatestMessageChannel(), user.getMentionString() + " " + msg);
	}

	public void sendNotice(DiscordUser user, String msg, DiscordEmbed embed) {
		sendMsg(getLatestMessageChannel(), user.getMentionString() + " " + msg, embed);
	}

	public void sendMsg(List<DiscordChannel> channelList, String msg) {
		List<Player> mentionedPlayers = new ArrayList<Player>();
		Matcher m = Pattern.compile("<@(.*?)>").matcher(msg);
		while (m.find()) {
			DiscordUser dsUser = DiscordUser.getUser(m.group(1));
			Player playerMentioned = Player.get(dsUser);
			if (dsUser != null){
				mentionedPlayers.add(playerMentioned);
			}
		}

		for (DiscordChannel channel : channelList) {
			String msgCopy = String.valueOf(msg);
			for (Player p : mentionedPlayers){
				if ((p.getLastPublicChannel() != null && !channel.isThread && !channel.id.equals(p.getLastPublicChannel().id)) ||
					((p.getLastPublicChannel() != null && channel.isThread && channel.parent_id != null && !channel.parent_id.equals(p.getLastPublicChannel().id)))
				){
					msgCopy = msgCopy.replace(
							"<@" + p.getDiscordUser().id + ">",
							"**" + p.getDiscordUser().username + "**"
					);
				}
			}
			sendMsg(channel, msgCopy);
		}
	}
	
	public void sendMsg(List<DiscordChannel> channelList, String msg, DiscordEmbed embed) {
		if (msg == null){
			msg = "";
		}

		List<Player> mentionedPlayers = new ArrayList<Player>();
		Matcher m = Pattern.compile("<@(.*?)>").matcher(msg);
		while (m.find()) {
			DiscordUser dsUser = DiscordUser.getUser(m.group(1));
			Player playerMentioned = Player.get(dsUser);
			if (dsUser != null){
				mentionedPlayers.add(playerMentioned);
			}
		}

		for (DiscordChannel channel : channelList) {
			String msgCopy = String.valueOf(msg);
			for (Player p : mentionedPlayers){
				if ((p.getLastPublicChannel() != null && !channel.isThread &&  !channel.id.equals(p.getLastPublicChannel().id)) ||
					((p.getLastPublicChannel() != null && channel.isThread && channel.parent_id != null && !channel.parent_id.equals(p.getLastPublicChannel().id)))
				){
					msgCopy = msgCopy.replace(
							"<@" + p.getDiscordUser().id + ">",
							"**" + p.getDiscordUser().username + "**"
					);
				}
			}
			sendMsg(channel, msgCopy, embed);
		}
	}

	public List<DiscordMessage> sendMsgToEdit(List<DiscordChannel> channelList, String msg, DiscordEmbed embed, List<DiscordComponent> components) {
		if (msg == null){
			msg = "";
		}

		List<Player> mentionedPlayers = new ArrayList<Player>();
		List<DiscordMessage> sentMessages = new ArrayList<DiscordMessage>();
		Matcher m = Pattern.compile("<@(.*?)>").matcher(msg);
		while (m.find()) {
			DiscordUser dsUser = DiscordUser.getUser(m.group(1));
			Player playerMentioned = Player.get(dsUser);
			if (dsUser != null){
				mentionedPlayers.add(playerMentioned);
			}
		}

		for (DiscordChannel channel : channelList) {
			String msgCopy = String.valueOf(msg);
			for (Player p : mentionedPlayers){
				if ((p.getLastPublicChannel() != null && !channel.isThread &&  !channel.id.equals(p.getLastPublicChannel().id)) ||
					((p.getLastPublicChannel() != null && channel.isThread && channel.parent_id != null && !channel.parent_id.equals(p.getLastPublicChannel().id)))
				){
					msgCopy = msgCopy.replace(
							"<@" + p.getDiscordUser().id + ">",
							"**" + p.getDiscordUser().username + "**"
					);
				}
			}
			sentMessages.add(sendMsgToEdit(channel, msgCopy, embed, components));
		}

		return sentMessages;
	}
	
	public List<DiscordChannel> createThread(List<DiscordChannel> channelList, String name) {
		List<DiscordChannel> threadChannels = new ArrayList<DiscordChannel>();
		for (DiscordChannel channel : channelList) {
			threadChannels.add(createThread(channel, name));
		}
		return threadChannels;
	}
	
	public DiscordChannel getLatestMessageChannel() {
		return latestMessageChannel;
	}

	public void createApplicationCommands(){
		DiscordApplicationCommand appBet = new DiscordApplicationCommand("bet", "Place a bet for a game");
		DiscordCommandOption option1 = new DiscordCommandOption(DiscordCommandOptionType.INTEGER, "matchid", "The game number.");
		appBet.addOption(option1);
		DiscordCommandOption option2 = new DiscordCommandOption(DiscordCommandOptionType.STRING, "team", "The color of the team you want to bet on.");
		option2.addChoice(new DiscordCommandOptionChoice("red", "red"));
		option2.addChoice(new DiscordCommandOptionChoice("blue", "blue"));
		appBet.addOption(option2);
		DiscordCommandOption option3 = new DiscordCommandOption(DiscordCommandOptionType.INTEGER, "amount", "The amount of coins you want to bet");
		appBet.addOption(option3);
		appBet.create();

		DiscordApplicationCommand appBuy = new DiscordApplicationCommand("buy", "Buy a perk with your coins.");
		appBuy.create();

		DiscordApplicationCommand appPardon = new DiscordApplicationCommand("pardon", "Unbans a player banned by the bot. Does not work on manual bans.");
		DiscordCommandOption pardonOption1 = new DiscordCommandOption(DiscordCommandOptionType.USER, "player", "Player to unban.");
		appPardon.addOption(pardonOption1);
		DiscordCommandOption pardonOption2 = new DiscordCommandOption(DiscordCommandOptionType.STRING, "reason", "Reason for the unban.");
		appPardon.addOption(pardonOption2);
		appPardon.create();
	}
}
