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
public class RefundCommand extends BaseCommand {

    private static final String OPTION_PLAYER = "player";
    private static final String OPTION_AMOUNT = "amount";
    private static final String OPTION_REASON = "reason";

    private final PickupBot bot;

    public RefundCommand(PickupBot bot) {
        this.bot = bot;
    }

    @Override
    public void init() {
        setApplicationCommand(
                DiscordApplicationCommand
                        .builder()
                        .name("refund")
                        .description("Refund pugcoins to a player following a bot error")
                        .options(List.of(
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.USER)
                                        .name(OPTION_PLAYER)
                                        .description("Player to refund")
                                        .required(true)
                                        .build(),
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.INTEGER)
                                        .name(OPTION_AMOUNT)
                                        .description("Amount to refund")
                                        .required(true)
                                        .build(),
                                DiscordCommandOption
                                        .builder()
                                        .type(DiscordCommandOptionType.STRING)
                                        .name(OPTION_REASON)
                                        .description("Reason for the refund")
                                        .required(true)
                                        .build()
                        ))
                        .build()
        );
    }


    @Override
    public void handle(DiscordSlashCommandInteraction interaction) {
        log.debug("Received pardon command");
        interaction.deferReply();

        OptionMapping refundUser = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_PLAYER)).findFirst().orElse(null);
        OptionMapping amount = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_AMOUNT)).findFirst().orElse(null);
        OptionMapping reason = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_REASON)).findFirst().orElse(null);
        if (refundUser == null || amount == null || reason == null) {
            interaction.respondEphemeral("Invalid arguments");
            return;
        }

        Player player = Player.get(interaction.getUser());
        if (player == null) {
            interaction.respondEphemeral("You are not registered");
            return;
        }

        DiscordUser user = new JdaDiscordUser(refundUser.getAsMember());
        Player refundPlayer = Player.get(user);
        if (refundPlayer == null) {
            interaction.respondEphemeral(Config.player_not_found);
            return;
        }
        bot.getLogic().refundPlayer(interaction, refundPlayer, amount.getAsInt(), reason.getAsString(), player);
    }

}
