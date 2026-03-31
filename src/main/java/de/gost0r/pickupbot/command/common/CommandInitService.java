package de.gost0r.pickupbot.command.common;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

import java.util.List;

@Service
public class CommandInitService {

    private final DiscordService discordService;
    private final List<BaseCommand> commandList;
    private final java.util.concurrent.Executor commandExecutor;

    public CommandInitService(DiscordService discordService,
                              List<BaseCommand> commandList,
                              @org.springframework.beans.factory.annotation.Qualifier("commandExecutor") java.util.concurrent.Executor commandExecutor) {
        this.discordService = discordService;
        this.commandList = commandList;
        this.commandExecutor = commandExecutor;
    }

    public void initCommands() {
        commandList.forEach(BaseCommand::init);
        updateAllApplicationCommands();
    }

    public void handleInteraction(DiscordSlashCommandInteraction interaction) {
        commandExecutor.execute(() -> {
            String command = interaction.getName();
            commandList
                    .stream()
                    .filter(c -> c.getApplicationCommand().name().equals(command))
                    .findFirst()
                    .ifPresent(c -> c.handle(interaction));
        });
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
