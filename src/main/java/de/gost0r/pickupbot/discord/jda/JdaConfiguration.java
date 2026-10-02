package de.gost0r.pickupbot.discord.jda;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

@Slf4j
@Configuration
public class JdaConfiguration {

    private final String token;

    public JdaConfiguration(@Value("${app.discord.token}") String token) {
        this.token = token;
    }

    @Bean
    public JDA jda(DiscordRequestBudget requestBudget) {
        RestAction.setDefaultFailure(failure -> log.error("Discord REST request failed", failure));
        return JDABuilder.create(
                        token,
                        EnumSet.of(
                                GatewayIntent.GUILD_MEMBERS,
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT,
                                GatewayIntent.DIRECT_MESSAGES
                        )
                )
                .setHttpClientBuilder(new okhttp3.OkHttpClient.Builder().addNetworkInterceptor(requestBudget))
                .build();
    }
}
