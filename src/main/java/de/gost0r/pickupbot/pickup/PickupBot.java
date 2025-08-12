package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import de.gost0r.pickupbot.pickup.PlayerBan.BanReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PickupBot {

    private final FtwglApi ftwglApi;
    private final DiscordService discordService;
    private final PermissionService permissionService;
    private final PickupRoleCache pickupRoleCache;
    public final String env;

    private DiscordChannel latestMessageChannel;

    private PickupLogic logic;
    private DiscordUser self;

    public PickupBot(
            @Value("${app.stage}") String env,
            FtwglApi ftwglApi,
            DiscordService discordService,
            PermissionService permissionService,
            PickupRoleCache pickupRoleCache
    ) {
        this.env = env;
        this.ftwglApi = ftwglApi;
        this.discordService = discordService;
        this.permissionService = permissionService;
        this.pickupRoleCache = pickupRoleCache;
    }

    public void init() {
        log.info("Starting bot...");
        this.self = discordService.getMe();

        logic = new PickupLogic(this, ftwglApi, discordService, permissionService, pickupRoleCache);
        logic.init();
        createApplicationCommands();

        sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), Config.bot_online);
        log.info("Bot online");
    }

    public void tick() {
        if (logic != null) {
            logic.afkCheck();
            logic.checkPrivateGroups();
        }
    }

    public void recvMessage(DiscordMessage msg) {
        log.info("RECV #{} {}: {}",
                (msg.getChannel() == null || msg.getChannel().getName() == null) ? "null" : msg.getChannel().getName(),
                msg.getUser().getUsername(),
                msg.getContent()
        );

        this.latestMessageChannel = msg.getChannel();

        if (msg.getUser().getId().equals(self.getId()) || logic == null) {
            return;
        }

        String[] data = msg.getContent().split(" ");

        if (isChannel(PickupChannelType.PUBLIC, msg.getChannel())) {
            Player p = Player.get(msg.getUser());

            if (p != null) {
                p.afkCheck();
                p.setLastPublicChannel(msg.getChannel());
            }

            // Execute code according to cmd
            switch (data[0].toLowerCase()) {
                case Config.CMD_ADD:
                    if (data.length > 1) {
                        if (p != null) {
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
                                sendNotice(msg.getUser(), Config.no_gt_found);
                            }
                        } else sendNotice(msg.getUser(), Config.user_not_registered);
                    } else sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADD));
                    break;

                case Config.CMD_TS:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("TS");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);

                            if (data.length > 1) {
                                logic.cmdMapVote(p, gt, data[1], 1);
                            }
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_CTF:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("CTF");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);

                            if (data.length > 1) {
                                logic.cmdMapVote(p, gt, data[1], 1);
                            }
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_BM:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("BM");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);

                            if (data.length > 1) {
                                logic.cmdMapVote(p, gt, data[1], 1);
                            }
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_1v1:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("1v1");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);

                            if (data.length > 1) {
                                logic.cmdMapVote(p, gt, data[1], 1);
                            }
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_2v2:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("2v2");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);

                            if (data.length > 1) {
                                logic.cmdMapVote(p, gt, data[1], 1);
                            }
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_DIV1:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("div1");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);

                            if (data.length > 1) {
                                logic.cmdMapVote(p, gt, data[1], 1);
                            }
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_PROCTF:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("proctf");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);

                            if (data.length > 1) {
                                logic.cmdMapVote(p, gt, data[1], 1);
                            }
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_SKEET:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("SKEET");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_AIM:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("aim");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_PROMOD:
                    if (p != null) {
                        Gametype gt = logic.getGametypeByString("PROMOD");
                        if (gt != null) {
                            logic.cmdAddPlayer(p, gt, false);
                        } else {
                            sendNotice(msg.getUser(), Config.no_gt_found);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_REMOVE:
                    Player player = p;
                    int startindex = 1;
                    if (permissionService.hasAdminRights(msg.getUser()) && data.length > 1) {
                        DiscordUser u = discordService.getUserFromMention(data[1]);
                        if (u != null) {
                            player = Player.get(u);
                            if (data.length > 2) {
                                startindex = 2;
                            } else {
                                startindex = -1;
                            }
                        }
                    }

                    List<Gametype> gametypes = new ArrayList<Gametype>();
                    if (data.length == 1 || startindex == -1) {
                        gametypes = null;
                    } else {
                        String[] modes = Arrays.copyOfRange(data, startindex, data.length);
                        for (String mode : modes) {
                            Gametype gt = logic.getGametypeByString(mode);
                            if (gt != null) {
                                gametypes.add(gt);
                            }
                        }
                    }
                    if (player != null) {
                        logic.cmdRemovePlayer(player, gametypes);
                        player.setLastPublicChannel(p.getLastPublicChannel());
                    }

                    break;

                case Config.CMD_FORCEADD:
                    if (!permissionService.hasAdminRights(msg.getUser())) {
                        sendNotice(msg.getUser(), Config.player_not_admin);
                        return;
                    }
                    if (data.length >= 3) {
                        for (int i = 2; i < data.length; i++) {
                            if (data[i].trim().length() == 0) {
                                continue;
                            }
                            DiscordUser u = discordService.getUserFromMention(data[i]);
                            Player playerToAdd = null;
                            if (u != null) {
                                playerToAdd = Player.get(u);
                            } else {
                                playerToAdd = Player.get(data[i]);
                            }
                            if (playerToAdd != null) {
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
                                    for (Team activeTeam : logic.getActiveTeams()) {
                                        if (activeTeam.isInTeam(playerToAdd)) {
                                            logic.cmdAddTeam(playerToAdd, gametypes.get(0), true);
                                            return;
                                        }
                                    }
                                    logic.cmdAddPlayer(playerToAdd, gametypes, true);
                                } else {
                                    sendNotice(msg.getUser(), Config.no_gt_found);
                                }
                            } else
                                sendNotice(msg.getUser(), Config.other_user_not_registered.replace(".user.", data[i]));
                        }
                    } else
                        sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_FORCEADD));
                    break;

                case Config.CMD_MAPS:
                    if (p != null) {
                        if (data.length == 2) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            logic.cmdGetMapsGt(p, gt);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_MAPS));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_MAP:
                    if (p != null) {
                        if (data.length == 2) {
                            logic.cmdMapVote(p, null, data[1], 1);
                        } else if (data.length == 3) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            logic.cmdMapVote(p, gt, data[2], 1);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_MAP));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_ADDVOTE:
                    if (p != null) {
                        if (data.length == 2) {
                            logic.cmdMapVote(p, null, data[1], 0);
                        } else if (data.length == 3) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            logic.cmdMapVote(p, gt, data[2], 0);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDVOTE));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_BANMAP:
                    if (p != null) {
                        if (data.length == 2) {
                            logic.cmdUseMapBan(p, data[1]);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_BANMAP));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_STATUS:
                    if (data.length == 1) {
                        logic.cmdStatus();
                    } else
                        sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_STATUS));
                    break;

                case Config.CMD_VOTES:
                    if (data.length == 1) {
                        logic.cmdGetMaps(p, false);
                    } else
                        sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_VOTES));
                    break;

                case Config.CMD_SURRENDER:
                    if (data.length == 1) {
                        if (p != null) {
                            logic.cmdSurrender(p);
                        }
                    } else
                        sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SURRENDER));
                    break;

                case Config.CMD_RESET:
                    if (permissionService.hasAdminRights(msg.getUser())) {
                        if (data.length == 1) {
                            logic.cmdReset("all");
                        } else if (data.length == 2) {
                            logic.cmdReset(data[1]);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_RESET));
                    }
                    break;

                case Config.CMD_LOCK:
                    if (permissionService.hasAdminRights(msg.getUser())) {
                        if (data.length == 1) {
                            logic.cmdLock();
                        } else
                            msg.getChannel().sendMessage(msg.getUser().getMentionString() + " " + Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LOCK));
                    }
                    break;

                case Config.CMD_UNLOCK:
                    if (permissionService.hasAdminRights(msg.getUser())) {
                        if (data.length == 1) {
                            logic.cmdUnlock();
                        } else
                            msg.getChannel().sendMessage(msg.getUser().getMentionString() + " " + Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_UNLOCK));
                    }
                    break;

                case Config.CMD_GETELO:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdGetStats(p);
                        } else if (data.length == 2) {
                            Player pOther;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                pOther = Player.get(u);
                            } else {
                                pOther = Player.get(data[1].toLowerCase());
                            }

                            if (pOther != null) {
                                logic.cmdGetStats(pOther);
                            } else sendNotice(msg.getUser(), Config.player_not_found);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_GETELO));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_GETSTATS:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdGetStats(p);
                        } else if (data.length == 2) {
                            Player pOther;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                pOther = Player.get(u);
                            } else {
                                pOther = Player.get(data[1].toLowerCase());
                            }
                            if (pOther != null) {
                                logic.cmdGetStats(pOther);
                            } else sendNotice(msg.getUser(), Config.player_not_found);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_GETSTATS));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_TOP_PLAYERS:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdTopElo(10);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP10));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_TOP_COUNTRIES:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdTopCountries(5);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP10));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_TOP_WDL:
                    if (p != null) {
                        if (data.length == 2) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            if (gt != null) {
                                logic.cmdTopWDL(10, gt);
                            } else {
                                sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_WDL));
                            }
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_WDL));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_TOP:
                case Config.CMD_TOP_KDR:
                    if (p != null) {
                        if (data.length == 2) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            if (gt != null) {
                                logic.cmdTopKDR(10, gt);
                            } else {
                                sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_KDR));
                            }
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_KDR));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_TOP_MATCH_PLAYED:
                    if (p != null) {
                        logic.cmdTopMatchPlayed(10);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_SPREE:
                    if (p != null) {
                        if (data.length == 2) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            if (gt != null) {
                                logic.cmdTopSpree(10, gt);
                            } else {
                                sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_SPREE));
                            }
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_SPREE));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_LOSS:
                    if (p != null) {
                        if (data.length == 2) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            if (gt != null) {
                                logic.cmdWorstSpree(10, gt);
                            } else {
                                sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_LOSS));
                            }
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_LOSS));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_REGISTER:
                    if (data.length == 2) {
                        logic.cmdRegisterPlayer(msg.getUser(), data[1].toLowerCase(), msg);
                    } else
                        sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_REGISTER));
                    break;

                case Config.CMD_COUNTRY:
                    if (data.length == 2) {
                        logic.cmdSetPlayerCountry(msg.getUser(), data[1].toUpperCase());
                    } else
                        sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_COUNTRY));
                    break;

                case Config.CMD_LIVE:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdLive(msg.getChannel());
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LIVE));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_MATCH:
                    if (p != null) {
                        if (data.length == 2) {
                            logic.cmdDisplayMatch(data[1]);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_MATCH));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_LAST:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdDisplayLastMatch();
                        } else if (data.length == 2) {
                            Player pOther;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                pOther = Player.get(u);
                            } else {
                                pOther = Player.get(data[1].toLowerCase());
                            }

                            if (pOther != null) {
                                logic.cmdDisplayLastMatchPlayer(pOther);
                            } else sendNotice(msg.getUser(), Config.player_not_found);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LAST));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;

                case Config.CMD_BANINFO:
                    if (p != null) {
                        if (data.length == 1) {
                            msg.getChannel().sendMessage(logic.printBanInfo(p));
                        } else if (data.length == 2) {
                            Player pOther;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                pOther = Player.get(u);
                            } else {
                                pOther = Player.get(data[1].toLowerCase());
                            }

                            if (pOther != null) {
                                msg.getChannel().sendMessage(logic.printBanInfo(pOther));
                            } else sendNotice(msg.getUser(), Config.player_not_found);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_BANINFO));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
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
                                DiscordUser u = discordService.getUserFromMention(data[i]);
                                Player playerToInvite = null;
                                if (u != null) {
                                    playerToInvite = Player.get(u);
                                } else {
                                    noMentions = true;
                                    continue;
                                }
                                if (playerToInvite != null) {
                                    invitedPlayers.add(playerToInvite);
                                } else {
                                    logic.bot.sendNotice(p.getDiscordUser(), Config.other_user_not_registered.replace(".user.", u.getMentionString()));
                                }
                            }
                            if (noMentions) {
                                logic.bot.sendNotice(p.getDiscordUser(), Config.team_only_mentions);
                            }
                            if (!invitedPlayers.isEmpty()) {
                                logic.invitePlayersToTeam(p, invitedPlayers);
                            }
                        } else {
                            logic.cmdPrintTeam(p);
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_LEAVETEAM:
                    if (p != null) {
                        logic.leaveTeam(p);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_SCRIM:
                    if (p != null) {
                        if (data.length == 2) {
                            Gametype gt;
                            if (data[1].equalsIgnoreCase("2V2")) {
                                gt = logic.getGametypeByString("2V2");
                            } else gt = logic.getGametypeByString("SCRIM " + data[1]);

                            if (gt == null || !gt.isTeamGamemode()) {
                                sendNotice(msg.getUser(), Config.team_error_wrong_gt);
                                return;
                            }

                            logic.cmdAddTeam(p, gt, false);
                        } else {
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SCRIM));
                        }
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_REMOVETEAM:
                    if (p != null) {
                        logic.cmdRemoveTeam(p, true);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_TEAMS:
                    if (p != null) {
                        logic.cmdPrintTeams(p);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_PING:
                    if (p != null) {
                        logic.cmdGetPingURL(p);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_TOP_RICH:
                    if (p != null) {
                        logic.cmdTopRich(10);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_TOP_RATING:
                    if (p != null) {
                        logic.cmdTopFTWGLRatings();
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_WALLET:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdWallet(p);
                        } else if (data.length == 2) {
                            Player pOther;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                pOther = Player.get(u);
                            } else {
                                pOther = Player.get(data[1].toLowerCase());
                            }

                            if (pOther != null) {
                                logic.cmdWallet(pOther);
                            } else sendNotice(msg.getUser(), Config.player_not_found);
                        }

                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_DONATE:
                    if (p != null) {
                        if (data.length == 3) {

                            Player pOther;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                pOther = Player.get(u);
                            } else {
                                pOther = Player.get(data[1].toLowerCase());
                            }

                            if (pOther != null) {
                                int amount;
                                try {
                                    amount = Integer.parseInt(data[2]);
                                    pOther.setLastPublicChannel(msg.getChannel());
                                    logic.cmdDonate(p, pOther, amount);
                                } catch (NumberFormatException nfe) {
                                    sendNotice(p.getDiscordUser(), Config.donate_incorrect_amount);
                                }
                            } else sendNotice(msg.getUser(), Config.player_not_found);
                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_DONATE));

                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_BETHISTORY:
                    if (p != null) {
                        if (data.length == 1) {
                            logic.cmdBetHistory(p);
                        } else if (data.length == 2) {
                            Player pOther;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                pOther = Player.get(u);
                            } else {
                                pOther = Player.get(data[1].toLowerCase());
                            }

                            if (pOther != null) {
                                logic.cmdBetHistory(pOther);
                            } else sendNotice(msg.getUser(), Config.player_not_found);
                        }

                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_CREATE_PRIVATE:
                    if (p != null) {
                        if (data.length >= 2) {
                            Gametype gt = logic.getGametypeByString(data[1]);
                            if (gt == null) {
                                sendNotice(msg.getUser(), Config.no_gt_found);
                            } else {
                                PrivateGroup pvGroup = logic.createPrivateGroup(p, gt);

                                if (pvGroup != null) {
                                    if (data.length >= 3) {
                                        String[] players = Arrays.copyOfRange(data, 2, data.length);
                                        for (String newP : players) {
                                            Player pOther;
                                            DiscordUser u = discordService.getUserFromMention(newP);
                                            if (u != null) {
                                                pOther = Player.get(u);
                                            } else {
                                                pOther = Player.get(newP.toLowerCase());
                                            }

                                            if (pOther != null) {
                                                pvGroup.addPlayer(pOther);
                                            } else sendNotice(msg.getUser(), Config.player_not_found);
                                        }
                                    }
                                    sendNotice(msg.getUser(), Config.player_create_group, pvGroup.getEmbed());
                                }

                            }
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_CREATE_PRIVATE));
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_ADD_PLAYER_PRIVATE:
                    if (p != null) {
                        PrivateGroup pvGroup = logic.getPrivateGroupOwned(p);
                        if (pvGroup != null) {
                            if (data.length >= 2) {
                                String[] players = Arrays.copyOfRange(data, 1, data.length);
                                boolean changesMade = false;
                                for (String newP : players) {
                                    Player pOther;
                                    DiscordUser u = discordService.getUserFromMention(newP);
                                    if (u != null) {
                                        pOther = Player.get(u);
                                    } else {
                                        pOther = Player.get(newP.toLowerCase());
                                    }

                                    if (pOther != null) {
                                        if (!logic.playerInPrivateGroup(pOther)) {
                                            pvGroup.addPlayer(pOther);
                                            changesMade = true;
                                        } else {
                                            String newMsg = Config.player_already_group;
                                            newMsg = newMsg.replace(".player.", pOther.getUrtauth());
                                            sendNotice(p.getDiscordUser(), newMsg);
                                        }

                                    } else sendNotice(msg.getUser(), Config.player_not_found);
                                }
                                if (changesMade) {
                                    sendNotice(msg.getUser(), Config.player_added_group);
                                }
                            } else
                                sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADD_PLAYER_PRIVATE));
                        } else sendNotice(msg.getUser(), Config.player_no_owned_group);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_REMOVE_PLAYER_PRIVATE:
                    if (p != null) {
                        PrivateGroup pvGroup = logic.getPrivateGroupOwned(p);
                        if (pvGroup != null) {
                            if (data.length >= 2) {
                                String[] players = Arrays.copyOfRange(data, 1, data.length);
                                boolean changesMade = false;
                                for (String newP : players) {
                                    Player pOther;
                                    DiscordUser u = discordService.getUserFromMention(newP);
                                    if (u != null) {
                                        pOther = Player.get(u);
                                    } else {
                                        pOther = Player.get(newP.toLowerCase());
                                    }

                                    if (pOther != null) {
                                        if (pOther.equals(p)) {
                                            logic.dissolveGroup(pvGroup);
                                        } else {
                                            pvGroup.removePlayer(pOther);
                                            changesMade = true;
                                        }
                                    } else sendNotice(msg.getUser(), Config.player_not_found);
                                }
                                if (changesMade) {
                                    sendNotice(msg.getUser(), Config.player_removed_group);
                                }
                            } else
                                sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_REMOVE_PLAYER_PRIVATE));
                        } else sendNotice(msg.getUser(), Config.player_no_owned_group);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_LEAVE_PRIVATE:
                    if (p != null) {
                        if (logic.playerInPrivateGroup(p)) {
                            logic.cmdLeavePrivate(p);
                        } else sendNotice(msg.getUser(), Config.player_no_group);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_PRIVATE:
                    if (p != null) {
                        if (logic.playerInPrivateGroup(p)) {
                            PrivateGroup pvGroup = logic.getPrivateGroupMember(p);
                            logic.cmdAddPlayer(p, pvGroup.gt, false);
                            pvGroup.updateTimestamp();

                            if (data.length > 1) {
                                logic.cmdMapVote(p, pvGroup.gt, data[1], 1);
                            }
                        } else sendNotice(msg.getUser(), Config.player_no_group);
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
                case Config.CMD_SHOW_PRIVATE:
                    if (p != null) {
                        logic.cmdShowPrivate();
                    } else sendNotice(msg.getUser(), Config.user_not_registered);
                    break;
            }
        }

        if (msg.getChannel().isThreadChannel()) {
            Player p = Player.get(msg.getUser());

            // AFK CHECK CODE
            if (p != null) {
                p.afkCheck();
            }
        }

        // use admin channel or DM for super admins
        if (isChannel(PickupChannelType.ADMIN, msg.getChannel())
                || msg.getChannel().isPrivateChannel() && permissionService.hasSuperAdminRights(msg.getUser())) {
            if (permissionService.hasAdminRights(msg.getUser())) {
                // Execute code according to cmd
                switch (data[0].toLowerCase()) {
                    case Config.CMD_REBOOT:
                        try {
                            msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            logic.restartApplication();
                        } catch (URISyntaxException | IOException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                        break;

                    case Config.CMD_GETDATA:
                        if (data.length == 1) {
                            logic.cmdGetData(msg.getChannel());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_GETDATA));
                        break;

                    case Config.CMD_ENABLEMAP:
                        if (data.length == 3) {
                            if (logic.cmdEnableMap(data[1], data[2])) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLEMAP));
                        break;

                    case Config.CMD_DISABLEMAP:
                        if (data.length == 3) {
                            if (logic.cmdDisableMap(data[1], data[2])) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_DISABLEMAP));
                        break;

                    case Config.CMD_ENABLEGAMETYPE:
                        if (data.length == 3) {
                            if (logic.cmdEnableGametype(data[1], data[2])) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLEGAMETYPE));
                        break;

                    case Config.CMD_DISABLEGAMETYPE:
                        if (data.length == 2) {
                            if (logic.cmdDisableGametype(data[1])) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLEGAMETYPE));
                        break;

                    case Config.CMD_LISTGAMECONFIG:
                        if (data.length == 2) {
                            if (!logic.cmdListGameConfig(msg.getChannel(), data[1])) {
                                msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                            }
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_LISTGAMECONFIG));
                        break;

                    case Config.CMD_ADDSERVER:
                        if (data.length == 4) {
                            if (logic.cmdAddServer(data[1], data[2], data[3])) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDSERVER));
                        break;

                    case Config.CMD_ENABLESERVER:
                        if (data.length == 2) {
                            if (logic.cmdServerActivation(data[1], true)) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENABLESERVER));
                        break;

                    case Config.CMD_DISABLESERVER:
                        if (data.length == 2) {
                            if (logic.cmdServerActivation(data[1], false)) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_DISABLESERVER));
                        break;

                    case Config.CMD_UPDATESERVER:
                        if (data.length == 3) {
                            if (logic.cmdServerChangeRcon(data[1], data[2])) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_UPDATESERVER));
                        break;

                    case Config.CMD_SHOWSERVERS:
                        if (data.length == 1) {
                            msg.getChannel().sendMessage(Config.wait_testing_server);
                            logic.cmdServerList(msg.getChannel());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SHOWSERVERS));
                        break;

                    case Config.CMD_RCON:
                        if (data.length > 2) {
                            if (logic.cmdServerSendRcon(data[1], msg.getContent().substring(Config.CMD_RCON.length() + data[1].length() + 2))) {
                                msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_RCON));
                        break;

                    case Config.CMD_SHOWMATCHES:
                        if (data.length == 1) {
                            logic.cmdMatchList(msg.getChannel());
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SHOWMATCHES));
                        break;

                    case Config.CMD_UNREGISTER:
                        if (data.length == 2) {
                            Player player = Player.get(data[1].toLowerCase());
                            if (player != null) {
                                if (logic.cmdUnregisterPlayer(player)) {
                                    msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                                } else
                                    msg.getChannel().sendMessage(Config.admin_cmd_unsuccessful + msg.getContent());
                            } else msg.getChannel().sendMessage(Config.player_not_found);
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_UNREGISTER));
                        break;

                    case Config.CMD_ENFORCEAC:
                        if (data.length == 2) {
                            Player player = Player.get(data[1].toLowerCase());
                            if (player != null) {
                                if (logic.cmdEnforcePlayerAC(player)) {
                                    msg.getChannel().sendMessage(Config.admin_enforce_ac_on.replace(".urtauth.", player.getUrtauth()));
                                } else
                                    msg.getChannel().sendMessage(Config.admin_enforce_ac_off.replace(".urtauth.", player.getUrtauth()));
                            } else msg.getChannel().sendMessage(Config.player_not_found);
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ENFORCEAC));
                        break;

                    case Config.CMD_SETPROCTF:
                        if (data.length >= 2) {
                            for (int i = 1; i < data.length; i++) {
                                Player player = Player.get(data[i].toLowerCase());
                                if (player != null) {
                                    if (logic.cmdSetProctf(player)) {
                                        msg.getChannel().sendMessage(Config.admin_proctf_on.replace(".urtauth.", player.getUrtauth()));
                                    } else
                                        msg.getChannel().sendMessage(Config.admin_proctf_off.replace(".urtauth.", player.getUrtauth()));
                                } else msg.getChannel().sendMessage(Config.player_not_found);
                            }
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_SETPROCTF));
                        break;

                    case Config.CMD_ADDBAN:
                        if (data.length == 4) {
                            Player p;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                p = Player.get(u);
                            } else {
                                p = Player.get(data[1].toLowerCase());
                            }

                            if (p != null) {
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
                                    } else msg.getChannel().sendMessage(Config.banduration_invalid);
                                } else
                                    msg.getChannel().sendMessage(Config.banreason_not_found.replace(".banreasons.", Arrays.toString(BanReason.values())));
                            } else msg.getChannel().sendMessage(Config.player_not_found);
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDBAN));
                        break;

                    case Config.CMD_REMOVEBAN:
                        if (data.length == 2) {
                            Player p;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                p = Player.get(u);
                            } else {
                                p = Player.get(data[1].toLowerCase());
                            }

                            if (p != null) {
                                logic.UnbanPlayer(p);
                            } else msg.getChannel().sendMessage(Config.player_not_found);
                        } else
                            msg.getChannel().sendMessage(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_ADDBAN));
                        break;

                    case Config.CMD_COUNTRY:
                        if (data.length == 3) {
                            Player p;
                            DiscordUser u = discordService.getUserFromMention(data[1]);
                            if (u != null) {
                                p = Player.get(u);
                            } else {
                                p = Player.get(data[1].toLowerCase());
                            }

                            if (p != null) {
                                logic.cmdChangePlayerCountry(p, data[2].toUpperCase());
                            } else msg.getChannel().sendMessage(Config.player_not_found);

                        } else
                            sendNotice(msg.getUser(), Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_CHANGE_COUNTRY));
                        break;

                    case Config.CMD_RESETELO:
                        logic.cmdResetElo();
                        sendNotice(msg.getUser(), Config.elo_reset);
                        break;

                    case Config.CMD_ENABLEDYNSERVER:
                        logic.cmdEnableDynamicServer();
                        msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                        break;

                    case Config.CMD_DISABLEDYNSERVER:
                        logic.cmdDisableDynamicServer();
                        msg.getChannel().sendMessage(Config.admin_cmd_successful + msg.getContent());
                        break;
                }
            }
        }

        if (isChannel(PickupChannelType.PUBLIC, msg.getChannel())
                || isChannel(PickupChannelType.ADMIN, msg.getChannel())
                || msg.getChannel().isPrivateChannel()) {
            switch (data[0].toLowerCase()) {
                case Config.CMD_HELP:
                    if (data.length == 2) {
                        String cmd = (!data[1].startsWith("!") ? "!" : "") + data[1];
                        switch (cmd) {
                            case Config.CMD_ADD:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADD));
                                break;
                            case Config.CMD_REMOVE:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVE));
                                break;
                            case Config.CMD_MAPS:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_MAPS));
                                break;
                            case Config.CMD_MAP:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_MAP));
                                break;
                            case Config.CMD_STATUS:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_STATUS));
                                break;
                            case Config.CMD_HELP:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_HELP));
                                break;
                            case Config.CMD_LOCK:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_LOCK));
                                break;
                            case Config.CMD_UNLOCK:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_UNLOCK));
                                break;
                            case Config.CMD_RESET:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_RESET));
                                break;
                            case Config.CMD_GETDATA:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_GETDATA));
                                break;
                            case Config.CMD_ENABLEMAP:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ENABLEMAP));
                                break;
                            case Config.CMD_DISABLEMAP:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_DISABLEMAP));
                                break;
                            case Config.CMD_RCON:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_RCON));
                                break;
                            case Config.CMD_REGISTER:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_REGISTER));
                                break;
                            case Config.CMD_GETELO:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_GETELO));
                                break;
                            case Config.CMD_GETSTATS:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_GETSTATS));
                                break;
                            case Config.CMD_TOP_PLAYERS:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP10));
                                break;
                            case Config.CMD_TOP_COUNTRIES:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP_COUNTRIES));
                                break;
                            case Config.CMD_MATCH:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_MATCH));
                                break;
                            case Config.CMD_SURRENDER:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_SURRENDER));
                                break;
                            case Config.CMD_LIVE:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_LIVE));
                                break;
                            case Config.CMD_ADDBAN:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDBAN));
                                break;
                            case Config.CMD_REMOVEBAN:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVEBAN));
                                break;
                            case Config.CMD_BANINFO:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_BANINFO));
                                break;
                            case Config.CMD_SHOWSERVERS:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_SHOWSERVERS));
                                break;
                            case Config.CMD_ADDSERVER:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDSERVER));
                                break;
                            case Config.CMD_ENABLESERVER:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ENABLESERVER));
                                break;
                            case Config.CMD_DISABLESERVER:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_DISABLESERVER));
                                break;
                            case Config.CMD_UPDATESERVER:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_UPDATESERVER));
                                break;
                            case Config.CMD_ENABLEGAMETYPE:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ENABLEGAMETYPE));
                                break;
                            case Config.CMD_DISABLEGAMETYPE:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_DISABLEGAMETYPE));
                                break;
                            case Config.CMD_ADDCHANNEL:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDCHANNEL));
                                break;
                            case Config.CMD_REMOVECHANNEL:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVECHANNEL));
                                break;
                            case Config.CMD_ADDROLE:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_ADDROLE));
                                break;
                            case Config.CMD_REMOVEROLE:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVEROLE));
                                break;
                            case Config.CMD_SHOWMATCHES:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_SHOWMATCHES));
                                break;
                            case Config.CMD_LISTGAMECONFIG:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_LISTGAMECONFIG));
                                break;
                            case Config.CMD_COUNTRY:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_COUNTRY));
                                break;
                            case Config.CMD_BM:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_BM));
                                break;
                            case Config.CMD_CTF:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_CTF));
                                break;
                            case Config.CMD_TS:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TS));
                                break;
                            case Config.CMD_DIV1:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_DIV1));
                                break;
                            case Config.CMD_PROCTF:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_PROCTF));
                                break;
                            case Config.CMD_VOTES:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_VOTES));
                                break;
                            case Config.CMD_TOP_WDL:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP_WDL));
                                break;
                            case Config.CMD_TOP_KDR:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP_KDR));
                                break;
                            case Config.CMD_LAST:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_LAST));
                                break;
                            case Config.CMD_SCRIM:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_SCRIM));
                                break;
                            case Config.CMD_REMOVETEAM:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_REMOVETEAM));
                                break;
                            case Config.CMD_TEAM:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TEAM));
                                break;
                            case Config.CMD_LEAVETEAM:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_LEAVETEAM));
                                break;
                            case Config.CMD_TEAMS:
                                msg.getChannel().sendMessage(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TEAMS));
                                break;

                            default:
                                msg.getChannel().sendMessage(Config.help_unknown);
                                break;
                        }
                    } else {
                        if (isChannel(PickupChannelType.PUBLIC, msg.getChannel())) {
                            msg.getChannel().sendMessage(Config.help_cmd_avi.replace(".cmds.", Config.PUB_LIST));
                        }
                        if (permissionService.hasAdminRights(msg.getUser()) && (isChannel(PickupChannelType.ADMIN, msg.getChannel()) || msg.getChannel().isPrivateChannel())) {
                            msg.getChannel().sendMessage(Config.help_cmd_avi.replace(".cmds.", Config.ADMIN_LIST));
                        }
                    }
                    break;

                case Config.CMD_ADDCHANNEL:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        if (data.length == 3) {
                            DiscordChannel targetChannel = discordService.getChannelFromMention(data[1]);
                            if (targetChannel != null) {
                                try {
                                    PickupChannelType type = PickupChannelType.valueOf(data[2].toUpperCase());
                                    if (!logic.getChannelByType(type).contains(targetChannel)) {
                                        if (logic.addChannel(type, targetChannel)) {
                                            sendNotice(msg.getUser(), "successfully added the channel");
                                        } else sendNotice(msg.getUser(), "unsuccessfully added the channel");
                                    }
                                } catch (IllegalArgumentException e) {
                                    sendNotice(msg.getUser(), "unknown channel type");
                                }
                            } else sendNotice(msg.getUser(), "invalid channel");
                        } else sendNotice(msg.getUser(), "invalid options");
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;

                case Config.CMD_REMOVECHANNEL:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        if (data.length == 3) {
                            DiscordChannel targetChannel = discordService.getChannelFromMention(data[1]);
                            if (targetChannel != null) {
                                try {
                                    PickupChannelType type = PickupChannelType.valueOf(data[2].toUpperCase());
                                    if (logic.getChannelByType(type).contains(targetChannel)) {
                                        if (logic.removeChannel(type, targetChannel)) {
                                            sendNotice(msg.getUser(), "successfully removed the channel.");
                                        } else sendNotice(msg.getUser(), "unsuccessfully removed the channel.");
                                    }

                                } catch (IllegalArgumentException e) {
                                    sendNotice(msg.getUser(), "unknown role type");
                                }
                            } else sendNotice(msg.getUser(), "invalid channel");
                        } else sendNotice(msg.getUser(), "invalid options");
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;

                case Config.CMD_ADDROLE:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        if (data.length == 3) {
                            DiscordRole targetRole = discordService.getRoleFromMention(data[1]);
                            if (targetRole != null) {
                                try {
                                    PickupRoleType type = PickupRoleType.valueOf(data[2].toUpperCase());
                                    if (!pickupRoleCache.getRolesByType(type).contains(targetRole)) {
                                        if (logic.addRole(type, targetRole)) {
                                            sendNotice(msg.getUser(), "successfully added the role.");
                                        } else sendNotice(msg.getUser(), "unsuccessfully removed the role.");
                                    }

                                } catch (IllegalArgumentException e) {
                                    sendNotice(msg.getUser(), "unknown role type");
                                }
                            } else sendNotice(msg.getUser(), "invalid channel");
                        } else sendNotice(msg.getUser(), "invalid options");
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;

                case Config.CMD_REMOVEROLE:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        if (data.length == 3) {
                            DiscordRole targetRole = discordService.getRoleFromMention(data[1]);
                            if (targetRole != null) {
                                try {
                                    PickupRoleType type = PickupRoleType.valueOf(data[2].toUpperCase());
                                    if (pickupRoleCache.getRolesByType(type).contains(targetRole)) {
                                        if (logic.removeRole(type, targetRole)) {
                                            sendNotice(msg.getUser(), "successfully removed the role.");
                                        } else sendNotice(msg.getUser(), "unsuccessfully removed the role.");
                                    }
                                } catch (IllegalArgumentException e) {
                                    sendNotice(msg.getUser(), "unknown role type");
                                }
                            } else sendNotice(msg.getUser(), "invalid role");
                        } else sendNotice(msg.getUser(), "invalid options");
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;


                case Config.CMD_SHOWROLES:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        DiscordUser u = msg.getUser();
                        if (data.length == 2) {
                            DiscordUser testUser = discordService.getUserFromMention(data[1]);
                            if (testUser != null) {
                                u = testUser;
                            }
                        }
                        List<DiscordRole> list = u.getRoles();
                        StringBuilder message = new StringBuilder();
                        for (DiscordRole role : list) {
                            if (msg.getChannel().isPrivateChannel()) {
                                message.append(role.getMentionString()).append(" ");
                            } else {
                                message.append(role.getMentionString()).append(" ");
                            }
                        }
                        sendNotice(u, message.toString());
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;


                case Config.CMD_SHOWKNOWNROLES:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        StringBuilder message = new StringBuilder("Roles: ");
                        for (PickupRoleType type : PickupRoleType.values()) {
                            message.append("\n**").append(type.name()).append("**:");

                            for (DiscordRole role : pickupRoleCache.getRolesByType(type)) {
                                if (msg.getChannel().isPrivateChannel()) {
                                    message.append(role.getMentionString() + " (``" + role.getId() + "``)").append(" ");
                                } else {
                                    message.append(role.getMentionString()).append(" ");
                                }
                            }
                        }
                        msg.getChannel().sendMessage(message.toString());
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;

                case Config.CMD_SHOWKNOWNCHANNELS:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        StringBuilder message = new StringBuilder("Channels: ");
                        for (PickupChannelType type : logic.getChannelTypes()) {
                            message.append("\n**").append(type.name()).append("**:");

                            for (DiscordChannel channel : logic.getChannelByType(type)) {
                                message.append(" ").append(channel.getMentionString()).append(" ");
                            }
                        }
                        msg.getChannel().sendMessage(message.toString());
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;

                case Config.CMD_GODROLE:
                    if (permissionService.hasSuperAdminRights(msg.getUser())) {
                        if (pickupRoleCache.getRolesByType(PickupRoleType.SUPERADMIN).isEmpty()) {
                            if (data.length == 2) {
                                DiscordRole role = discordService.getRoleFromMention(data[1]);
                                if (role != null) {
                                    logic.addRole(PickupRoleType.SUPERADMIN, role);
                                    sendNotice(msg.getUser(), "*" + role.getMentionString() + " set as SUPERADMIN role*");
                                }
                            }
                        } else sendNotice(msg.getUser(), "A DiscordRole is already set as SUPERADMIN, check the DB.");
                    } else sendNotice(msg.getUser(), "You need SuperAdmin rights to use this.");
                    break;
            }
        }
    }

    public void recvInteraction(DiscordInteraction interaction) {
        log.info("RECV #{} {}: {}",
                (interaction.getMessage().getChannel() == null || interaction.getMessage().getChannel().getName() == null) ? "null" : interaction.getMessage().getChannel().getName(),
                interaction.getUser().getUsername(),
                interaction.getComponentId()
        );
        interaction.deferReply();

        Player p = Player.get(interaction.getUser());
        if (p == null) {
            interaction.respondEphemeral(Config.user_not_registered);
            return;
        }

        if (interaction.getMessage() != null && interaction.getMessage().getChannel() != null && isChannel(PickupChannelType.PUBLIC, interaction.getMessage().getChannel())) {
            p.setLastPublicChannel(interaction.getMessage().getChannel());
        }

        String[] data = interaction.getComponentId().split("_");

        switch (data[0].toLowerCase()) {
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
                logic.showSeasonStats(interaction, Player.get(data[1]), Integer.parseInt(interaction.getValues().get(0)));
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

    public void recvApplicationCommand(DiscordSlashCommandInteraction command) {
        log.info("RECV #{} {}", command.getName(), command.getUser().getUsername());
        command.deferReply();

        Player p = Player.get(command.getUser());
        if (p == null) {
            command.respondEphemeral(Config.user_not_registered);
            return;
        }

        switch (command.getName()) {
            case Config.APP_BET:
                logic.bet(command, command.getOptions().get(0).getAsInt(), command.getOptions().get(1).getAsString(), command.getOptions().get(2).getAsInt(), p);
                break;

            case Config.APP_PARDON:
                DiscordUser u = discordService.getUserById(command.getOptions().get(0).getAsString());
                Player pPardon = Player.get(u);
                if (pPardon == null) {
                    command.respondEphemeral(Config.player_not_found);
                    return;
                }
                logic.pardonPlayer(command, pPardon, command.getOptions().get(1).getAsString(), p);
                break;

            case Config.APP_REFUND:
                DiscordUser user = discordService.getUserById(command.getOptions().get(0).getAsString());
                Player pRefund = Player.get(user);
                if (pRefund == null) {
                    command.respondEphemeral(Config.player_not_found);
                    return;
                }
                logic.refundPlayer(command, pRefund, command.getOptions().get(1).getAsInt(), command.getOptions().get(2).getAsString(), p);
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
        getLatestMessageChannel().sendMessage(user.getMentionString() + " " + msg);
    }

    public void sendNotice(DiscordUser user, String msg, DiscordEmbed embed) {
        // FIXME
        getLatestMessageChannel().sendMessage(user.getMentionString() + " " + msg, embed);
    }

    public void sendMsg(List<DiscordChannel> channelList, String msg) {
        List<Player> mentionedPlayers = new ArrayList<Player>();
        Matcher m = Pattern.compile("<@(.*?)>").matcher(msg);
        while (m.find()) {
            DiscordUser dsUser = discordService.getUserById(m.group(1));
            Player playerMentioned = Player.get(dsUser);
            if (dsUser != null) {
                mentionedPlayers.add(playerMentioned);
            }
        }

        for (DiscordChannel channel : channelList) {
            String msgCopy = String.valueOf(msg);
            for (Player p : mentionedPlayers) {
                if ((p.getLastPublicChannel() != null && !channel.isThreadChannel() && !channel.getId().equals(p.getLastPublicChannel().getId())) ||
                        ((p.getLastPublicChannel() != null && channel.isThreadChannel() && channel.getParentId() != null && !channel.getParentId().equals(p.getLastPublicChannel().getId())))
                ) {
                    msgCopy = msgCopy.replace(
                            "<@" + p.getDiscordUser().getId() + ">",
                            "**" + p.getDiscordUser().getUsername() + "**"
                    );
                }
            }
            channel.sendMessage(msgCopy);
        }
    }

    public void sendMsg(List<DiscordChannel> channelList, String msg, DiscordEmbed embed) {
        if (msg == null) {
            msg = "";
        }

        List<Player> mentionedPlayers = new ArrayList<Player>();
        Matcher m = Pattern.compile("<@(.*?)>").matcher(msg);
        while (m.find()) {
            DiscordUser dsUser = discordService.getUserById(m.group(1));
            Player playerMentioned = Player.get(dsUser);
            if (dsUser != null) {
                mentionedPlayers.add(playerMentioned);
            }
        }

        for (DiscordChannel channel : channelList) {
            String msgCopy = String.valueOf(msg);
            for (Player p : mentionedPlayers) {
                if ((p.getLastPublicChannel() != null && !channel.isThreadChannel() && !channel.getId().equals(p.getLastPublicChannel().getId())) ||
                        ((p.getLastPublicChannel() != null && channel.isThreadChannel() && channel.getParentId() != null && !channel.getParentId().equals(p.getLastPublicChannel().getId())))
                ) {
                    msgCopy = msgCopy.replace(
                            "<@" + p.getDiscordUser().getId() + ">",
                            "**" + p.getDiscordUser().getUsername() + "**"
                    );
                }
            }
            channel.sendMessage(msgCopy, embed);
        }
    }

    // FIXME needed?
    public List<DiscordMessage> sendMsgToEdit(List<DiscordChannel> channelList, String msg, DiscordEmbed embed, List<DiscordComponent> components) {
        if (msg == null) {
            msg = "";
        }

        List<Player> mentionedPlayers = new ArrayList<Player>();
        List<DiscordMessage> sentMessages = new ArrayList<DiscordMessage>();
        Matcher m = Pattern.compile("<@(.*?)>").matcher(msg);
        while (m.find()) {
            DiscordUser dsUser = discordService.getUserById(m.group(1));
            Player playerMentioned = Player.get(dsUser);
            if (dsUser != null) {
                mentionedPlayers.add(playerMentioned);
            }
        }

        for (DiscordChannel channel : channelList) {
            String msgCopy = String.valueOf(msg);
            for (Player p : mentionedPlayers) {
                if ((p.getLastPublicChannel() != null && !channel.isThreadChannel() && !channel.getId().equals(p.getLastPublicChannel().getId())) ||
                        ((p.getLastPublicChannel() != null && channel.isThreadChannel() && channel.getParentId() != null && !channel.getParentId().equals(p.getLastPublicChannel().getId())))
                ) {
                    msgCopy = msgCopy.replace(
                            "<@" + p.getDiscordUser().getId() + ">",
                            "**" + p.getDiscordUser().getUsername() + "**"
                    );
                }
            }
            sentMessages.add(channel.sendMessage(msgCopy, embed, components));
        }

        return sentMessages;
    }

    public DiscordChannel getLatestMessageChannel() {
        return latestMessageChannel;
    }

    public void createApplicationCommands() {
        discordService.registerApplicationCommands(List.of(
                new DiscordApplicationCommand("bet", "Place a bet for a game", List.of(
                        new DiscordCommandOption(DiscordCommandOptionType.INTEGER, "matchid", "The game number.", List.of()),
                        new DiscordCommandOption(DiscordCommandOptionType.STRING, "team", "The color of the team you want to bet on.", List.of(
                                new DiscordCommandOptionChoice("red", "red"),
                                new DiscordCommandOptionChoice("blue", "blue")
                        )),
                        new DiscordCommandOption(DiscordCommandOptionType.INTEGER, "amount", "The amount of coins you want to bet", List.of())
                )),
                new DiscordApplicationCommand("buy", "Buy a perk with your coins.", List.of()),
                new DiscordApplicationCommand("pardon", "Unbans a player banned by the bot. Does not work on manual bans.", List.of(
                        new DiscordCommandOption(DiscordCommandOptionType.USER, "player", "Player to unban.", List.of()),
                        new DiscordCommandOption(DiscordCommandOptionType.STRING, "reason", "Reason for the unban.", List.of())
                )),
                new DiscordApplicationCommand("refund", "Refund pugcoins to a player following a bot error.", List.of(
                        new DiscordCommandOption(DiscordCommandOptionType.USER, "player", "Player to refund.", List.of()),
                        new DiscordCommandOption(DiscordCommandOptionType.INTEGER, "amount", "Amount to refund.", List.of()),
                        new DiscordCommandOption(DiscordCommandOptionType.STRING, "reason", "Reason for the refund.", List.of())
                ))
        ));
    }
}
