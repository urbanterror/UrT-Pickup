package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;
import de.gost0r.pickupbot.discord.DiscordUser;
import lombok.Getter;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.util.ArrayList;
import java.util.List;

public class JdaDiscordSlashCommandInteraction implements DiscordSlashCommandInteraction {

    @Getter
    private final DiscordUser user;
    @Getter
    private final List<OptionMapping> options;

    private final SlashCommandInteractionEvent event;

    JdaDiscordSlashCommandInteraction(SlashCommandInteractionEvent event) {
        this.event = event;
        user = new JdaDiscordUser(event.getMember(), event.getUser());
        options = event.getOptions();
    }

    @Override
    public void deferReply() {
        event.deferReply().queue();
    }

    @Override
    public void respond(String content) {
        event.reply(content).queue();
    }

    @Override
    public void respond(String content, DiscordEmbed embed) {
        event.reply(content)
                .setEmbeds(JdaUtils.mapToMessageEmbed(embed))
                .queue();
    }

    @Override
    public void respond(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components) {
        event.reply(content)
                .setEmbeds(JdaUtils.mapToMessageEmbed(embed))
                .addActionRow(components.stream().map(JdaUtils::mapToItemComponent).toList())
                .queue();
    }

    @Override
    public String getName() {
        return event.getName();
    }
}