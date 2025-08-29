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
public class PardonCommand extends BaseCommand {

    private static final String OPTION_PLAYER = "player";
    private static final String OPTION_REASON = "reason";

    private final PickupBot bot;

    public PardonCommand(PickupBot bot) {
        this.bot = bot;
    }

    @Override
    public void init() {
        setApplicationCommand(DiscordApplicationCommand
                .builder()
                .name("pardon")
                .description("Unbans a player banned by the bot. Does not work on manual bans")
                .options(List.of(
                        DiscordCommandOption
                                .builder()
                                .type(DiscordCommandOptionType.USER)
                                .name(OPTION_PLAYER)
                                .description("Player to unban")
                                .required(true)
                                .build(),
                        DiscordCommandOption
                                .builder()
                                .type(DiscordCommandOptionType.STRING)
                                .name(OPTION_REASON)
                                .description("Reason for the unban")
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

        OptionMapping pardonedUser = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_PLAYER)).findFirst().orElse(null);
        OptionMapping reason = interaction.getOptions().stream().filter(o -> o.getName().equals(OPTION_REASON)).findFirst().orElse(null);
        if (pardonedUser == null || reason == null) {
            interaction.respondEphemeral("Invalid arguments");
            return;
        }

        Player player = Player.get(interaction.getUser());
        if (player == null) {
            interaction.respondEphemeral("You are not registered");
            return;
        }

        DiscordUser user = new JdaDiscordUser(pardonedUser.getAsMember());
        Player pardonedPlayer = Player.get(user);
        if (pardonedPlayer == null) {
            interaction.respondEphemeral(Config.player_not_found);
            return;
        }
        bot.getLogic().pardonPlayer(interaction, pardonedPlayer, reason.getAsString(), player);
    }

}
