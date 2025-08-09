package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordInteraction;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;
import de.gost0r.pickupbot.pickup.PickupBot;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JdaForwarder extends ListenerAdapter {

    private final PickupBot bot;

    public JdaForwarder(JDA jda, PickupBot bot) {
        this.bot = bot;

        jda.addEventListener(this);
    }

    @Override
    public void onReady(@Nonnull ReadyEvent event) {
        this.bot.init();
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        final DiscordMessage message = new JdaDiscordMessage(event.getMessage());

        bot.recvMessage(message);
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        DiscordSlashCommandInteraction command = new JdaDiscordSlashCommandInteraction(event);
        bot.recvApplicationCommand(command);
    }

    @Override
    public void onGenericComponentInteractionCreate(@NotNull GenericComponentInteractionCreateEvent event) {
        DiscordInteraction interaction = new JdaDiscordInteraction(event);
        bot.recvInteraction(interaction);
    }
}
