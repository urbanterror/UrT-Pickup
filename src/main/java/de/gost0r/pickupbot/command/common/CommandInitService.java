package de.gost0r.pickupbot.command.common;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommandInitService {

    private final DiscordService discordService;
    private final List<BaseCommand> commandList;

    public CommandInitService(DiscordService discordService,
                              List<BaseCommand> commandList) {
        this.discordService = discordService;
        this.commandList = commandList;
    }

    public void initCommands() {
        commandList.forEach(BaseCommand::init);
        updateAllApplicationCommands();
    }

    public void handleInteraction(DiscordSlashCommandInteraction interaction) {
        String command = interaction.getName();
        commandList
                .stream()
                .filter(c -> c.getApplicationCommand().name().equals(command))
                .findFirst()
                .ifPresent(c -> c.handle(interaction));
    }

    private void updateAllApplicationCommands() {
        discordService.registerApplicationCommands(
                commandList
                        .stream()
                        .map(BaseCommand::getApplicationCommand)
                        .toList()
        );
    }
}
