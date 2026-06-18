package de.gost0r.pickupbot.command;

import de.gost0r.pickupbot.command.common.BaseCommand;
import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.discord.jda.JdaDiscordUser;
import de.gost0r.pickupbot.pickup.Config;
import de.gost0r.pickupbot.pickup.PickupBot;
import de.gost0r.pickupbot.pickup.Player;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DonateCommand extends BaseCommand {

    private static final String OPTION_PLAYER = "player";
    private static final String OPTION_AMOUNT = "amount";

    private final PickupBot bot;

    public DonateCommand(PickupBot bot) {
        this.bot = bot;
    }

    @Override
    public void init() {
        setApplicationCommand(
                DiscordApplicationCommand
                        .builder()
                        .name("donate")
                        .description("Donate pugcoins to another player privately")
                        .options(List.of(
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.USER)
                                        .name(OPTION_PLAYER)
                                        .description("Player to donate to")
                                        .required(true)
                                        .build(),
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.INTEGER)
                                        .name(OPTION_AMOUNT)
                                        .description("Amount of pugcoins to donate")
                                        .required(true)
                                        .build()
                        ))
                        .build()
        );
    }

    @Override
    public void handle(DiscordSlashCommandInteraction interaction) {
        log.debug("Received donate command");
        interaction.deferReply();

        OptionMapping donateUser = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_PLAYER)).findFirst().orElse(null);
        OptionMapping amount = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_AMOUNT)).findFirst().orElse(null);

        if (donateUser == null || amount == null) {
            interaction.respondEphemeral("Invalid arguments");
            return;
        }

        Player player = Player.get(interaction.getUser());
        if (player == null) {
            interaction.respondEphemeral(Config.user_not_registered);
            return;
        }

        DiscordUser destDiscordUser = new JdaDiscordUser(donateUser.getAsMember(), donateUser.getAsUser());
        Player destPlayer = Player.get(destDiscordUser);
        if (destPlayer == null) {
            interaction.respondEphemeral(Config.player_not_found);
            return;
        }

        if (player.equals(destPlayer)) {
            interaction.respondEphemeral("You cannot donate to yourself.");
            return;
        }

        bot.getLogic().donatePlayer(interaction, player, destPlayer, amount.getAsInt());
    }
}
