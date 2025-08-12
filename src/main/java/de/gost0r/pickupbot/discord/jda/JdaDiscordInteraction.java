package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import lombok.Getter;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;

import java.util.ArrayList;
import java.util.List;

public class JdaDiscordInteraction implements DiscordInteraction {
    @Getter
    private final DiscordUser user;
    @Getter
    private final DiscordMessage message;

    private final GenericComponentInteractionCreateEvent event;
    private final List<String> values;

    public JdaDiscordInteraction(GenericComponentInteractionCreateEvent event) {
        this.event = event;
        user = new JdaDiscordUser(event.getMember(), event.getUser());
        message = new JdaDiscordMessage(event.getMessage());

        values = event instanceof StringSelectInteractionEvent ? ((StringSelectInteractionEvent) event).getInteraction().getValues() : null;
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
        if (content != null) {
            event.reply(content)
                    .setEmbeds(JdaUtils.mapToMessageEmbed(embed))
                    .queue();
        } else {
            event.replyEmbeds(JdaUtils.mapToMessageEmbed(embed)).queue();
        }
    }

    @Override
    public void respond(String content, DiscordEmbed embed, ArrayList<DiscordComponent> components) {
        if (content != null) {
            event.reply(content)
                    .setEmbeds(JdaUtils.mapToMessageEmbed(embed))
                    .addActionRow(components.stream().map(JdaUtils::mapToItemComponent).toList())
                    .queue();
        } else if (embed != null) {
            event.replyEmbeds(JdaUtils.mapToMessageEmbed(embed))
                    .addActionRow(components.stream().map(JdaUtils::mapToItemComponent).toList())
                    .queue();
        } else {
            event.replyComponents(ActionRow.of(components.stream().map(JdaUtils::mapToItemComponent).toList())).queue();
        }
    }

    @Override
    public String getId() {
        return event.getId();
    }

    @Override
    public String getToken() {
        return event.getToken();
    }

    @Override
    public String getComponentId() {
        return event.getComponentId();
    }

    @Override
    public List<String> getValues() {
        return values;
    }
}