package de.gost0r.pickupbot.discord;

import java.util.HashMap;
import java.util.Map;

public class DiscordRole {

    public String id = "";
    public String name = "";

    public DiscordRole(String id) {
        this.id = id;
    }

    public String getMentionString() {
        return "<@&" + id + ">";
    }


    public static Map<String, DiscordRole> roleList = new HashMap<String, DiscordRole>();

    public static DiscordRole getRole(String id) {
        if (id.matches("[0-9]+")) {
            if (roleList.containsKey(id)) {
                return roleList.get(id);
            }
//			JSONObject role = DiscordAPI.requestRole(id);
//			if (role != null) {
//				DiscordRole newRole = new DiscordRole(role);
//				roleList.put(newRole.id, newRole);
//				return newRole;
//			}
            // TODO temp solution:
            DiscordRole newRole = new DiscordRole(id);
            roleList.put(id, newRole);
            return newRole;
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DiscordRole) {
            DiscordRole other = (DiscordRole) o;
            return this.id == other.id;
        }
        return false;
    }

    @Override
    public String toString() {
        return "id=" + id + " name=" + name;
    }
}
