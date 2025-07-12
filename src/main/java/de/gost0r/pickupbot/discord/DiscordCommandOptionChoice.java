package de.gost0r.pickupbot.discord;

import org.json.JSONObject;

public class DiscordCommandOptionChoice {
    public String name;
    public String value;

    public DiscordCommandOptionChoice(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public JSONObject getJSON() {
        JSONObject jsonObj = new JSONObject();
        jsonObj.put("name", name);
        jsonObj.put("value", value);
        return jsonObj;
    }
}

