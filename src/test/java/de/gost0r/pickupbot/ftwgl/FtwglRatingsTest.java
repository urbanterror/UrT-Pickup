package de.gost0r.pickupbot.ftwgl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.models.PlayerRating;
import de.gost0r.pickupbot.pickup.Player;
import de.gost0r.pickupbot.pickup.Season;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FtwglRatingsTest {
    @Test
    void sendsSeasonDateAndKeepsBothRangesIncludingMissingRatings() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ratings", exchange -> {
            request.set(json.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"ratings":{"1":1.2,"2":1.8,"3":1.4},"secondary_ratings":{"1":1.6,"2":1.1,"4":1.3}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            FtwglApi api = new FtwglApi("http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                    Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2));
            List<Player> players = List.of(player("1"), player("2"), player("3"), player("4"), player("5"));
            var ratings = api.getPlayerRatings(players,
                    new Season(12, Instant.parse("2026-09-01T23:30:00Z").toEpochMilli(), Long.MAX_VALUE));

            assertEquals("2026-09-01", request.get().get("secondary_since").asText());
            assertEquals(json.readTree("[1,2,3,4,5]"), request.get().get("discord_ids"));
            assertEquals("test-key", authorization.get());
            assertEquals(new PlayerRating(1.2f, 1.6f), ratings.get(players.get(0)));
            assertEquals(1.6f, ratings.get(players.get(0)).captainRating());
            assertEquals(new PlayerRating(1.8f, 1.1f), ratings.get(players.get(1)));
            assertEquals(1.8f, ratings.get(players.get(1)).captainRating());
            assertEquals(new PlayerRating(1.4f, 0f), ratings.get(players.get(2)));
            assertEquals(new PlayerRating(0f, 1.3f), ratings.get(players.get(3)));
            assertEquals(PlayerRating.ZERO, ratings.get(players.get(4)));
        } finally {
            server.stop(0);
        }
    }

    private static Player player(String id) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        Player player = mock(Player.class);
        when(player.getDiscordUser()).thenReturn(user);
        return player;
    }
}
