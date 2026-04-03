package de.gost0r.pickupbot.command.common;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

@Service
public class CommandInitService {

    private final DiscordService discordService;
    private final List<BaseCommand> commandList;
    private final Executor queueExecutor;

    public CommandInitService(DiscordService discordService,
                              List<BaseCommand> commandList,
                              @Qualifier("queueExecutor") Executor queueExecutor) {
        this.discordService = discordService;
        this.commandList = commandList;
        this.queueExecutor = queueExecutor;
    }

    public void initCommands() {
        commandList.forEach(BaseCommand::init);
        updateAllApplicationCommands();
    }

    public void handleInteraction(DiscordSlashCommandInteraction interaction) {
        queueExecutor.execute(() -> {
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
