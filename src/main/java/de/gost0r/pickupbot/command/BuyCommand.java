package de.gost0r.pickupbot.command;

import de.gost0r.pickupbot.command.common.BaseCommand;
import de.gost0r.pickupbot.discord.DiscordApplicationCommand;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;

// don't register
//@Component
public class BuyCommand extends BaseCommand {

    @Override
    public void init() {
        setApplicationCommand(
                DiscordApplicationCommand
                        .builder()
                        .name("buy")
                        .description("Buy a perk with your coins")
                        .build()
        );
    }

    @Override
    public void handle(DiscordSlashCommandInteraction interaction) {

    }
}
