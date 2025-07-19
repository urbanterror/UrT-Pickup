package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class JdaForwarder extends ListenerAdapter {

    private final DiscordBot bot;

    public JdaForwarder(DiscordBot bot) {
        this.bot = bot;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        final DiscordMessage message = new DiscordMessage(
                event.getMessage().getId(),
                DiscordUser.getUser(event.getAuthor().getId()),
                DiscordChannel.findChannel(event.getChannel().getId()),
                event.getMessage().getContentRaw()
        );

        bot.recvMessage(message);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        DiscordSlashCommandInteraction command = new DiscordSlashCommandInteraction(
                event.getId(),
                event.getToken(),
                event.getName(),
                DiscordUser.getUser(event.getUser().getId()),
                event.getOptions()
        );
        bot.recvApplicationCommand(command);
    }

    @Override
    public void onGenericComponentInteractionCreate(GenericComponentInteractionCreateEvent event) {
        final DiscordMessage discordMessage = new DiscordMessage(
                event.getMessage().getId(),
                DiscordUser.getUser(event.getMessage().getAuthor().getId()),
                DiscordChannel.findChannel(event.getChannel().getId()),
                event.getMessage().getContentRaw()
        );
        List<String> values = event instanceof StringSelectInteractionEvent ? ((StringSelectInteractionEvent) event).getInteraction().getValues() : null;

        DiscordInteraction interaction = new DiscordInteraction(
                event.getId(),
                event.getToken(),
                event.getComponentId(),
                DiscordUser.getUser(event.getUser().getId()),
                discordMessage,
                values
        );
        bot.recvInteraction(interaction);
    }
}
