package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class DiscordInteraction {
    public String id;
    public String name; // Application commands
    public String token;
    public DiscordUser user;
    public String custom_id;
    public DiscordMessage message;
    public ArrayList<String> values;
    public ArrayList<DiscordCommandOptionChoice> options;

    public DiscordInteraction(String id, String token, JSONObject data, DiscordUser user, DiscordMessage message, JSONArray values) {
        this.id = id;
        this.token = token;
        this.user = user;
        if (data.has("custom_id")) {
            this.custom_id = data.getString("custom_id");
        }
        if (data.has("name")) {
            this.name = data.getString("name");
        }
        this.message = message;

        ArrayList<String> strValues = new ArrayList<String>();
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                strValues.add(values.getString(i));
            }
        }
        this.values = strValues;
        this.options = new ArrayList<DiscordCommandOptionChoice>();
    }

    public void respond(String content) {
        DiscordAPI.interactionRespond(id, token, content, null, null);
    }

    public void respond(String content, DiscordEmbed embed) {
        DiscordAPI.interactionRespond(id, token, content, embed, null);
    }

    public void respond(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components) {
        DiscordAPI.interactionRespond(id, token, content, embed, components);
    }

    public void getOptions(JSONArray options) {
        for (int i = 0; i < options.length(); i++) {
            JSONObject choiceObj = options.getJSONObject(i);
            DiscordCommandOptionChoice choice = new DiscordCommandOptionChoice(choiceObj.getString("name"), String.valueOf(choiceObj.get("value")));
            this.options.add(choice);
        }
    }
}