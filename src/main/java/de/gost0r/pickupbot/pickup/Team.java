package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordButton;
import de.gost0r.pickupbot.discord.DiscordButtonStyle;
import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordComponent;

import java.util.ArrayList;
import java.util.List;

public class Team {
    private PickupLogic logic;

    private int id;
    private Player captain;
    private List<Player> players;
    private List<Player> invitedPlayers;
    private final int maxPlayers = 5;
    private DiscordChannel threadChannel;


    public Team(PickupLogic logic, Player captain) {
        this.logic = logic;

        this.captain = captain;

        players = new ArrayList<Player>();
        invitedPlayers = new ArrayList<Player>();

        threadChannel = this.logic.bot.createThread(captain.getLastPublicChannel(), captain.getUrtauth() + "'s team");

        addPlayer(captain);
    }

    public List<Player> getPlayers() {
        return players;
    }

    public List<Player> getInvitedPlayers() {
        return invitedPlayers;
    }

    public Player getCaptain() {
        return captain;
    }

    public DiscordChannel getThreadChannel() {
        return threadChannel;
    }

    public boolean isInTeam(Player player) {
        return players.contains(player);
    }

    public boolean isInvitedToTeam(Player player) {
        return invitedPlayers.contains(player);
    }

    public void setCaptain(Player newCaptain) {
        if (!players.contains(newCaptain)) {
            addPlayer(newCaptain);
        }
        captain = newCaptain;
    }

    public void addPlayer(Player player) {
        players.add(player);
    }

    public void removePlayer(Player player) {
        players.remove(player);
        logic.bot.sendMsg(threadChannel, Config.team_removed.replace(".player.", player.getDiscordUser().getMentionString()));
    }

    public void invitePlayer(Player player) {
        invitedPlayers.add(player);

        List<DiscordComponent> buttons = new ArrayList<DiscordComponent>();
        DiscordButton button_accept = new DiscordButton(DiscordButtonStyle.GREEN);
        button_accept.custom_id = "teaminvite_1_" + captain.getUrtauth() + "_" + player.getUrtauth();
        button_accept.label = "Accept";
        buttons.add(button_accept);
        DiscordButton button_decline = new DiscordButton(DiscordButtonStyle.RED);
        button_decline.custom_id = "teaminvite_0_" + captain.getUrtauth() + "_" + player.getUrtauth();
        button_decline.label = "Decline";
        buttons.add(button_decline);
        DiscordButton button_cancel = new DiscordButton(DiscordButtonStyle.GREY);
        button_cancel.custom_id = "teaminvite_2_" + captain.getUrtauth() + "_" + player.getUrtauth();
        button_cancel.label = "Cancel";
        buttons.add(button_cancel);

        String invite_message = Config.team_invited;
        invite_message = invite_message.replace(".invited.", player.getDiscordUser().getMentionString());
        invite_message = invite_message.replace(".captain.", captain.getDiscordUser().getMentionString());
        logic.bot.sendMsgToEdit(threadChannel, invite_message, null, buttons);
    }

    public void acceptInvitation(Player player) {
        invitedPlayers.remove(player);
        addPlayer(player);

        List<DiscordComponent> buttons = new ArrayList<DiscordComponent>();
        DiscordButton button_remove = new DiscordButton(DiscordButtonStyle.RED);
        button_remove.custom_id = "teamremove_" + captain.getUrtauth() + "_" + player.getUrtauth();
        button_remove.label = "Remove";
        buttons.add(button_remove);

        String accept_message = Config.team_accepted;
        accept_message = accept_message.replace(".player.", player.getDiscordUser().getMentionString());
        logic.bot.sendMsgToEdit(threadChannel, accept_message, null, buttons);
    }

    public void declineInvitation(Player player) {
        invitedPlayers.remove(player);
        logic.bot.sendMsg(threadChannel, Config.team_declined.replace(".player.", player.getDiscordUser().getMentionString()));
    }

    public void cancelInvitation(Player player) {
        invitedPlayers.remove(player);
        logic.bot.sendMsg(threadChannel, Config.team_canceled.replace(".player.", player.getDiscordUser().getMentionString()));
    }

    public boolean isFull() {
        return players.size() == maxPlayers;
    }

    public void archive() {
        threadChannel.archive();
    }

    public String getTeamString() {
        String str = captain.getDiscordUser().getMentionString() + " (captain)";
        for (Player player : players) {
            if (!player.equals(captain)) {
                str = str + " " + player.getDiscordUser().getMentionString();
            }
        }
        return str;
    }

    public String getTeamStringNoMention() {
        String str = captain.getUrtauth() + " (captain)";
        for (Player player : players) {
            if (!player.equals(captain)) {
                str = str + " " + player.getUrtauth();
            }
        }
        return str;
    }
}
