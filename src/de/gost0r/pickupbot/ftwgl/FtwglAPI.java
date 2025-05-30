package de.gost0r.pickupbot.ftwgl;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.pickup.Config;
import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.Player;
import de.gost0r.pickupbot.pickup.Region;
import de.gost0r.pickupbot.pickup.server.Server;
import io.sentry.Sentry;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FtwglAPI {
    private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static String api_url;
    private static String api_key;

    public static void setupCredentials(String url, String key){
        FtwglAPI.api_url = url;
        FtwglAPI.api_key = key;
    }

    public static String launchAC(Player player, String ip, String password){
        JSONObject requestObj = new JSONObject()
                .put("discord_id", Long.parseLong(player.getDiscordUser().id))
                .put("address", ip)
                .put("password", password);

        String response =  sendPostRequest("/launch/pug", requestObj);

        if (response == null){
            return Config.ftw_notconnected;
        }
        else return Config.ftw_success;
    }

    public static boolean checkIfPingStored(Player p){
        String request = "/ping/" + p.getDiscordUser().id;
        int response = sendHeadRequest(request);
        return response == 200;
    }

    public static String requestPingUrl(Player player){
        JSONObject requestObj = new JSONObject()
                .put("discord_id", Long.parseLong(player.getDiscordUser().id))
                .put("username", player.getDiscordUser().username)
                .put("urt_auth", player.getUrtauth());

        String response =  sendPostRequest("/ping", requestObj);
        JSONObject obj = new JSONObject(response);

        if (!obj.has("url")){
            return Config.ftw_error;
        }
        return obj.getString("url");
    }

    public static Server spawnDynamicServer(List<Player> playerList){
        JSONObject requestObj = new JSONObject();
        List<Long> discordIdList = new ArrayList<Long>();
        for (Player player : playerList){
            discordIdList.add(Long.parseLong(player.getDiscordUser().id));
        }
        requestObj.put("discord_ids", discordIdList);
        String response = sendPostRequest("/rent/pug", requestObj);
        JSONObject obj = new JSONObject(response);

        if (!obj.has("server") || !obj.getJSONObject("server").has("config")){
            // spawnDynamicServer(playerList);
            LOGGER.warning("CAN'T SPAWN: " + response);
            return null;
        }

        LOGGER.warning(obj.toString());

        JSONObject serverObj = obj.getJSONObject("server").getJSONObject("config");
        JSONObject serverLocationObj = obj.getJSONObject("server_location");
        JSONObject playerPingsObj = obj.getJSONObject("pings");

        String country = serverLocationObj.getString("country");
        Region region = Region.valueOf(Country.getContinent(country));

        Map<Player, Integer> playerPing = new HashMap<Player, Integer>();
        for (String discordId : playerPingsObj.keySet()){
            DiscordUser user = DiscordUser.getUser(discordId);
            Player player = Player.get(user);
            if (player != null){
                playerPing.put(player, playerPingsObj.getInt(discordId));
            }
        }

        Server server = new Server(obj.getJSONObject("server").getInt("id"), null, serverObj.getInt("port"), serverObj.getString("rcon"), serverObj.getString("password"), true, region);
        server.country = country;
        server.city = serverLocationObj.getString("city");
        server.playerPing = playerPing;
        return server;
    }

    public static Server getSpawnedServerIp(Server server){
        String response = sendGetRequest("/rent/" + String.valueOf(server.id), true);

        if (response == null){
            return null;
        }
        LOGGER.warning(response);

        JSONObject obj = new JSONObject(response);
        JSONObject serverObj = obj.getJSONObject("config");
        if (!serverObj.has("ip")){
            try {
                Thread.sleep(1000);
                return getSpawnedServerIp(server);
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, "Exception: ", e);
                Sentry.capture(e);
            }
        }
        server.IP = serverObj.getString("ip");
        if (!server.isOnline()){
            try {
                Thread.sleep(1000);
                return getSpawnedServerIp(server);
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, "Exception: ", e);
                Sentry.capture(e);
            }
        }

        return server;
    }

    private static synchronized String sendPostRequest(String request, JSONObject content) {
        try {
            byte[] postData       = content.toString().getBytes( StandardCharsets.UTF_8 );
            int    postDataLength = postData.length;

            URL url = new URL(api_url + request);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", api_key);
            c.setRequestProperty("charset", "utf-8");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Bot");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Length", Integer.toString(postDataLength));
            try (DataOutputStream wr = new DataOutputStream( c.getOutputStream())) {
                wr.write(postData);
            }

            if (c.getResponseCode() != 200 && c.getResponseCode() != 201 && c.getResponseCode() != 204 ) {
                if (c.getResponseCode() == 429 || c.getResponseCode() == 502) {
                    try {
                        Thread.sleep(1000);
                        return sendPostRequest(request, content);
                    } catch (InterruptedException e) {
                        LOGGER.log(Level.WARNING, "Exception: ", e);
                        Sentry.capture(e);
                    }
                }
                else{
                    LOGGER.warning("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString() + " - Loadout: " + content.toString());
                    Sentry.capture("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString() + " - Loadout: " + content.toString());
                }
                return null;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader((c.getInputStream())));
            String fullmsg = "";
            String output = "";
            while ((output = br.readLine()) != null) {
                try {
                    fullmsg += output;
                } catch (ClassCastException | NullPointerException e) {
                    LOGGER.log(Level.WARNING, "Exception: ", e);
                    Sentry.capture(e);
                }
            }
            c.disconnect();

            LOGGER.fine("API call complete for " + request + ": " + fullmsg);
            return fullmsg;

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Exception: ", e);
            Sentry.capture(e);
        }
        return null;
    }

    private static synchronized String sendGetRequest(String request, boolean canBeNull) {
//		Thread.dumpStack();
        try {
            URL url = new URL(api_url + request);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "Bot");
            c.setRequestProperty("Authorization", api_key);

            if (c.getResponseCode() != 200 && c.getResponseCode() != 201 && c.getResponseCode() != 204) {
                if (c.getResponseCode() == 429 || c.getResponseCode() == 502) {
                    try {
                        Thread.sleep(1000);
                        return sendGetRequest(request, canBeNull);
                    } catch (InterruptedException e) {
                        LOGGER.log(Level.WARNING, "Exception: ", e);
                        Sentry.capture(e);
                    }
                    return null;
                }
                if (c.getResponseCode() == 404 && canBeNull){
                    return  null;
                }
                else{
                    LOGGER.warning("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString());
                    Sentry.capture("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString());
                }
            }

            BufferedReader br = new BufferedReader(new InputStreamReader((c.getInputStream())));
            String fullmsg = "";
            String output = "";
            while ((output = br.readLine()) != null) {
                try {
                    fullmsg += output;
                } catch (ClassCastException | NullPointerException e) {
                    LOGGER.log(Level.WARNING, "Exception: ", e);
                    Sentry.capture(e);
                }
            }
            c.disconnect();

            LOGGER.fine("API call complete for " + request + ": " + fullmsg);
            return fullmsg;

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Exception: ", e);
            Sentry.capture(e);
        }
        return null;
    }

    private static synchronized int sendHeadRequest(String request) {
        try {
            URL url = new URL(api_url + request);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "Bot");
            c.setRequestProperty("Authorization", api_key);

            if (c.getResponseCode() != 200 && c.getResponseCode() != 201 && c.getResponseCode() != 204) {
                if (c.getResponseCode() == 429 || c.getResponseCode() == 502) {
                    try {
                        Thread.sleep(1000);
                        return sendHeadRequest(request);
                    } catch (InterruptedException e) {
                        LOGGER.log(Level.WARNING, "Exception: ", e);
                        Sentry.capture(e);
                    }
                    return -1;
                }
                if (c.getResponseCode() == 404){
                    return  c.getResponseCode();
                }
                else{
                    LOGGER.warning("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString());
                    Sentry.capture("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString());
                }
            }

            c.disconnect();
            LOGGER.fine("API call complete for " + request + ": " + String.valueOf(c.getResponseCode()));
            return c.getResponseCode();

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Exception: ", e);
            Sentry.capture(e);
        }
        return -1;
    }

    public static boolean hasLauncherOn(Player p){
        String response = sendGetRequest("/connected/launcher/" + p.getDiscordUser().id, true);
        return response != null;
    }
}
