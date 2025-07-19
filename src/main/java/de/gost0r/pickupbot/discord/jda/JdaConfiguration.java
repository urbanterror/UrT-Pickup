package de.gost0r.pickupbot.discord.jda;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.List;

@Configuration
public class JdaConfiguration {

    private final String token;

    public JdaConfiguration(@Value("${app.discord.token}") String token) {
        this.token = token;
    }

    @Bean
    public JDA jda(List<ListenerAdapter> listeners) {
        return JDABuilder.create(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .addEventListeners(listeners.toArray(new Object[0]))
                .build();
    }
}
