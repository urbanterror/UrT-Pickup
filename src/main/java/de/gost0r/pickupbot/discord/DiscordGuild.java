package de.gost0r.pickupbot.discord;

import java.util.HashMap;
import java.util.Map;

public class DiscordGuild {
    public String id = "";

    public DiscordGuild(String id) {
        this.id = id;
    }

    public static Map<String, DiscordGuild> guildList = new HashMap<String, DiscordGuild>();

    public static DiscordGuild getGuild(String id) {
        if (id.matches("[0-9]+")) {
            if (guildList.containsKey(id)) {
                return guildList.get(id);
            }
//			JSONObject guild = DiscordAPI.requestGuild(id);
//			if (guild != null) {
//				DiscordGuild newGuild = new DiscordGuild(guild);
//				guildList.put(newGuild.id, newGuild);
//				return newGuild;
//			}
            // TODO temp solution:
            DiscordGuild newGuild = new DiscordGuild(id);
            guildList.put(id, newGuild);
            return newGuild;
        }
        return null;
    }

}
