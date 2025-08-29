package de.gost0r.pickupbot.command;

import de.gost0r.pickupbot.command.common.BaseCommand;
import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.pickup.PickupBot;
import de.gost0r.pickupbot.pickup.Player;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class BetCommand extends BaseCommand {

    private static final String OPTION_MATCH_ID = "matchid";
    private static final String OPTION_TEAM = "team";
    private static final String OPTION_AMOUNT = "amount";

    private final PickupBot bot;

    public BetCommand(PickupBot bot) {
        this.bot = bot;
    }

    @Override
    public void init() {
        setApplicationCommand(
                DiscordApplicationCommand
                        .builder()
                        .name("bet")
                        .description("Place a bet for a game")
                        .options(List.of(
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.INTEGER)
                                        .name(OPTION_MATCH_ID)
                                        .description("The game number")
                                        .required(true)
                                        .build(),
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.STRING)
                                        .name(OPTION_TEAM)
                                        .description("The color of the team you want to bet on")
                                        .required(true)
                                        .choices(List.of(
                                                new DiscordCommandOptionChoice("red", "red"),
                                                new DiscordCommandOptionChoice("blue", "blue")
                                        ))
                                        .build(),
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.INTEGER)
                                        .name(OPTION_AMOUNT)
                                        .description("The amount of coins you want to bet")
                                        .required(true)
                                        .build()
                        ))
                        .build()
        );
    }

    @Override
    public void handle(DiscordSlashCommandInteraction interaction) {
        log.debug("Received bet command");
        interaction.deferReply();
        OptionMapping matchId = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_MATCH_ID)).findFirst().orElse(null);
        OptionMapping team = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_TEAM)).findFirst().orElse(null);
        OptionMapping amount = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_AMOUNT)).findFirst().orElse(null);
        if (matchId == null || team == null || amount == null) {
            interaction.respondEphemeral("Invalid arguments");
            return;
        }

        Player player = Player.get(interaction.getUser());
        if (player == null) {
            interaction.respondEphemeral("You are not registered");
            return;
        }

        bot.getLogic().bet(interaction, matchId.getAsInt(), team.getAsString(), amount.getAsInt(), player);
    }
}
