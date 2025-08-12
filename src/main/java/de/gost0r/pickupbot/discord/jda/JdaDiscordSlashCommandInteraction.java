package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordSlashCommandInteraction;
import de.gost0r.pickupbot.discord.DiscordUser;
import lombok.Getter;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;

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
    public void respondEphemeral(String content) {
        if (content != null) {
            event.reply(content)
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Override
    public void respondEphemeral(String content, DiscordEmbed embed) {
        if (content != null) {
            ReplyCallbackAction callback = event.reply(content);
            if (embed != null) {
                callback.setEmbeds(JdaUtils.mapToMessageEmbed(embed));
            }
            callback.setEphemeral(true)
                    .queue();
        } else if (embed != null) {
            event.replyEmbeds(JdaUtils.mapToMessageEmbed(embed))
                    .setEphemeral(true).queue();
        }
    }

    @Override
    public void respondEphemeral(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components) {
        if (content != null) {
            ReplyCallbackAction callback = event.reply(content);
            if (embed != null) {
                callback.setEmbeds(JdaUtils.mapToMessageEmbed(embed));
            }
            if (components != null) {
                callback.addActionRow(components.stream().map(JdaUtils::mapToItemComponent).toList());
            }
            callback.setEphemeral(true)
                    .queue();
        } else if (embed != null) {
            ReplyCallbackAction callback = event.replyEmbeds(JdaUtils.mapToMessageEmbed(embed));
            if (components != null) {
                callback.addActionRow(components.stream().map(JdaUtils::mapToItemComponent).toList());
            }
            callback.setEphemeral(true)
                    .queue();
        } else if (components != null) {
            event.replyComponents(ActionRow.of(components
                            .stream()
                            .map(JdaUtils::mapToItemComponent)
                            .toList())
                    )
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Override
    public String getName() {
        return event.getName();
    }
}