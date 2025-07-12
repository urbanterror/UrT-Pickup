package de.gost0r.pickupbot.discord;

import org.json.JSONObject;

public class DiscordButton extends DiscordComponent {

    public DiscordButtonStyle style;
    public String label;
    public JSONObject emoji;
    public String url;

    public DiscordButton(DiscordButtonStyle style) {
        if (style == DiscordButtonStyle.NONE) {
            style = DiscordButtonStyle.BLURPLE;
        }
        this.style = style;
        disabled = false;
        type = 2;
    }

    public JSONObject getJSON() {
        JSONObject buttonJSON = new JSONObject();
        buttonJSON.put("type", type);
        buttonJSON.put("style", style.ordinal());

        if (disabled) {
            buttonJSON.put("disabled", disabled);
        }
        if (label != null) {
            buttonJSON.put("label", label);
        }
        if (emoji != null) {
            buttonJSON.put("emoji", emoji);
        }
        if (custom_id != null) {
            buttonJSON.put("custom_id", custom_id);
        }
        if (url != null) {
            buttonJSON.put("url", url);
        }

        return buttonJSON;
    }
}