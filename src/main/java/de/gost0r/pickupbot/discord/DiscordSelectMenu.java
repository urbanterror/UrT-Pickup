package de.gost0r.pickupbot.discord;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DiscordSelectMenu extends DiscordComponent {
    public ArrayList<DiscordSelectOption> options;
    public String placeholder;

    public DiscordSelectMenu(ArrayList<DiscordSelectOption> options) {
        this.options = options;
        disabled = false;
        type = 3;
    }

    public JSONObject getJSON() {
        JSONObject selectMenuJSON = new JSONObject();
        selectMenuJSON.put("type", type);
        selectMenuJSON.put("custom_id", custom_id);

        if (options != null) {
            List<JSONObject> optionList = new ArrayList<JSONObject>();
            for (DiscordSelectOption option : options) {
                optionList.add(option.getJSON());
            }
            selectMenuJSON.put("options", optionList);
        }

        if (disabled) {
            selectMenuJSON.put("disabled", disabled);
        }

        if (placeholder != null) {
            selectMenuJSON.put("placeholder", placeholder);
        }

        return selectMenuJSON;
    }
}
