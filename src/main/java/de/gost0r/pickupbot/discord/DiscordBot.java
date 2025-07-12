package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;
import de.gost0r.pickupbot.discord.web.DiscordGateway;
import de.gost0r.pickupbot.discord.web.DiscordGatewayEvent;
import de.gost0r.pickupbot.discord.web.WsClientEndPoint;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Slf4j
public class DiscordBot {

    private static String token = "";
    private static String application_id = "";
    private static List<DiscordGuild> guilds;

    protected DiscordUser self = null;

    private DiscordGateway gateway = null;
    private WsClientEndPoint endpoint = null;
    private String env;

    public DiscordBot() {
    }

    public void init(String env) {
        reconnect();
        self = DiscordUser.getUser("@me");
        guilds = DiscordAPI.getBotGuilds();
        this.env = env;
    }

    public void reconnect() {
        try {
            if (endpoint == null) {
                endpoint = new WsClientEndPoint(this, new URI("wss://gateway.discord.gg/?encoding=json&v=9"));
            } else {
                endpoint.reconnect();
            }
            if (gateway == null) {
                gateway = new DiscordGateway(this, endpoint);
            } else {
                gateway.reconnect();
            }
            endpoint.addMessageHandler(gateway);
        } catch (URISyntaxException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        } catch (Exception e) {
            // we can trigger a handshake exception for endpoint. if that's the case, simply try again.
            // TODO: need to check that this is really working
            log.error("Exception: ", e);
            init(env);
        }
    }

    public void handleEvent(DiscordGatewayEvent event, JSONObject obj) {
        try {
            DiscordUser user = null;
            switch (event) {
                case MESSAGE_CREATE:
                    DiscordMessage msg = new DiscordMessage(obj.getString("id"),
                            DiscordUser.getUser(obj.getJSONObject("author")),
                            DiscordChannel.findChannel(obj.getString("channel_id")),
                            obj.getString("content"));
                    recvMessage(msg);
                    break;
                case INTERACTION_CREATE:
                    DiscordMessage message = null;
                    if (obj.has("message")) {
                        message = new DiscordMessage(obj.getJSONObject("message").getString("id"),
                                DiscordUser.getUser(obj.getJSONObject("message").getJSONObject("author")),
                                DiscordChannel.findChannel(obj.getJSONObject("message").getString("channel_id")),
                                obj.getJSONObject("message").getString("content"));
                    }

                    if (obj.has("member")) {
                        user = DiscordUser.getUser(obj.getJSONObject("member").getJSONObject("user"));
                    } else {
                        user = DiscordUser.getUser(obj.getJSONObject("user"));
                    }
                    JSONArray values = null;
                    if (obj.getJSONObject("data").has("values")) {
                        values = obj.getJSONObject("data").getJSONArray("values");
                    }
                    DiscordInteraction interaction = new DiscordInteraction(obj.getString("id"),
                            obj.getString("token"),
                            obj.getJSONObject("data"),
                            user,
                            message,
                            values);
                    if (obj.getJSONObject("data").has("custom_id")) {
                        recvInteraction(interaction);
                    } else if (obj.getJSONObject("data").has("name")) {
                        if (obj.getJSONObject("data").has("options")) {
                            interaction.getOptions(obj.getJSONObject("data").optJSONArray("options"));
                        }
                        recvApplicationCommand(interaction);
                    }
                    break;
                case GUILD_MEMBER_UPDATE:
                    user = DiscordUser.findUser(obj.getJSONObject("user").getString("id"));
                    if (user != null) {
                        // only update roles of users that we already know
                        user.setRoles(DiscordGuild.getGuild("guild_id"), obj.getJSONArray("roles"));
                    }
                    break;
                case PRESENCE_UPDATE:
                    user = DiscordUser.findUser(obj.getJSONObject("user").getString("id"));
                    if (user != null) {
                        DiscordUserStatus userStatus = DiscordUserStatus.valueOf(obj.getString("status"));
                        user.setStatus(userStatus);
                    }
                    break;
                default:
                    break;
            }
        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        tick();
    }

    protected void tick() {

    }

    protected void recvMessage(DiscordMessage msg) {

    }

    protected void recvInteraction(DiscordInteraction interaction) {

    }

    protected void recvApplicationCommand(DiscordInteraction interaction) {

    }

    public void sendMsg(DiscordChannel channel, String msg) {
        DiscordAPI.sendMessage(channel, msg);
    }

    public void sendMsg(DiscordChannel channel, String msg, DiscordEmbed embed) {
        DiscordAPI.sendMessage(channel, msg, embed);
    }


    public void sendMsg(DiscordUser user, String msg) {
        sendMsg(user.getDMChannel(), msg);
    }

    public void sendMsg(DiscordUser user, String msg, DiscordEmbed embed) {
        sendMsg(user.getDMChannel(), msg, embed);
    }

    public DiscordMessage sendMsgToEdit(DiscordChannel channel, String msg, DiscordEmbed embed, List<DiscordComponent> components) {
        return DiscordAPI.sendMessageToEdit(channel, msg, embed, components);
    }

    public DiscordChannel createThread(DiscordChannel channel, String name) {
        return DiscordAPI.createThread(channel, name);
    }

    public static String getToken() {
        return token;
    }

    public static void setToken(String token) {
        DiscordBot.token = token;
    }

    public static String getApplicationId() {
        return application_id;
    }

    public static void setApplicationId(String application_id) {
        DiscordBot.application_id = application_id;
    }

    public static List<DiscordGuild> getGuilds() {
        return guilds;
    }

    public DiscordUser parseMention(String string) {
        string = string.replaceAll("[^\\d]", "");
        return DiscordUser.getUser(string);
    }

    public boolean addUserRole(DiscordUser user, DiscordRole role) {
        return DiscordAPI.addUserRole(user, role);
    }

    public boolean removeUserRole(DiscordUser user, DiscordRole role) {
        return DiscordAPI.removeUserRole(user, role);
    }

}
