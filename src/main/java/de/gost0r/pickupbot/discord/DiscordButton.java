package de.gost0r.pickupbot.discord;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class DiscordButton extends DiscordComponent {

    private DiscordButtonStyle style;
    private String label;
    private DiscordEmoji emoji;
    private String url;

    public DiscordButton(DiscordButtonStyle style) {
        if (style == DiscordButtonStyle.NONE) {
            style = DiscordButtonStyle.PURPLE;
        }
        this.setStyle(style);
        setDisabled(false);
        setType(DiscordComponentType.BUTTON);
    }

}