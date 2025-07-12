package de.gost0r.pickupbot.ftwgl;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.pickup.Config;
import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.Player;
import de.gost0r.pickupbot.pickup.Region;
import de.gost0r.pickupbot.pickup.server.Server;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
public class FtwglAPI {

    private static String api_url;
    private static String api_key;

    public static void setupCredentials(String url, String key) {
        FtwglAPI.api_url = url;
        FtwglAPI.api_key = key;
    }

    public static String launchAC(Player player, String ip, String password) {
        JSONObject requestObj = new JSONObject()
                .put("discord_id", Long.parseLong(player.getDiscordUser().id))
                .put("address", ip)
                .put("password", password);

        String response = sendPostRequest("/launch/pug", requestObj);

        if (response == null) {
            return Config.ftw_notconnected;
        } else return Config.ftw_success;
    }

    public static boolean checkIfPingStored(Player p) {
        String request = "/ping/" + p.getDiscordUser().id;
        int response = sendHeadRequest(request);
        return response == 200;
    }

    public static String requestPingUrl(Player player) {
        JSONObject requestObj = new JSONObject()
                .put("discord_id", Long.parseLong(player.getDiscordUser().id))
                .put("username", player.getDiscordUser().username)
                .put("urt_auth", player.getUrtauth());

        String response = sendPostRequest("/ping", requestObj);
        JSONObject obj = new JSONObject(response);

        if (!obj.has("url")) {
            return Config.ftw_error;
        }
        return obj.getString("url");
    }

    public static Server spawnDynamicServer(List<Player> playerList) {
        JSONObject requestObj = new JSONObject();
        List<Long> discordIdList = new ArrayList<Long>();
        for (Player player : playerList) {
            discordIdList.add(Long.parseLong(player.getDiscordUser().id));
        }
        requestObj.put("discord_ids", discordIdList);
        String response = sendPostRequest("/rent/pug", requestObj);
        JSONObject obj = new JSONObject(response);

        if (!obj.has("server") || !obj.getJSONObject("server").has("config")) {
            // spawnDynamicServer(playerList);
            log.warn("CAN'T SPAWN: {}", response);
            return null;
        }

        JSONObject serverObj = obj.getJSONObject("server");
        JSONObject serverConfigObj = obj.getJSONObject("server").getJSONObject("config");
        JSONObject serverLocationObj = obj.getJSONObject("server_location");
        JSONObject playerPingsObj = obj.getJSONObject("pings");

        String country = serverLocationObj.getString("country");
        Region region = Region.valueOf(Country.getContinent(country));

        Map<Player, Integer> playerPing = new HashMap<Player, Integer>();
        for (String discordId : playerPingsObj.keySet()) {
            DiscordUser user = DiscordUser.getUser(discordId);
            Player player = Player.get(user);
            if (player != null) {
                playerPing.put(player, playerPingsObj.getInt(discordId));
            }
        }

        Server server = new Server(serverObj.getInt("id"), null, serverObj.getInt("port"), serverConfigObj.getString("rcon"), serverConfigObj.getString("password"), true, region);
        server.country = country;
        server.city = serverLocationObj.getString("city");
        server.playerPing = playerPing;
        return server;
    }

    public static Server getSpawnedServerIp(Server server) {
        String response = sendGetRequest("/rent/" + String.valueOf(server.id), true);

        if (response == null) {
            return null;
        }

        JSONObject obj = new JSONObject(response);
        JSONObject serverObj = obj.getJSONObject("config");
        if (!serverObj.has("ip")) {
            try {
                Thread.sleep(1000);
                return getSpawnedServerIp(server);
            } catch (InterruptedException e) {
                log.warn("Exception: ", e);
                Sentry.captureException(e);
            }
        }
        server.IP = serverObj.getString("ip");
        if (!server.isOnline()) {
            try {
                Thread.sleep(1000);
                return getSpawnedServerIp(server);
            } catch (InterruptedException e) {
                log.warn("Exception: ", e);
                Sentry.captureException(e);
            }
        }

        return server;
    }

    public static float getPlayerRating(Player p) {
        JSONObject requestObj = new JSONObject();
        List<Long> discordIdList = new ArrayList<Long>();
        discordIdList.add(Long.parseLong(p.getDiscordUser().id));
        requestObj.put("discord_ids", discordIdList);

        String response = sendPostRequest("/ratings", requestObj);

        if (response == null) {
            log.warn("Failed to get rating for player: {}", p.getDiscordUser().username);
            return 0.0f;
        }

        try {
            JSONObject obj = new JSONObject(response);
            // The response structure is: {"ratings": {"402540351216156695": 1.42}}
            if (obj.has("ratings")) {
                JSONObject ratingsObj = obj.getJSONObject("ratings");
                String discordId = p.getDiscordUser().id;
                if (ratingsObj.has(discordId)) {
                    return (float) ratingsObj.getDouble(discordId);
                } else {
                    log.warn("No rating found for player: {}", p.getDiscordUser().username);
                    return 0.0f;
                }
            } else {
                log.warn("No 'ratings' field found in response for player: {}", p.getDiscordUser().username);
                return 0.0f;
            }
        } catch (Exception e) {
            log.warn("Exception parsing rating response: ", e);
            Sentry.captureException(e);
            return 0.0f;
        }
    }

    public static Map<Player, Float> getPlayerRatings(List<Player> players) {
        if (players == null || players.isEmpty()) {
            return new HashMap<>();
        }

        JSONObject requestObj = new JSONObject();
        List<Long> discordIdList = new ArrayList<Long>();
        for (Player player : players) {
            discordIdList.add(Long.parseLong(player.getDiscordUser().id));
        }
        requestObj.put("discord_ids", discordIdList);

        String response = sendPostRequest("/ratings", requestObj);

        Map<Player, Float> playerRatings = new HashMap<>();

        if (response == null) {
            log.warn("Failed to get ratings for player list");
            // Return map with all players having 0.0f rating
            for (Player player : players) {
                playerRatings.put(player, 0.0f);
            }
            return playerRatings;
        }

        try {
            JSONObject obj = new JSONObject(response);
            // The response structure is: {"ratings": {"402540351216156695": 1.42, "123456789": 2.15}}
            if (obj.has("ratings")) {
                JSONObject ratingsObj = obj.getJSONObject("ratings");

                for (Player player : players) {
                    String discordId = player.getDiscordUser().id;
                    if (ratingsObj.has(discordId)) {
                        playerRatings.put(player, (float) ratingsObj.getDouble(discordId));
                    } else {
                        log.warn("No rating found for player: {}", player.getDiscordUser().username);
                        playerRatings.put(player, 0.0f);
                    }
                }
            } else {
                log.warn("No 'ratings' field found in response for player list");
                // Return map with all players having 0.0f rating
                for (Player player : players) {
                    playerRatings.put(player, 0.0f);
                }
            }
        } catch (Exception e) {
            log.warn("Exception parsing rating response: ", e);
            Sentry.captureException(e);
            // Return map with all players having 0.0f rating
            for (Player player : players) {
                playerRatings.put(player, 0.0f);
            }
        }

        return playerRatings;
    }

    private static synchronized String sendPostRequest(String request, JSONObject content) {
        try {
            byte[] postData = content.toString().getBytes(StandardCharsets.UTF_8);
            int postDataLength = postData.length;

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
            try (DataOutputStream wr = new DataOutputStream(c.getOutputStream())) {
                wr.write(postData);
            }

            if (c.getResponseCode() != 200 && c.getResponseCode() != 201 && c.getResponseCode() != 204) {
                if (c.getResponseCode() == 429 || c.getResponseCode() == 502) {
                    try {
                        Thread.sleep(1000);
                        return sendPostRequest(request, content);
                    } catch (InterruptedException e) {
                        log.warn("Exception: ", e);
                        Sentry.captureException(e);
                    }
                } else {
                    log.warn("API call failed: ({}) {} for {} - Loadout: {}", c.getResponseCode(), c.getResponseMessage(), url.toString(), content.toString());
                    Sentry.captureMessage("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString() + " - Loadout: " + content.toString());
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
                    log.warn("Exception: ", e);
                    Sentry.captureException(e);
                }
            }
            c.disconnect();

            log.trace("API call complete for {}: {}", request, fullmsg);
            return fullmsg;

        } catch (IOException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
                        log.warn("Exception: ", e);
                        Sentry.captureException(e);
                    }
                    return null;
                }
                if (c.getResponseCode() == 404 && canBeNull) {
                    return null;
                } else {
                    log.warn("API call failed: ({}) {} for {}", c.getResponseCode(), c.getResponseMessage(), url.toString());
                    Sentry.captureMessage("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString());
                }
            }

            BufferedReader br = new BufferedReader(new InputStreamReader((c.getInputStream())));
            String fullmsg = "";
            String output = "";
            while ((output = br.readLine()) != null) {
                try {
                    fullmsg += output;
                } catch (ClassCastException | NullPointerException e) {
                    log.warn("Exception: ", e);
                    Sentry.captureException(e);
                }
            }
            c.disconnect();

            log.trace("API call complete for {}: {}", request, fullmsg);
            return fullmsg;

        } catch (IOException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
                        log.warn("Exception: ", e);
                        Sentry.captureException(e);
                    }
                    return -1;
                }
                if (c.getResponseCode() == 404) {
                    return c.getResponseCode();
                } else {
                    log.warn("API call failed: ({}) {} for {}", c.getResponseCode(), c.getResponseMessage(), url.toString());
                    Sentry.captureMessage("API call failed: (" + c.getResponseCode() + ") " + c.getResponseMessage() + " for " + url.toString());
                }
            }

            c.disconnect();
            log.trace("API call complete for {}: {}", request, String.valueOf(c.getResponseCode()));
            return c.getResponseCode();

        } catch (IOException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return -1;
    }

    public static boolean hasLauncherOn(Player p) {
        String response = sendGetRequest("/connected/launcher/" + p.getDiscordUser().id, true);
        return response != null;
    }
}