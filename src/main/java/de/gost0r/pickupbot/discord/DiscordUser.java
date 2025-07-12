package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;
import de.gost0r.pickupbot.pickup.PickupBot;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class DiscordUser {

    public String id;
    public String username;
    public String discriminator;
    public String avatar;

    public DiscordUserStatus status = DiscordUserStatus.online;
    public long statusChangeTime = -1L;

    public DiscordChannel channel = null;

    private Map<DiscordGuild, List<DiscordRole>> roles = new HashMap<DiscordGuild, List<DiscordRole>>();

    public DiscordUser(String id, String username, String discriminator, String avatar) {
        this.id = id;
        this.username = username;
        this.discriminator = discriminator;
        this.avatar = avatar;
    }

    public DiscordUser(JSONObject user) {
        try {
            this.id = user.getString("id");
            this.username = user.getString("username");
            this.discriminator = user.getString("discriminator");
            this.avatar = user.isNull("avatar") ? null : user.getString("avatar");
        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void setStatus(DiscordUserStatus status) {
        this.status = status;
        this.statusChangeTime = System.currentTimeMillis();
    }

    public String getMentionString() {
        return "<@" + id + ">";
    }


    public void setRoles(DiscordGuild guild, JSONArray array) {
        List<DiscordRole> roleList = new ArrayList<DiscordRole>();
        try {
            for (int i = 0; i < array.length(); ++i) {
                roleList.add(DiscordRole.getRole(array.getString(i)));
            }
            roles.put(guild, roleList);
        } catch (JSONException e) {
            log.warn("Exception for {}: ", array, e);
            Sentry.captureException(e);
        }
    }

    public List<DiscordRole> getRoles(List<DiscordGuild> guilds) {
        List<DiscordRole> list = new ArrayList<DiscordRole>();
        for (DiscordGuild guild : guilds) {
            if (roles.containsKey(guild)) {
                list.addAll(roles.get(guild));
                continue;
            }

            JSONArray ar = DiscordAPI.requestUserGuildRoles(guild.id, this.id);
            if (ar == null) { // If the user is not a member of this guild
                continue;
            }

            for (int i = 0; i < ar.length(); ++i) {
                try {
                    DiscordRole role = DiscordRole.getRole(ar.getString(i));
                    list.add(role);
                } catch (JSONException e) {
                    log.warn("Exception: ", e);
                    Sentry.captureException(e);
                }
            }
            roles.put(guild, list);
        }
        return list;
    }

    public boolean hasRole(DiscordGuild guild, DiscordRole role) {
        JSONArray ar = DiscordAPI.requestUserGuildRoles(guild.id, this.id);
        if (ar == null) { // If the user is not a member of this guild
            return false;
        }

        for (int i = 0; i < ar.length(); ++i) {
            try {
                if (role.id.equals(ar.getString(i))) {
                    return true;
                }
            } catch (JSONException e) {
                log.warn("Exception: ", e);
                Sentry.captureException(e);
            }
        }
        return false;
    }

    public DiscordChannel getDMChannel() {
        try {
            if (channel == null) {
                String channelID = DiscordAPI.createDM(id).getString("id");
                DiscordChannel channel = DiscordChannel.findChannel(channelID);
                this.channel = channel;
            }
            return channel;
        } catch (JSONException | NullPointerException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return null;
    }

    public static Map<String, DiscordUser> userList = new HashMap<String, DiscordUser>();

    public static DiscordUser getUser(JSONObject obj) {
        try {
            String userID = obj.getString("id");
            if (userList.containsKey(userID)) {
                return userList.get(userID);
            }
            DiscordUser newUser = new DiscordUser(obj);
            userList.put(userID, newUser);
            return newUser;
        } catch (JSONException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return null;
    }

    public static DiscordUser getUser(String id) {
        if (id.matches("[0-9]+") && id.length() > 10) {
            if (userList.containsKey(id)) {
                return userList.get(id);
            }
            JSONObject user = DiscordAPI.requestUser(id);
            if (user != null) {
                DiscordUser newUser = new DiscordUser(user);
                userList.put(newUser.id, newUser);
                return newUser;
            }
        }
        return null;
    }

    public static DiscordUser findUser(String id) {
        if (id.matches("[0-9]+")) {
            if (userList.containsKey(id)) {
                return userList.get(id);
            }
        }
        return null;
    }

    public boolean hasStreamerRights() {
        List<DiscordRole> roleList = this.getRoles(DiscordBot.getGuilds());
        List<DiscordRole> streamerList = PickupBot.logic.getStreamerList();
        for (DiscordRole s : roleList) {
            for (DiscordRole r : streamerList) {
                if (s.equals(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasAdminRights() {
        List<DiscordRole> roleList = this.getRoles(DiscordBot.getGuilds());
        List<DiscordRole> adminList = PickupBot.logic.getAdminList();
        for (DiscordRole s : roleList) {
            for (DiscordRole r : adminList) {
                if (s.equals(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasSuperAdminRights() {
        List<DiscordRole> roleList = this.getRoles(DiscordBot.getGuilds());
        List<DiscordRole> adminList = PickupBot.logic.getSuperAdminList();
        for (DiscordRole s : roleList) {
            for (DiscordRole r : adminList) {
                if (s.equals(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DiscordUser) {
            DiscordUser user = (DiscordUser) o;
            return user.id == this.id;
        }
        return false;
    }

    public String getAvatarUrl() {
        return "https://cdn.discordapp.com/avatars/" + id + "/" + avatar + ".png";
    }

}
