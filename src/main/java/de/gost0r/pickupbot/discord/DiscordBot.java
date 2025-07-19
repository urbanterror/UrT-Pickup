package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class DiscordBot {

    private static String token = "";
    private static String application_id = "";
    private static List<DiscordGuild> guilds;

    protected DiscordUser self = null;

    public String env;

    public void init(String env) {
        self = DiscordUser.getUser("@me");
        guilds = DiscordAPI.getBotGuilds();
        this.env = env;
    }


    public void tick() {

    }

    public void recvMessage(DiscordMessage msg) {

    }

    public void recvInteraction(DiscordInteraction interaction) {

    }

    public void recvApplicationCommand(DiscordSlashCommandInteraction command) {

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
