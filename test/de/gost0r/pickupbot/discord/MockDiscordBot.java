package de.gost0r.pickupbot.discord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.gost0r.pickupbot.pickup.PickupBot;

/**
 * A mock implementation of DiscordBot for testing purposes.
 * This class captures messages sent by the bot instead of sending them to Discord.
 */
public class MockDiscordBot extends PickupBot {
    
    private Map<String, List<String>> channelMessages = new HashMap<>();
    private Map<DiscordUser, List<String>> userNotices = new HashMap<>();
    private String latestMessageChannel = "test-channel";
    
    public MockDiscordBot() {
        super(null);
    }
    
    @Override
    public void sendMsg(String channel, String msg) {
        if (!channelMessages.containsKey(channel)) {
            channelMessages.put(channel, new ArrayList<>());
        }
        channelMessages.get(channel).add(msg);
    }
    
    @Override
    public void sendNotice(DiscordUser user, String msg) {
        if (!userNotices.containsKey(user)) {
            userNotices.put(user, new ArrayList<>());
        }
        userNotices.get(user).add(msg);
    }
    
    @Override
    public String getLatestMessageChannel() {
        return latestMessageChannel;
    }
    
    public void setLatestMessageChannel(String channel) {
        this.latestMessageChannel = channel;
    }
    
    /**
     * Get all messages sent to a specific channel
     */
    public List<String> getChannelMessages(String channel) {
        return channelMessages.getOrDefault(channel, new ArrayList<>());
    }
    
    /**
     * Get all notices sent to a specific user
     */
    public List<String> getUserNotices(DiscordUser user) {
        return userNotices.getOrDefault(user, new ArrayList<>());
    }
    
    /**
     * Clear all captured messages
     */
    public void clearMessages() {
        channelMessages.clear();
        userNotices.clear();
    }
    
    /**
     * Simulate receiving a command from a user
     */
    public void simulateCommand(String command, DiscordUser user) {
        DiscordMessage msg = new DiscordMessage();
        msg.content = command;
        msg.user = user;
        msg.channel = latestMessageChannel;
        
        this.handleCommand(msg);
    }
}