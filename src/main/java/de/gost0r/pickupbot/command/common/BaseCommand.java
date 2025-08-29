package de.gost0r.pickupbot.command.common;

import de.gost0r.pickupbot.discord.DiscordApplicationCommand;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;
import de.gost0r.pickupbot.pickup.Gametype;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
public abstract class BaseCommand {
    private DiscordApplicationCommand applicationCommand;

    public abstract void init();

    public abstract void handle(DiscordSlashCommandInteraction interaction);

    public void onActivatedGameTypesChanged(List<Gametype> gameTypes) {
    }
}
