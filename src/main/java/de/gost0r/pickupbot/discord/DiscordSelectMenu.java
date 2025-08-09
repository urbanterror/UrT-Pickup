package de.gost0r.pickupbot.discord;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;

@EqualsAndHashCode(callSuper = true)
@Data
public class DiscordSelectMenu extends DiscordComponent {
    private final ArrayList<DiscordSelectOption> options;

    public DiscordSelectMenu(ArrayList<DiscordSelectOption> options) {
        this.options = options;
        setDisabled(false);
        setType(DiscordComponentType.STRING_SELECT);
    }
}
