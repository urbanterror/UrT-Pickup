package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordEmbed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

public class PrivateGroup {
    public Player captain;
    public Gametype gt;
    private ArrayList<Player> playerList = new ArrayList<>();
    public Instant timestamp;

    public PrivateGroup(Player captain, Gametype inputGt) {
        this.captain = captain;
        this.gt = new Gametype(inputGt.getName() + ' ' + captain.getUrtauth(), inputGt.getTeamSize(), true, true);
        updateTimestamp();
        addPlayer(captain);
    }

    public void addPlayer(Player p) {
        if (!playerList.contains(p)) {
            playerList.add(p);
        }
        updateTimestamp();
    }

    public void removePlayer(Player p) {
        playerList.remove(p);
        updateTimestamp();
    }

    public boolean playerInGroup(Player p) {
        return playerList.contains(p);
    }

    public DiscordEmbed getEmbed() {
        DiscordEmbed embed = new DiscordEmbed();
        embed.title = "Private " + gt.getName().split(" ")[0] + " Group";
        embed.color = 7056881;

        long epochSeconds = timestamp.plus(Duration.ofHours(1)).getEpochSecond();
        embed.description = "**Captain**: " + captain.getDiscordUser().getMentionString() + "\nThis group will expire in <t:" + Long.toString(epochSeconds) + ":R> if left inactive.";

        int totalPlayers = playerList.size();
        int columns = (int) Math.ceil(totalPlayers / 10.0); // max 10 players per column

        for (int i = 0; i < columns; i++) {
            StringBuilder playerChunk = new StringBuilder();
            for (int j = i * 10; j < Math.min((i + 1) * 10, totalPlayers); j++) {
                playerChunk.append(playerList.get(j).getDiscordUser().getMentionString()).append("\n");
            }
            String fieldTitle = (i == 0)
                    ? "Players (" + totalPlayers + ")"
                    : "\u200E"; // invisible character
            embed.addField(fieldTitle, playerChunk.toString(), true);
        }
        return embed;
    }

    public void updateTimestamp() {
        timestamp = Instant.now();
    }
}
