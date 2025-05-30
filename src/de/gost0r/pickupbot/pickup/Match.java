package de.gost0r.pickupbot.pickup;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.gost0r.pickupbot.ftwgl.FtwglAPI;
import io.sentry.Sentry;
import org.json.JSONObject;

import de.gost0r.pickupbot.discord.DiscordButton;
import de.gost0r.pickupbot.discord.DiscordButtonStyle;
import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.pickup.MatchStats.Status;
import de.gost0r.pickupbot.pickup.server.Server;
import de.gost0r.pickupbot.pickup.server.ServerMonitor.ServerState;

public class Match implements Runnable {
	private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private Gametype gametype;
	private MatchState state;
	private int id;

	private Map<String, List<Player>> teamList;
	private Map<GameMap, Integer> mapVotes;
	private Map<Player, MatchStats> playerStats;
	private List<Player> sortedPlayers;
	private List<Team> squadList; // Premade teams
	
	public List<DiscordChannel> threadChannels;
	public List<DiscordMessage> liveScoreMsgs;

	private Server server;
	private Server gtvServer;
	private GameMap map;
	private int[] elo = new int[2];	
	private int[] score = new int[2];
	private float[] odds = new float[2];
	
	private Player[] captains = new Player[2];
	private int captainTurn;
	private int pickRound;
	private int[] pickSequence;

	private int[] surrender;

	private long startTime;
	private long timeLastPick;
	private boolean pickReminderSent;
	private List<DiscordMessage> pickMessages = new ArrayList<DiscordMessage>();

	private PickupLogic logic;

	public ArrayList<Bet> bets;
	public int payWin = 50;
	public int payLose = 25;

	private Match() {
		playerStats = new HashMap<Player, MatchStats>();
		teamList = new HashMap<String, List<Player>>();
		teamList.put("red", new ArrayList<Player>());
		teamList.put("blue", new ArrayList<Player>());
		mapVotes = new HashMap<GameMap, Integer>();
		sortedPlayers = new ArrayList<Player>();
		captainTurn = 1;
		pickRound = 0;
		pickSequence = new int[] {1, 0, 1, 0, 1, 0, 1, 0};//{1, 0, 0, 1, 0, 1, 1, 0};
		threadChannels = new ArrayList<DiscordChannel>();
		liveScoreMsgs = new ArrayList<DiscordMessage>();
		squadList = new ArrayList<Team>();
		bets = new ArrayList<Bet>();
	}

	public Match(PickupLogic logic, Gametype gametype, List<GameMap> maplist) {
		this();
		this.logic = logic;
		this.gametype = gametype;

		for (GameMap m : maplist) {
			mapVotes.put(m, 0);
		}
		state = MatchState.Signup;

		surrender = new int[] { gametype.getTeamSize() - 1, gametype.getTeamSize() - 1};
	}

	public Match(int id, long startTime, GameMap map, int[] score, int[] elo,
			Map<String, List<Player>> teamList, MatchState state, Gametype gametype, Server server,
			Map<Player, MatchStats> playerStats, PickupLogic logic) {
		this();
		this.id = id;
		this.startTime = startTime;
		this.map = map;
		this.score = score;
		this.elo = elo;
		this.teamList = teamList;
		this.state = state;
		this.gametype = gametype;
		this.server = server;
		this.playerStats = playerStats;
		this.logic = logic;

		if (server == null){
			abort();
			return;
		}
		
		if (!isOver()) {
			server.startMonitoring(this);
		}

		surrender = new int[] { gametype.getTeamSize() - 1, gametype.getTeamSize() - 1};
	}

	public void reset() {
		if (state == MatchState.Signup) {
			resetSignup();
		} else if (state == MatchState.AwaitingServer) {
			resetAwaitingServer();
		} else if (state == MatchState.Live) {
			resetLive();
		}
		refundBets();
	}

	private void resetSignup() {
		// reset mapvote
		for(Player p : playerStats.keySet()) {
			p.resetVotes();
		}
		mapVotes.replaceAll((m, v) -> 0);

		if(!threadChannels.isEmpty()){
			for (DiscordChannel threadChannel : threadChannels){
				threadChannel.delete();
			}
			threadChannels = new ArrayList<DiscordChannel>();
		}
		
		playerStats.clear();
	}

	private void resetAwaitingServer() {
		logic.cancelRequestServer(this);
		resetSignup();
		state = MatchState.Signup;
	}

	private void resetLive() {
		abort();
	}

	public void addPlayer(Player player) {
		if (state == MatchState.Signup && !isInMatch(player)) {
			playerStats.put(player, new MatchStats());
			checkReadyState(player);
		}
	}

	public void removePlayer(Player player, boolean shouldSpam) {
		if ((state == MatchState.Signup || state == MatchState.AwaitingServer) && isInMatch(player)) {
			GameMap map = player.getVotedMap(gametype);
			if (map != null) {
				mapVotes.put(map, mapVotes.get(map) - 1);
				player.voteMap(gametype, null);
			}
			playerStats.remove(player);
			checkServerState();
			logic.cmdStatus(this, player, shouldSpam);
		}
	}

	public void addSquad(Team squad) {
		if (state == MatchState.Signup) {
			squadList.add(squad);
		}
	}

	public void removeSquad(Team squad) {
		if (state == MatchState.Signup) {
			squadList.remove(squad);
		}
	}

	public void voteMap(Player player, GameMap map, int number, boolean bonus) {
		if ((state == MatchState.Signup || state == MatchState.AwaitingServer) && (isInMatch(player) || sortedPlayers.contains(player))) {
			GameMap oldMap = player.getVotedMap(gametype);
			if (oldMap != null) {
				if (!bonus){
					mapVotes.put(oldMap, mapVotes.get(oldMap) - 1);
					player.voteMap(gametype, null);
				}
			}
			mapVotes.put(map, mapVotes.get(map) + number);
			player.voteMap(gametype, map);
			String msg = Config.pkup_map;
			msg = msg.replace(".map.", map.name);
			msg = msg.replace(".count.", String.valueOf(mapVotes.get(map)));
			if (bonus){
				player.setAdditionalMapVotes(0);
				msg = Config.used_additonal_vote;
				msg = msg.replace(".player.", player.getDiscordUser().getMentionString());
				msg = msg.replace(".vote.", String.valueOf(number));
				msg = msg.replace(".map.", map.name);
				msg = msg.replace(".count.", String.valueOf(mapVotes.get(map)));
				logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), msg);
				return;
			}
			logic.bot.sendNotice(player.getDiscordUser(), msg);
		} else {
			logic.bot.sendNotice(player.getDiscordUser(), Config.map_cannot_vote);
		}
	}

	public void voteSurrender(Player player) {
		long timeUntilSurrender = (startTime + 180000L) - System.currentTimeMillis(); // 3min in milliseconds
		if (timeUntilSurrender < 0) {
			if (!player.hasVotedSurrender()) {
				player.voteSurrender();
				int teamnum = getTeam(player).equals("red") ? 0 : 1;
				surrender[teamnum]--;
				checkSurrender();
				if (!isOver()) {
					String msg = Config.pkup_surrender_cast;
					msg = msg.replace(".num.", String.valueOf(surrender[teamnum]));
					msg = msg.replace(".s.", surrender[teamnum] > 1 ? "s" : "");
					logic.bot.sendNotice(player.getDiscordUser(), msg);
				}
			} else {
				logic.bot.sendNotice(player.getDiscordUser(), Config.player_already_surrender);
			}
		} else {
			timeUntilSurrender /= 1000d;
			String min = String.valueOf((int) (Math.floor(timeUntilSurrender / 60d)));
			String sec = String.valueOf((int) (Math.floor(timeUntilSurrender % 60d)));
			min = min.length() == 1 ? "0" + min : min;
			sec = sec.length() == 1 ? "0" + sec : sec;
			String msg = Config.pkup_surrender_time;
			msg = msg.replace(".time.", min + ":" + sec);
			logic.bot.sendNotice(player.getDiscordUser(), msg);
		}
	}

	public void checkSurrender() {
		for (int i = 0; i < 2; ++i) {
			if (surrender[i] <= 0) {
				state = MatchState.Surrender;
				try {
					if (server != null && server.getServerMonitor() != null) {
						server.getServerMonitor().surrender(i);
					}
				} catch (Exception e) {
					LOGGER.log(Level.WARNING, "Exception: ", e);
					Sentry.capture(e);
				}
				cleanUp();
				logic.db.saveMatch(this);
				sendAftermath();
				logic.matchRemove(this);
			}
		}
	}

	public void checkReadyState(Player player) {
		if (playerStats.keySet().size() == gametype.getTeamSize() * 2
			|| (gametype.getTeamSize() == 0 && playerStats.keySet().size() == 1)) {
			state = MatchState.AwaitingServer;
			logic.cmdStatus(this, player, true);
			
			// Compute region majority
			logic.requestServer(this);
		} else {
			logic.cmdStatus(this, player, true);
		}
	}

	public void checkServerState() {
		if (state == MatchState.AwaitingServer && (playerStats.keySet().size() != gametype.getTeamSize() * 2
			|| (gametype.getTeamSize() == 0 && playerStats.keySet().size() != 1))) {
			state = MatchState.Signup;
			logic.cancelRequestServer(this);
		}
	}

	public void abort() {
		state = MatchState.Abort;
		cleanUp();
		logic.db.saveMatch(this);

//		for (DiscordChannel threadChannel : threadChannels){
//			threadChannel.archive();
//		}

		if (gtvServer != null) {
			gtvServer.free();
			gtvServer.sendRcon("gtv_disconnect 1");
		}
		refundBets();
	}

	public void abandon(Status status, List<Player> involvedPlayers) {
		state = MatchState.Abandon;
		cleanUp();
		logic.db.saveMatch(this);

//		for (DiscordChannel threadChannel : threadChannels){
//			threadChannel.archive();
//		}
		
		if (gtvServer != null) {
			gtvServer.free();
			gtvServer.sendRcon("gtv_disconnect 1");
		}

		sendAftermath(status, involvedPlayers);
		logic.matchRemove(this);
		refundBets();
	}

	public void end() {
		state = MatchState.Done;
		if (server.getServerMonitor().noMercyIssued){
			state = MatchState.Mercy;
			payWin = 75;
		}
		cleanUp();

		logic.db.saveMatch(this);
		// Update player stats
		for (Player p : playerStats.keySet()){
			p.stats = logic.db.getPlayerStats(p, logic.currentSeason);
			p.setRank(logic.db.getRankForPlayer(p));
		}

//		for (DiscordChannel threadChannel : threadChannels){
//			threadChannel.archive();
//		}
		
		if (gtvServer != null) {
			gtvServer.free();
			gtvServer.sendRcon("gtv_disconnect 1");
		}

		sendAftermath();
		logic.matchRemove(this);
		if (gametype.getTeamSize() > 0){
			payPlayers();
		}
	}

	public void cancelStart() {
		state = MatchState.Abort;
		cleanUp();
		logic.matchStarted(this);
		logic.matchRemove(this);
	}

	private void cleanUp() {
		for(Player p : playerStats.keySet()) {
			p.resetVotes();
		}
		if (server != null){
			server.free();
		}

		logic.matchEnded();
	}

	private void sendAftermath() {
		StringBuilder fullmsg = new StringBuilder(Config.pkup_aftermath_head);
		fullmsg = new StringBuilder(fullmsg.toString().replace(".gamenumber.", String.valueOf(id)).replace(".gametype.", gametype.getName()).replace(".map.", map.name));
		for (int i = 0; i < 2; ++i) {
			int opp = (i + 1) % 2;
			String teamname = (i == 0) ? "Red" : "Blue";
			StringBuilder msg = new StringBuilder(Config.pkup_aftermath_result);
			msg = new StringBuilder(msg.toString().replace(".team.", teamname));
			if (score[i] > score[opp]) {
				msg = new StringBuilder(msg.toString().replace(".result.", "won"));
			} else if (score[i] < score[opp]) {
				msg = new StringBuilder(msg.toString().replace(".result.", "lost"));
			} else {
				msg = new StringBuilder(msg.toString().replace(".result.", "draw"));
			}
			msg = new StringBuilder(msg.toString().replace(".score.", score[i] + "-" + score[opp]));
			
			for (Player p : teamList.get(teamname.toLowerCase())) {
				String msgelo = Config.pkup_aftermath_player;
				msgelo = msgelo.replace(".player.", p.getDiscordUser().getMentionString());
				String elochange = ((p.getEloChange() >= 0) ? "+" : "") + p.getEloChange();
				if (p.hasBoostActive()){
					elochange = "**" + ((p.getEloChange() > 0) ? "+" : "") + p.getEloChange() + "** ``BOOST``";
				}
				msgelo = msgelo.replace(".elochange.", elochange);
				msg.append(" ").append(msgelo);
			}
			if (gametype.getTeamSize() > 0){
				if (score[i] > score[opp]) {
					msg.append(" Team reward: <:pugcoin_bronze:1081604558381400064> ``" + payWin + "``");
				}
				else{
					msg.append(" Team reward: <:pugcoin_bronze:1081604558381400064> ``" + payLose + "``");
				}
			}

			fullmsg.append("\n").append(msg);
		}
		for (Player player : getPlayerList()) {
			if (player.didChangeRank()) {
				String msg = Config.pkup_aftermath_rank;
				msg = msg.replace(".player.", player.getDiscordUser().getMentionString());
				msg = msg.replace(".updown.", player.getEloChange() > 0 ? "up" : "down");
				msg = msg.replace(".rank.", player.getRank().getEmoji());
				fullmsg.append("\n").append(msg);
			}
		}
		
		logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), fullmsg.toString(), getMatchEmbed(false));
	}

	private void sendAftermath(Status status, List<Player> involvedPlayers) {
		String fullmsg = Config.pkup_aftermath_head;
		fullmsg = fullmsg.replace(".gametype.", gametype.getName());
		fullmsg = fullmsg.replace(".gamenumber.", String.valueOf(id));
		fullmsg = fullmsg.replace(".map.", map.name);

		String msg = Config.pkup_aftermath_abandon_1;
		msg = msg.replace(".reason.", status.name());
		fullmsg += "\n" + msg;

		if (!involvedPlayers.isEmpty()) { // only if we have people who left	
			StringBuilder playerlist = new StringBuilder();
			for (Player player : involvedPlayers) {
				if (playerlist.length() > 0) {
					playerlist.append(" ");
				}
				playerlist.append(player.getUrtauth());
			}
			String be = involvedPlayers.size() == 1 ? "was" : "were";

			msg = Config.pkup_aftermath_abandon_2;
			msg = msg.replace(".players.", playerlist.toString());
			msg = msg.replace(".be.", be);
			fullmsg += "\n" + msg;
		}

		logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), fullmsg);
	}

	public void launch(Server server) {

		if (!isOver() && state != MatchState.Live) {
			// Need to set temp id here to cancel games during draft
			id = logic.db.getLastMatchID() + 1;
			this.server = server;
			server.take();
			server.matchid = id;

			for (Player player : getPlayerList()) {				
				for (Match m : logic.playerInMatch(player)) {
					if (m == this || (gametype.getTeamSize() <= 2 && m.getGametype().getTeamSize() > 2)){
						continue;
					}
					if (gametype.getTeamSize() > 2 && m.getGametype().getTeamSize() <= 2 && m.state == MatchState.Live){
						m.reset();
						continue;
					}
					m.removePlayer(player, false);
				}
			}

			String threadTitle = Config.pkup_go_pub_threadtitle;
			threadTitle = threadTitle.replace(".ID.", String.valueOf(logic.db.getLastMatchID() + 1));

			for (DiscordChannel publicChannel : logic.getChannelByType(PickupChannelType.PUBLIC)){
				threadChannels.add(logic.bot.createThread(publicChannel, threadTitle));
			}

			logic.matchStarted(this);
			timeLastPick = System.currentTimeMillis();
			sortPlayers();

		}
	}

	public void sortPlayers() {
		// Sort players by elo
		List<Player> playerList = new ArrayList<Player>(playerStats.keySet());

		if (squadList.size() > 0){
			Team squad = squadList.get(0);
			for (Player player : playerList){
				if (squad.isInTeam(player)){
					teamList.get("red").add(player);
				}
				else {
					teamList.get("blue").add(player);
				}
			}
			sortedPlayers.clear();
			checkTeams();
			return;
		}

		sortedPlayers.add(playerList.get(0));
		for (Player player : playerList) {
			for (Player sortedPlayer : sortedPlayers) {
				if (player.getCaptainScore(gametype) >= sortedPlayer.getCaptainScore(gametype) && !player.equals(sortedPlayer)) {
					sortedPlayers.add(sortedPlayers.indexOf(sortedPlayer), player);
					break;
				}
			}
			if (!sortedPlayers.contains(player)) {
				sortedPlayers.add(player);
			}
		}
		if (gametype.getTeamSize() != 0) {
			captains[0] = sortedPlayers.get(0);
			teamList.get("red").add(captains[0]);

			captains[1] = sortedPlayers.get(1);
			teamList.get("blue").add(captains[1]);
		} else{
			captains[0] = sortedPlayers.get(0);
			teamList.get("blue").add(captains[0]);
		}

		if (sortedPlayers.size() > 2){
			String captainAnnouncement = Config.pkup_go_pub_captains;
			captainAnnouncement = captainAnnouncement.replace(".captain1.", captains[0].getDiscordUser().getMentionString());
			captainAnnouncement = captainAnnouncement.replace(".captain2.", captains[1].getDiscordUser().getMentionString());
			// List players stats
			for (Player p : sortedPlayers){
				captainAnnouncement += "\n" + logic.cmdGetElo(p, gametype);
				if (logic.getDynamicServers()){
					captainAnnouncement += " " + String.valueOf("\t " + server.playerPing.get(p)) + "ms";
				}
			}
			if (logic.getDynamicServers()){
				captainAnnouncement +="\n**Server:** " + Country.getCountryFlag(server.country) + "``" + server.city + "``";
			}
			else{
				captainAnnouncement +="\n**Server:** " + server.getRegionFlag(false, false) + "``" + server.region.name() + "``";
			}
			logic.bot.sendMsg(threadChannels, captainAnnouncement);

			String captainDm = Config.pkup_go_captains;
			logic.bot.sendMsg(captains[0].getDiscordUser(), captainDm);
			logic.bot.sendMsg(captains[1].getDiscordUser(), captainDm);
		}

		sortedPlayers.remove(0);
		if (gametype.getTeamSize() != 0){
			sortedPlayers.remove(0);
		}
		
		checkTeams();
		/*
		
		if (true) { // WIP
			// 5: i=0,1 => red: (0, 9) (2, 7) -> blue: (1, 8) (3, 6)   if cond: (4, 5)
			// 4: i=0,1 => red: (0, 7) (2, 5) -> blue: (1, 7) (3, 4)   if cond: none
			// 3: i=0   => red: (0, 5)        -> blue: (1, 4)          if cond: (2, 3)
			// 2: i=0   => red: (0, 3)        -> blue: (1, 2)          if cond: none
			// 1: i=-   => red: none          -> blue: none            if cond: (0, 1)
			for (int i = 0; i < Math.floor(gametype.getTeamSize() / 2); ++i) {
				teamList.get("red").add(sortPlayers.get(i*2));
				teamList.get("red").add(sortPlayers.get(((gametype.getTeamSize()*2)-1)-i*2));

				teamList.get("blue").add(sortPlayers.get(i*2+1));
				teamList.get("blue").add(sortPlayers.get(((gametype.getTeamSize()*2)-1)-i*2-1));
			}

			if ((gametype.getTeamSize() % 2) == 1) {
				int better = gametype.getTeamSize() - 1;
				int worse = gametype.getTeamSize();

				// compute avg elo up till now
				elo = new int[] {0, 0};
				for (Player p : teamList.get("red")) elo[0] += p.getElo();
				for (Player p : teamList.get("blue")) elo[1] += p.getElo();
				elo[0] /= Math.max(1, gametype.getTeamSize() - 1);
				elo[1] /= Math.max(1, gametype.getTeamSize() - 1);

				if (elo[0] > elo[1]) {
					teamList.get("red").add(sortPlayers.get(worse));
					teamList.get("blue").add(sortPlayers.get(better));
				} else {
					teamList.get("red").add(sortPlayers.get(better));
					teamList.get("blue").add(sortPlayers.get(worse));
				}
			}
			
			logic.matchStarted(this);
			
			// do important changes that affect possibly other matches/servers/playerlists outside the thread!
			
			new Thread(this).start();
			
		}
		*/
	}
	
	public void checkTeams() {
		if (sortedPlayers.isEmpty() && state == MatchState.AwaitingServer) {
			state = MatchState.Live;
			new Thread(this).start(); // do important changes that affect possibly other matches/servers/playerlists outside the thread!
		}
		else {
			List<DiscordComponent> buttons = new ArrayList<DiscordComponent>();
			int choiceNumber = 10;
			if (sortedPlayers.size() < choiceNumber) {
				choiceNumber = sortedPlayers.size();
			}
			for (int i = 0; i < choiceNumber; i++) {
				DiscordButton button = new DiscordButton(DiscordButtonStyle.BLURPLE);
				button.custom_id = Config.INT_PICK + "_" + i;
				button.label = sortedPlayers.get(i).getUrtauth() + " (" + sortedPlayers.get(i).getElo() + ")";
				button.emoji = sortedPlayers.get(i).getRank().getEmojiJSON();
				buttons.add(button);
			}
			
			// Include in the choices players that played less than 10 games to allow for new player skill uncertainty
			if (choiceNumber < sortedPlayers.size()) {
				for (int i = choiceNumber; i < sortedPlayers.size(); i++) {
					int matchPlayed = logic.db.getNumberOfGames(sortedPlayers.get(i));
					if (matchPlayed < 30) {
						DiscordButton button = new DiscordButton(DiscordButtonStyle.GREY);
						button.custom_id = Config.INT_PICK + "_" + i;
						button.label = sortedPlayers.get(i).getUrtauth();
						button.emoji = new JSONObject().put("name", "\u2753");
						buttons.add(button);
					}
				}
			}
			
			String pickPromptMsg = Config.pkup_go_pub_pick;
			pickPromptMsg = pickPromptMsg.replace(".captain.", captains[captainTurn].getDiscordUser().getMentionString());
			pickMessages = logic.bot.sendMsgToEdit(threadChannels, pickPromptMsg, null, buttons);
		}
	}

	public boolean isCaptainTurn(Player player) {
		if (captains[captainTurn] == null){
			return false;
		}
		return captains[captainTurn].getUrtauth().equals(player.getUrtauth());
	}

	public Player getCaptainsTurn() {
		return captains[captainTurn];
	}

	public void pick(Player captain, int pick) {
		String pickMsg = Config.pkup_go_pub_pickjoin;
		pickMsg = pickMsg.replace(".pick.", sortedPlayers.get(pick).getDiscordUser().getMentionString());
		
		if (captain.getUrtauth().equals(captains[0].getUrtauth())) {
			teamList.get("red").add(sortedPlayers.get(pick));
			pickMsg = pickMsg.replace(".color.", "red");
		}
		else {
			teamList.get("blue").add(sortedPlayers.get(pick));
			pickMsg = pickMsg.replace(".color.", "blue");
		}
		
		sortedPlayers.remove(pick);
		logic.bot.sendMsg(threadChannels, pickMsg);

		if (!sortedPlayers.isEmpty()){
			pickRound++;
			captainTurn = pickSequence[pickRound];
		}

		timeLastPick = System.currentTimeMillis();

		for (DiscordMessage pickMessage : pickMessages){
			pickMessage.delete();
		}
		pickMessages.clear();

		
		if (sortedPlayers.size() == 1) {
			pick(captains[captainTurn], 0);
			return;
		}
		checkTeams();
	}

	public long getTimeLastPick() {
		return timeLastPick;
	}

	public boolean getPickReminderSent() {
		return pickReminderSent;
	}

	public void setPickReminderSent(boolean reminderSent) {
		pickReminderSent = reminderSent;
	}


	// DONT CALL THIS OUTSIDE OF launch() !!!
	public void run() {
		startTime = System.currentTimeMillis();
		gtvServer = logic.setupGTV();
		Random rand = new Random();

		if (!logic.getDynamicServers() && gametype.getTeamSize() > 0){
			int password = rand.nextInt((999999-100000) + 1) + 100000;
			server.password = String.valueOf(password);
			LOGGER.info("Password: " + server.password);
		}

		// Get most voted map
		List<GameMap> mapList = getMostMapVotes();
		if (mapList.size() == 0) {
			logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), "ERROR: NO MAP FOR GAMETYPE");
			cancelStart();
			return;
		}
		this.map = mapList.size() == 1 ? mapList.get(0) : mapList.get(rand.nextInt(mapList.size()-1));
		LOGGER.info("Map: " + this.map.name);

		// avg elo
		elo = new int[] {0, 0};
		if (gametype.getTeamSize() != 0){
			for (Player p : teamList.get("red")) elo[0] += p.getElo();
			for (Player p : teamList.get("blue")) elo[1] += p.getElo();
			elo[0] /= gametype.getTeamSize();
			elo[1] /= gametype.getTeamSize();
		}

		LOGGER.info("Team Red: " + elo[0] + " " + Arrays.toString(teamList.get("red").toArray()));
		LOGGER.info("Team Blue: " + elo[1] + " " + Arrays.toString(teamList.get("blue").toArray()));

		id = logic.db.createMatch(this);
		server.matchid = id;

		// MESSAGE HYPE

		String msg = Config.pkup_go_pub_head;
		msg = msg.replace(".gamenumber.", String.valueOf(id));
		msg = msg.replace(".gametype.", gametype.getName());
		msg = msg.replace(".elo.", String.valueOf((elo[0] + elo[1])/2));
		if (logic.getDynamicServers() || gametype.getTeamSize() == 0){
			msg = msg.replace(".region.", Country.getCountryFlag(server.country) + " ``" + server.city + "``");
		} else if (server.region == Region.NAE || server.region == Region.NAW) {
			msg = msg.replace(".region.", ":flag_us:");
		} else if (server.region == Region.EU) {
			msg = msg.replace(".region.", ":flag_eu:");
		} else if (server.region == Region.OC) {
			msg = msg.replace(".region.", ":flag_au:");
		} else if (server.region == Region.SA) {
			msg = msg.replace(".region.", ":flag_br:");
		} else {
			msg = msg.replace(".region.", server.region.name());
		}
		StringBuilder fullmsg = new StringBuilder(msg);

		msg = Config.pkup_map_list;
		msg = msg.replace(".gametype.", gametype.getName());			
		if (getMostMapVotes().size() == mapVotes.keySet().size()) {
			msg = msg.replace(".maplist.", "NO VOTES - RANDOM");
		}
		else {
			msg = msg.replace(".maplist.", getMapVotes(true));
		}
		fullmsg.append("\n").append(msg);

		String[] teamname = {"Red", "Blue"};
		if (gametype.getTeamSize() == 0){
			teamname = new String[]{"Blue"};
		}
		for (String team : teamname) {
			StringBuilder playernames = new StringBuilder();
			for (Player p : teamList.get(team.toLowerCase())) {
				if (!playernames.toString().equals("")) {
					playernames.append(" ");
				}
				playernames.append(p.getDiscordUser().getMentionString());
			}
			msg = Config.pkup_go_pub_team;
			msg = msg.replace(".team.", team.equals("Red") ? "<:rush_red:510982162263179275>  Red: " : "<:rush_blue:510067909628788736>  Blue:");
			msg = msg.replace(".gametype.", gametype.getName());
			msg = msg.replace(".playerlist.", playernames.toString());
			fullmsg.append("\n").append(msg);
		}

		msg = Config.pkup_go_pub_map;
		msg = msg.replace(".map.", this.map.name);
		msg = msg.replace(".gametype.", gametype.getName());
		fullmsg.append("\n").append(msg);


		msg = Config.pkup_go_pub_calm;
		if (gtvServer == null) {
			msg = Config.pkup_go_pub_calm_notavi;
		}
		//msg = msg.replace(".elo.", String.valueOf((elo[0] + elo[1])/2));
		fullmsg.append("\n").append(msg);

		ArrayList<DiscordComponent> buttons = null;
		if (gametype.getTeamSize() > 1){
			computeOdds();

			buttons = new ArrayList<DiscordComponent>();
			JSONObject emojiRed = new JSONObject();
			emojiRed.put("name", "helmet_red");
			emojiRed.put("id", "900477396237549620");

			JSONObject emojiBlue = new JSONObject();
			emojiBlue.put("name", "helmet_blue");
			emojiBlue.put("id", "900477396573110282");

			DiscordButton buttonBetRed = new DiscordButton(DiscordButtonStyle.GREY);
			buttonBetRed.emoji = emojiRed;
			buttonBetRed.label = "Bet red (" + String.format("%.02f", odds[0]) + ")";
			buttonBetRed.custom_id = "showbet_red_" + id;

			DiscordButton buttonBetBlue = new DiscordButton(DiscordButtonStyle.GREY);
			buttonBetBlue.emoji = emojiBlue;
			buttonBetBlue.label = "Bet blue (" + String.format("%.02f", odds[1]) + ")";
			buttonBetBlue.custom_id = "showbet_blue_" + id;

			buttons.add(buttonBetRed);
			buttons.add(buttonBetBlue);
		}

		logic.bot.sendMsgToEdit(logic.getChannelByType(PickupChannelType.PUBLIC), fullmsg.toString(), null, buttons);

		if (logic.getDynamicServers() || gametype.getTeamSize() == 0){
			FtwglAPI.getSpawnedServerIp(server);
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				LOGGER.log(Level.WARNING, "Exception: ", e);
				Sentry.capture(e);
			}
		}

		buttons = new ArrayList<DiscordComponent>();
		DiscordButton button = new DiscordButton(DiscordButtonStyle.GREEN);
		button.custom_id = Config.INT_LAUNCHAC + "_" + String.valueOf(id) + "_" + server.getAddress() + "_" + server.password;
		button.label = Config.BTN_LAUNCHAC;
		buttons.add(button);

		msg = Config.pkup_go_player;
		msg = msg.replace(".server.", server.getAddress());
		msg = msg.replace(".password.", server.password);
		for (String team : teamList.keySet()) {
			for (Player player : teamList.get(team)) {
				if (player.getEnforceAC()){
					logic.bot.sendMsgToEdit(player.getDiscordUser().getDMChannel(), Config.pkup_go_player_ac, null, buttons);
					continue;
				}
				String msg_t = msg.replace(".team.", team.toUpperCase());
				logic.bot.sendMsg(player.getDiscordUser(), msg_t);
			}
		}

		msg = Config.pkup_go_pub_sent;
		msg = msg.replace(".gametype.", gametype.getName());
		logic.bot.sendMsgToEdit(logic.getChannelByType(PickupChannelType.PUBLIC), msg, null, buttons);

		// set server data
		server.sendRcon("kick allbots");
		server.sendRcon("g_password " + server.password);
		for (String s : this.gametype.getConfig()) {
			server.sendRcon(s);
		}
		server.sendRcon("map " + this.map.name);
		server.sendRcon("g_warmup 10");
		
		//logic.setLastMapPlayed(gametype, map);
		
		if (gtvServer != null) {
			gtvServer.sendRcon("gtv_connect " + server.getAddress() + "  " + server.password);
		}
		
		server.startMonitoring(this);

		liveScoreMsgs = logic.bot.sendMsgToEdit(threadChannels, "", getMatchEmbed(false), null);
	}

	List<GameMap> getMostMapVotes() {
		List<GameMap> mapList = new ArrayList<GameMap>();
		int currentVotes = -1;
		for (GameMap map : mapVotes.keySet()) {
			if (currentVotes == -1) {
				mapList.add(map);
				currentVotes = mapVotes.get(map);
				continue;
			}

			if (mapVotes.get(map) > currentVotes) {
				mapList.clear();
				mapList.add(map);
				currentVotes = mapVotes.get(map);
			} else if (mapVotes.get(map) == currentVotes && !map.equals(logic.getLastMapPlayed(gametype)) && map.bannedUntil < System.currentTimeMillis()) {
				mapList.add(map);
			}
		}
		return mapList;
	}

	public String getMapVotes(boolean skipNull) {
		
		List<GameMap> mostMapVotes = getMostMapVotes();
		StringBuilder msg = new StringBuilder("None");
		for (GameMap map : mapVotes.keySet()) {
			if (skipNull && mapVotes.get(map) == 0 && !logic.getLastMapPlayed(gametype).name.equals(map.name) && map.bannedUntil < System.currentTimeMillis()) continue;
			if (msg.toString().equals("None")) {
				msg = new StringBuilder();
			} else {
				msg.append(" - ");
			}
			LOGGER.info(logic.getLastMapPlayed(gametype).name + " " + map.name);
			if (logic.getLastMapPlayed(gametype).name.equals(map.name)) {
				String mapString = "~~" + map.name + "~~";
				msg.append(mapString);
			}
			else if (map.bannedUntil >= System.currentTimeMillis()){
				String mapString = "~~" + map.name + "~~ (Expires <t:" + map.bannedUntil / 1000 + ":R>)";
				msg.append(mapString);
			} else {
				String mapString = map.name + ": " + mapVotes.get(map);
				if (mostMapVotes.size() < mapVotes.keySet().size() && mostMapVotes.contains(map)) {
					mapString = "**" + mapString + "**";
				}
				msg.append(mapString);
			}
		}
		return msg.toString();
	}

	public boolean isOver() {
		return state == MatchState.Done || state == MatchState.Abort || state == MatchState.Abandon || state == MatchState.Surrender || state == MatchState.Mercy;
	}

	public MatchState getMatchState() {
		return state;
	}

	public Gametype getGametype() {
		return gametype;
	}

	public boolean isInMatch(Player player) {
		return playerStats.containsKey(player);
	}

	public Set<GameMap> getMapList() {
		return mapVotes.keySet();
	}

	public int getID() {
		return id;
	}

	public void setLogic(PickupLogic logic) {
		this.logic = logic;
	}

	public PickupLogic getLogic() {
		return logic;
	}

	public Server getServer() {
		return server;
	}

	public long getStartTime() {
		return startTime;
	}

	public GameMap getMap() {
		return map;
	}

	public int[] getElo() {
		return elo;
	}

	public int getEloRed() {
		return elo[0];
	}

	public int getEloBlue() {
		return elo[1];
	}

	public void setScore(int[] score) {
		this.score = score;
	}

	public List<Player> getTeamRed() { return teamList.get("red"); }

	public List<Player> getTeamBlue() {
		return teamList.get("blue");
	}

	public List<Player> getPlayerList() {
		return new ArrayList<Player>(playerStats.keySet());
	}

	public String getTeam(Player player) {
		for(String team : teamList.keySet()) {
			if (teamList.get(team).contains(player)) {
				return team;
			}
		}
		return null;
	}

	public int getScoreRed() {
		return score[0];
	}

	public int getScoreBlue() {
		return score[1];
	}

	public MatchStats getStats(Player player) {
		return playerStats.get(player);
	}

	public int getPlayerCount() {
		return playerStats.keySet().size();
	}

	public String getIngameInfo() {
		String info;
		if (state == MatchState.AwaitingServer || server.getServerMonitor() == null) {
			info = "Captains's pick";
		} else if (state == MatchState.Live) {
			ServerState serverState = server.getServerMonitor().getState();
			info = serverState.name();
			if (serverState == ServerState.LIVE) {
				info += " - " + server.getServerMonitor().getGameTime();
			}
			info += " (" + server.getServerMonitor().getScore() + ")";
			return info;
		} else {
			info = state.name();
			info += " (" + score[0] + "-" + score[1] + ")";
		}
		return info;
	}

	public String getMatchInfo() {
		
		StringBuilder redplayers = new StringBuilder("None");
		for (Player p : teamList.get("red")) {
			if (redplayers.toString().equals("None")) {
				redplayers = new StringBuilder(p.getUrtauth());
			} else {
				redplayers.append(" ").append(p.getUrtauth());
			}
		}
		StringBuilder blueplayers = new StringBuilder("None");
		for (Player p : teamList.get("blue")) {
			if (blueplayers.toString().equals("None")) {
				blueplayers = new StringBuilder(p.getUrtauth());
			} else {
				blueplayers.append(" ").append(p.getUrtauth());
			}
		}

		String msg = Config.pkup_match_print_info;
		msg = msg.replace(".gamenumber.", id == 0 ? String.valueOf(logic.db.getLastMatchID() + 1) : String.valueOf(id));
		if (gametype.getPrivate()) {
			msg = msg.replace(".gametype.", ":lock:" + gametype.getName().toUpperCase());
		} else {
			msg = msg.replace(".gametype.", gametype.getName().toUpperCase());
		}
		msg = msg.replace(".map.", map != null ? map.name : "ut4_?");
		msg = msg.replace(".redteam.", redplayers.toString());
		msg = msg.replace(".blueteam.", blueplayers.toString());
		msg = msg.replace(".ingame.", getIngameInfo());

		return msg;
	}
	
	public DiscordEmbed getMatchEmbed(boolean forceNoDynamic) {
		ServerState serverState = null;
		if (server != null && server.getServerMonitor() != null && server.isTaken() ) {
			serverState = server.getServerMonitor().getState();
		}
		
		DiscordEmbed embed = new DiscordEmbed();

		String region_flag = ":globe_with_meridians:";
		if (server != null) {
			region_flag = server.getRegionFlag(logic.getDynamicServers() || gametype.getTeamSize() == 0, forceNoDynamic);
		}
		
		if (serverState == ServerState.LIVE && state == MatchState.Live && server != null) {
			embed.title  = region_flag + " Match #" + id + " (" + server.getServerMonitor().getGameTime() + ")";
		}
		else {
			embed.title = region_flag + " Match #" + id ;
		}
		
		embed.color = 7056881;
		if (gametype.getPrivate()) {
			embed.description = map != null ? ":lock: **" + gametype.getName().toUpperCase() + "** - [" + map.name + "](https://maps.pugbot.net/q3ut4/" + map.name + ".pk3)" : "null";
		} else {
			embed.description = map != null ? "**" + gametype.getName() + "** - [" + map.name + "](https://maps.pugbot.net/q3ut4/" + map.name + ".pk3)" : "null";
		}

		
		StringBuilder red_team_player_embed = new StringBuilder();
		StringBuilder red_team_score_embed = new StringBuilder();
		StringBuilder red_team_ping_embed = new StringBuilder();
		StringBuilder blue_team_player_embed = new StringBuilder();
		StringBuilder blue_team_score_embed = new StringBuilder();
		StringBuilder blue_team_ping_embed = new StringBuilder();
		
		// Order teams scores by score
		List<Map.Entry<Player, MatchStats>> entries = new ArrayList<Map.Entry<Player, MatchStats>>(playerStats.entrySet());
		entries.sort((a, b) -> Integer.compare(
				b.getValue().score[0].score + b.getValue().score[1].score,
				a.getValue().score[0].score + a.getValue().score[1].score));
		
		for (Map.Entry<Player, MatchStats> entry : entries) {
			String country;
			if( entry.getKey().getCountry().equalsIgnoreCase("NOT_DEFINED")) {
				country =  "<:puma:849287183474884628>";
			}
			else {
				country = ":flag_" + entry.getKey().getCountry().toLowerCase() + ":";
			}
			String player_row = country + " \u200b \u200b " +  entry.getKey().getUrtauth() + "\n";
			int score = entry.getValue().score[0].score + entry.getValue().score[1].score;
			int deaths = entry.getValue().score[0].deaths + entry.getValue().score[1].deaths;
			int assists = entry.getValue().score[0].assists + entry.getValue().score[1].assists;
			String score_row = score +  "/" + deaths + "/" + assists + "\n";

			String ping_row = "";
			if ((logic.getDynamicServers() || gametype.getTeamSize() == 0) && !forceNoDynamic){
				ping_row = String.valueOf(server.playerPing.get(entry.getKey())) + "\n";
			}
			if (gametype.getTeamSize() != 0 && teamList.get("red").contains(entry.getKey())) {
				red_team_player_embed.append(player_row);
				red_team_score_embed.append(score_row);
				if ((logic.getDynamicServers() || gametype.getTeamSize() == 0) && !forceNoDynamic){
					red_team_ping_embed.append(ping_row);
				}
			}
			else if (teamList.get("blue").contains(entry.getKey())) {
				blue_team_player_embed.append(player_row);
				blue_team_score_embed.append(score_row);
				if ((logic.getDynamicServers() || gametype.getTeamSize() == 0) && !forceNoDynamic){
					blue_team_ping_embed.append(ping_row);
				}
			}
		}
		if (gametype.getTeamSize() != 0) {

			embed.addField("<:rush_red:510982162263179275> \u200b \u200b " + getScoreRed() + "\n \u200b", red_team_player_embed.toString(), true);
			embed.addField("K/D/A" + "\n \u200b", red_team_score_embed.toString(), true);
			if (logic.getDynamicServers() || gametype.getTeamSize() == 0) {
				embed.addField("Ping (ms)" + "\n \u200b", red_team_ping_embed.toString(), true);
			}
			embed.addField("\u200b", "\u200b", false);
		}

		embed.addField("<:rush_blue:510067909628788736> \u200b \u200b " + getScoreBlue() + "\n \u200b", blue_team_player_embed.toString(), true);
		embed.addField("K/D/A" + "\n \u200b", blue_team_score_embed.toString(), true);
		if (logic.getDynamicServers() || gametype.getTeamSize() == 0){
			embed.addField("Ping (ms)" + "\n \u200b", blue_team_ping_embed.toString(), true);
		}

		Date startDate = new Date(startTime);
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		embed.timestamp = df.format(startDate);
		embed.footer = state.name();
		
		return embed;
	}

	public Region getPreferredServerRegion() {
		float euPlayers = 0.0f;
		float ocPlayers = 0.0f;
		float saPlayers = 0.0f;

		for(Player p : playerStats.keySet()) {
			if (p.getRegion() == Region.EU){
				euPlayers++;
			}
			else if (p.getRegion() == Region.OC){
				ocPlayers++;
			}
			else if (p.getRegion() == Region.SA){
				saPlayers++;
			}
		}
		float regionScore = euPlayers - gametype.getTeamSize() * ocPlayers;

		if (ocPlayers > gametype.getTeamSize() * 2 * 0.6){
			return Region.OC;
		}
		else if (saPlayers > gametype.getTeamSize() * 2 * 0.6){
			return Region.SA;
		}
		else if (ocPlayers == 0 && ((euPlayers > gametype.getTeamSize() * 2 * 0.7) || (euPlayers > gametype.getTeamSize() * 2 * 0.6 && (saPlayers + ocPlayers) < 2))){
			return Region.EU;
		}
		else if (regionScore < 0){
			return Region.NAW;
		}
		else {
			return Region.NAE;
		}
	}

	@Override
	public String toString() {
		String msg = "";
		switch(state) {
		case Signup: msg = Config.pkup_match_print_signup; break;
		case AwaitingServer: msg = Config.pkup_match_print_server; break;
		case Live: msg = Config.pkup_match_print_live; break;
		case Done: msg = Config.pkup_match_print_done; break;
		case Mercy: msg = Config.pkup_match_print_done; break;
		case Abort: msg = Config.pkup_match_print_abort; break;
		case Abandon: msg = Config.pkup_match_print_abandon; break;
		case Surrender: msg = Config.pkup_match_print_sur; break;
		default: break;
		}
		
		StringBuilder playernames = new StringBuilder("None");
		for (Player p : playerStats.keySet()) {
			if (playernames.toString().equals("None")) {
				playernames = new StringBuilder(p.getDiscordUser().getMentionString());
			} else {
				playernames.append(" ").append(p.getDiscordUser().getMentionString());
			}
		}
		
		msg = msg.replace(".gamenumber.", String.valueOf(id));
		msg = msg.replace(".gametype.", gametype.getName());
		msg = msg.replace(".map.", map != null ? map.name : "null");
		msg = msg.replace(".elored.", String.valueOf(elo[0]));
		msg = msg.replace(".eloblue.", String.valueOf(elo[1]));
		msg = msg.replace(".playernumber.", String.valueOf(getPlayerCount()));
		int maxplayer = gametype.getTeamSize() == 0 ? 1 : gametype.getTeamSize() * 2;
		msg = msg.replace(".maxplayer.", String.valueOf(maxplayer));
		msg = msg.replace(".playerlist.", playernames.toString());
		msg = msg.replace(".score.", score[0] + " " + score[1]);
		return msg;
	}
	
	public void updateScoreEmbed() {
		if (state == MatchState.Live) {
			score = server.getServerMonitor().getScoreArray();
		}
		for (DiscordMessage liveScoreMsg : liveScoreMsgs) {
			liveScoreMsg.edit(null, getMatchEmbed(false));
		}
	}
	
	public Server getGtvServer() {
		return gtvServer;
	}

	public boolean hasSquads(){
		return squadList.size() > 0;
	}

	public void payPlayers(){

		String winningTeam = "";
		int redPay = payLose;
		int bluePay = payLose;
		if (score[0] > score[1]){
			winningTeam = "red";
			redPay = payWin;
		}
		else if (score[1] > score[0]){
			winningTeam = "blue";
			bluePay = payWin;
		}

		// If game result was corrupted and created a draw
		if (winningTeam.equals("")){
			refundBets();
			return;
		}

		for (Player redP : teamList.get("red")){
			redP.addCoins(redPay);
			redP.saveWallet();
		}
		for (Player blueP : teamList.get("blue")){
			blueP.addCoins(bluePay);
			blueP.saveWallet();
		}

		String betMsg = "";
		for (Bet bet : bets){
			bet.enterResult(bet.color.equals(winningTeam));
			if (bet.won){
				int wonAmount = Math.round(bet.amount * bet.odds);
				JSONObject emoji = Bet.getCoinEmoji(wonAmount);
				String msg = Config.bets_won;
				msg = msg.replace(".player.", bet.player.getDiscordUser().getMentionString());
				msg = msg.replace(".amount.", String.format("%,d", wonAmount));
				msg = msg.replace(".emojiname.", emoji.getString("name"));
				msg = msg.replace(".emojiid.", emoji.getString("id"));
				betMsg = betMsg + msg + '\n';
			}
		}
		if (!betMsg.equals("")){
			logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), betMsg);
		}
		bets.clear();
	}

	public boolean acceptBets(){
		if (gametype.getTeamSize() <= 1){
			return false;
		}
		if (state != MatchState.Live && state != MatchState.AwaitingServer){
			return false;
		}
		return server == null
				|| server.getServerMonitor() == null
				|| server.getServerMonitor().getState() == ServerState.WELCOME
				|| server.getServerMonitor().getState() == ServerState.WARMUP;
	}

	private void computeOdds(){
		float scoreRed = 0.0f;
		for (Player redP : teamList.get("red")){
			scoreRed += redP.getCaptainScore(gametype);
		}
		scoreRed /= gametype.getTeamSize();

		float scoreBlue = 0.0f;
		for (Player blueP : teamList.get("blue")){
			scoreBlue += blueP.getCaptainScore(gametype);
		}
		scoreBlue /= gametype.getTeamSize();

		float scoreAvg = (scoreBlue + scoreRed) / 2.0f;

		odds[0] = (float) Math.pow(10.0f, - (scoreRed - scoreBlue) / scoreAvg) + 1.0f;
		odds[1] = (float) Math.pow(10.0f, - (scoreBlue - scoreRed) / scoreAvg) + 1.0f;
	}

	public float getOdds(int team){
		return odds[team];
	}

	public void refundBets(){
		for (Bet bet : bets){
			bet.refund(this);
			bet.player.saveWallet();
		}
		bets.clear();
	}

	public void banMap(GameMap map){
		if (getMapList().contains(map)){
			mapVotes.put(map, 0);
		}
	}
}
