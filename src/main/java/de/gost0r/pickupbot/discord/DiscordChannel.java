package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class DiscordChannel {

    public String id;
    public DiscordChannelType type;
    public String name;
    public String topic;
    public String parent_id;
    public String guild_id;
    public boolean isThread;

    public DiscordChannel(JSONObject channel) {
        try {
            this.id = channel.getString("id");
            this.type = channel.getInt("type") == 11 ? DiscordChannelType.THREAD_CHANNEL : DiscordChannelType.values()[channel.getInt("type")];
            this.name = channel.isNull("name") ? null : channel.getString("name");
            this.topic = channel.isNull("topic") ? null : channel.getString("topic");
            this.parent_id = channel.isNull("parent_id") ? null : channel.getString("parent_id");
            this.guild_id = channel.isNull("guild_id") ? null : channel.getString("guild_id");
            this.isThread = !channel.isNull("thread_metadata");
        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }


    public String getMentionString() {
        return "<#" + id + ">";
    }

    public static Map<String, DiscordChannel> channelList = new HashMap<String, DiscordChannel>();

    public static DiscordChannel findChannel(String channelID) {
        if (channelID.matches("[0-9]+")) {
            if (channelList.containsKey(channelID)) {
                return channelList.get(channelID);
            }
            JSONObject reply = DiscordAPI.requestChannel(channelID);
            if (reply != null) {
                DiscordChannel newChannel = new DiscordChannel(reply);
                channelList.put(channelID, newChannel);
                return newChannel;
            }
        }
        log.info("Unable to find channel for: {}", channelID);
        return null;
    }


    @Override
    public boolean equals(Object o) {
        if (o instanceof DiscordChannel) {
            DiscordChannel chan = (DiscordChannel) o;
            return chan.id.equals(this.id);
        }
        return false;
    }


    @Override
    public String toString() {
        return id;
    }

    public void archive() {
        if (isThread) {
            DiscordAPI.archiveThread(this);
        }
    }

    public void delete() {
        DiscordAPI.deleteChannel(this);
    }
}
