package de.gost0r.pickupbot.discord;

import org.json.JSONObject;

public class DiscordSelectOption {
    public String label;
    public String value;
    public String description;
    public JSONObject emoji;
    public boolean isDefault;

    public DiscordSelectOption(String label, String value) {
        this.label = label;
        this.value = value;
    }


    public JSONObject getJSON() {
        JSONObject selectOptionJSON = new JSONObject();
        selectOptionJSON.put("label", label);
        selectOptionJSON.put("value", value);

        if (description != null) {
            selectOptionJSON.put("description", description);
        }
        if (emoji != null) {
            selectOptionJSON.put("emoji", emoji);
        }
        if (isDefault) {
            selectOptionJSON.put("default", isDefault);
        }

        return selectOptionJSON;
    }
}
