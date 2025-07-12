package de.gost0r.pickupbot.discord;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DiscordCommandOption {
    public DiscordCommandOptionType type;
    public String name;
    public String description;
    public boolean required;
    public ArrayList<DiscordCommandOptionChoice> choices;

    public DiscordCommandOption(DiscordCommandOptionType type, String name, String description) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.required = true;
        this.choices = new ArrayList<DiscordCommandOptionChoice>();
    }

    public void addChoice(DiscordCommandOptionChoice choice) {
        choices.add(choice);
    }

    public JSONObject getJSON() {
        JSONObject jsonObj = new JSONObject();
        jsonObj.put("type", type.ordinal());
        jsonObj.put("name", name);
        jsonObj.put("description", description);

        if (required) {
            jsonObj.put("required", required);
        }

        if (choices.size() > 0) {
            List<JSONObject> choiceList = new ArrayList<JSONObject>();
            for (DiscordCommandOptionChoice choice : choices) {
                choiceList.add(choice.getJSON());
            }
            jsonObj.put("choices", choiceList);
        }

        return jsonObj;
    }

}
