package de.gost0r.pickupbot.discord;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DiscordEmbed {

    public String title;
    public String description;
    public String thumbnail;
    public int color;
    public List<JSONObject> fields;
    public String footer;
    public String footer_icon;
    public String timestamp;

    public DiscordEmbed() {
        fields = new ArrayList<JSONObject>();
    }

    public void addField(String name, String value, boolean inline) {
        JSONObject field = new JSONObject();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);

        fields.add(field);
    }

    public JSONObject getJSON() {
        JSONObject embedJSON = new JSONObject();

        if (title != null) {
            embedJSON.put("title", title);
        }
        if (description != null) {
            embedJSON.put("description", description);
        }
        if (thumbnail != null) {
            embedJSON.put("thumbnail", new JSONObject().put("url", thumbnail));
        }
        if (color != 0) {
            embedJSON.put("color", color);
        }
        if (fields != null) {
            embedJSON.put("fields", fields);
        }
        if (footer != null) {
            JSONObject footerJSON = new JSONObject().put("text", footer);
            if (footer_icon != null) {
                footerJSON.put("icon_url", footer_icon);
            }
            embedJSON.put("footer", footerJSON);
        }
        if (timestamp != null) {
            embedJSON.put("timestamp", timestamp);
        }

        return embedJSON;
    }
}