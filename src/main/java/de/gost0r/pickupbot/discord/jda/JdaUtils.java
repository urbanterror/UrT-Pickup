package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.*;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdaUtils {

    @NotNull
    public static MessageEmbed mapToMessageEmbed(DiscordEmbed embed) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle(embed.getTitle());
        embedBuilder.setDescription(embed.getDescription());
        embedBuilder.setThumbnail(embed.getThumbnail());
        embedBuilder.setColor(embed.getColor());
        embed.getFields().forEach(field -> embedBuilder.addField(field.name(), field.value(), field.inline()));
        embedBuilder.setFooter(embed.getFooterText(), embed.getFooterIcon());
        if (embed.getTimestamp() != null) embedBuilder.setTimestamp(Instant.ofEpochMilli(embed.getTimestamp()));
        return embedBuilder.build();
    }

    public static ItemComponent mapToItemComponent(DiscordComponent component) {
        if (component instanceof DiscordButton button) {
            Button btn = Button.of(mapToButtonStyle(button.getStyle()), button.getCustomId(), button.getLabel());
            if (button.getEmoji() != null) {
                btn = btn.withEmoji(Emoji.fromCustom(button.getEmoji().name(), Long.parseLong(button.getEmoji().id()), false));
            }
            return btn;
        }
        if (component instanceof DiscordSelectMenu selectMenu) {
            List<SelectOption> options = selectMenu.getOptions()
                    .stream()
                    .map(option -> SelectOption.of(option.label(), option.value()))
                    .toList();
            return StringSelectMenu.create(selectMenu.getCustomId())
                    .setDisabled(selectMenu.isDisabled())
                    .addOptions(options)
                    .build();
        }
        return null;
    }

    public static List<List<ItemComponent>> mapToActionRows(List<DiscordComponent> components) {
        List<List<ItemComponent>> rows = new ArrayList<>();
        List<ItemComponent> componentList = new ArrayList<>();
        for (DiscordComponent component : components) {
            componentList.add(JdaUtils.mapToItemComponent(component));
            if (componentList.size() == 5) {
                rows.add(new ArrayList<>(componentList));
                componentList.clear();
            }
        }
        if (!componentList.isEmpty()) {
            rows.add(new ArrayList<>(componentList));
        }
        return rows;
    }

    public static ButtonStyle mapToButtonStyle(DiscordButtonStyle style) {
        return switch (style) {
            case NONE, PURPLE -> ButtonStyle.PRIMARY;
            case GREY -> ButtonStyle.SECONDARY;
            case GREEN -> ButtonStyle.SUCCESS;
            case RED -> ButtonStyle.DANGER;
            case URL -> ButtonStyle.LINK;
        };
    }

    public static OptionData mapToCommandOption(DiscordCommandOption option) {
        return new OptionData(mapToOptionType(option.type()), option.name(), option.description(), true)
                .addChoices(option.choices().stream()
                        .map(choice -> new Command.Choice(choice.name, choice.value))
                        .toList()
                );
    }

    public static OptionType mapToOptionType(DiscordCommandOptionType type) {
        return switch (type) {
            case NONE -> OptionType.UNKNOWN;
            case SUB_COMMAND -> OptionType.SUB_COMMAND;
            case SUB_COMMAND_GROUP -> OptionType.SUB_COMMAND_GROUP;
            case STRING -> OptionType.STRING;
            case INTEGER -> OptionType.INTEGER;
            case BOOLEAN -> OptionType.BOOLEAN;
            case USER -> OptionType.USER;
            case CHANNEL -> OptionType.CHANNEL;
            case ROLE -> OptionType.ROLE;
            case MENTIONABLE -> OptionType.MENTIONABLE;
            case NUMBER -> OptionType.NUMBER;
            case ATTACHMENT -> OptionType.ATTACHMENT;
        };
    }
}
