package de.gost0r.pickupbot.discord;

import de.gost0r.pickupbot.discord.api.DiscordAPI;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DiscordApplicationCommand {
    public int type;
    public String name;
    public String description;
    public String default_member_permissions;
    public ArrayList<DiscordCommandOption> options;

    public DiscordApplicationCommand(String name, String description) {
        this.type = 1;
        this.name = name;
        this.description = description;
        this.options = new ArrayList<DiscordCommandOption>();
    }

    public JSONObject getJSON() {
        JSONObject jsonObj = new JSONObject();
        jsonObj.put("type", type);
        jsonObj.put("name", name);
        jsonObj.put("description", description);

        if (default_member_permissions != null) {
            jsonObj.put("default_member_permissions", default_member_permissions);
        }

        if (options.size() > 0) {
            List<JSONObject> optionList = new ArrayList<JSONObject>();
            for (DiscordCommandOption option : options) {
                optionList.add(option.getJSON());
            }
            jsonObj.put("options", optionList);
        }
        return jsonObj;
    }

    public void addOption(DiscordCommandOption option) {
        options.add(option);
    }

    public void create() {
        DiscordAPI.createApplicationCommand(this);
    }

}
