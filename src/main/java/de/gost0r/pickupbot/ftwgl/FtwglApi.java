package de.gost0r.pickupbot.ftwgl;

import de.gost0r.pickupbot.ftwgl.models.*;
import de.gost0r.pickupbot.pickup.Config;
import de.gost0r.pickupbot.pickup.Country;
import de.gost0r.pickupbot.pickup.Player;
import de.gost0r.pickupbot.pickup.Region;
import de.gost0r.pickupbot.pickup.server.Server;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FtwglApi {

    private final RestClient restClient;

    public FtwglApi(
            @Value("${app.ftw.url}") String apiUrl,
            @Value("${app.ftw.key}") String apiKey
    ) {
        restClient = RestClient
                .builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", apiKey)
                .defaultHeader("User-Agent", "Bot")
                .build();
    }

    public String launchAC(Player player, String ip, String password) {
        LaunchClientRequest request = LaunchClientRequest.builder()
                .discordId(Long.parseLong(player.getDiscordUser().id))
                .address(ip)
                .password(password)
                .build();

        try {
            HttpStatusCode statusCode = sendPostRequest("/launch/pug", request, String.class).getStatusCode();
            return statusCode == HttpStatus.OK ? Config.ftw_success : Config.ftw_notconnected;
        } catch (Exception e) {
            log.warn("Exception: ", e);
            return Config.ftw_notconnected;
        }
    }

    public boolean hasLauncherOn(Player player) {
        String url = "/connected/launcher/" + player.getDiscordUser().id;
        try {
            ResponseEntity<String> response = sendGetRequest(url, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
            return false;
        }
    }

    public boolean checkIfPingStored(Player player) {
        String url = "/ping/" + player.getDiscordUser().id;
        try {
            return sendHeadRequest(url).getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
            return false;
        }
    }

    public String requestPingUrl(Player player) {
        PingUrlRequest request = PingUrlRequest
                .builder()
                .discordId(Long.parseLong(player.getDiscordUser().id))
                .username(player.getDiscordUser().username)
                .urtAuth(player.getUrtauth())
                .build();

        try {
            PingUrlResponse response = sendPostRequest("/ping", request, PingUrlResponse.class).getBody();
            assert response != null;
            return response.getUrl();
        } catch (Exception e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
            return Config.ftw_error;
        }
    }

    public Server spawnDynamicServer(List<Player> playerList) {
        RentPugRequest request = RentPugRequest.builder()
                .discordIds(playerList.stream()
                        .map(player -> Long.parseLong(player.getDiscordUser().id))
                        .toList())
                .build();

        try {
            RentPugResponse response = sendPostRequest("/rent/pug", request, RentPugResponse.class).getBody();
            assert response != null;
            if (response.getServer() == null || response.getServer().getConfig() == null) {
                log.warn("CAN'T SPAWN: {}", response);
                return null;
            }
            Server server = new Server(
                    response.getServer().getId(),
                    null,
                    response.getServer().getPort(),
                    response.getServer().getConfig().getRcon(),
                    response.getServer().getConfig().getPassword(),
                    true,
                    Region.valueOf(Country.getContinent(response.getServerLocation().getCountry()))
            );
            server.country = response.getServerLocation().getCountry();
            server.city = response.getServerLocation().getCity();
            server.playerPing = new HashMap<>();
            for (Player player : playerList) {
                server.playerPing.put(
                        player,
                        response.getPings()
                                .getOrDefault(Long.parseLong(player.getDiscordUser().id), 0)
                );
            }
            return server;
        } catch (Exception e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
            return null;
        }
    }

    public void queryAndUpdateServerIp(Server server) {
        String url = "/rent/" + server.id;
        try {
            RentServerByIdResponse response = sendGetRequest(url, RentServerByIdResponse.class).getBody();
            assert response != null;
            if (response.getConfig() == null || response.getConfig().getIp() == null) {
                Thread.sleep(1000);
                queryAndUpdateServerIp(server);
                return;
            }
            server.IP = response.getConfig().getIp();

            // max timeout of 5min
            Instant end = Instant.now().plus(5, ChronoUnit.MINUTES);
            while (!server.isOnline() && Instant.now().isBefore(end)) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            log.warn("InterruptedException: ", e);
            Sentry.captureException(e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public float getPlayerRatings(Player player) {
        return getPlayerRatings(List.of(player)).getOrDefault(player, 0f);
    }

    public Map<Player, Float> getPlayerRatings(List<Player> playerList) {
        PlayerRatingsRequest request = PlayerRatingsRequest.builder()
                .discordIds(playerList.stream()
                        .map(player -> Long.parseLong(player.getDiscordUser().id))
                        .toList())
                .build();
        try {
            PlayerRatingsResponse response = sendPostRequest("/ratings", request, PlayerRatingsResponse.class).getBody();
            assert response != null;

            Map<Player, Float> ratings = new HashMap<>();
            for (Player player : playerList) {
                ratings.put(
                        player,
                        response.getRatings()
                                .getOrDefault(Long.parseLong(player.getDiscordUser().id), 0d)
                                .floatValue()
                );
            }
            return ratings;
        } catch (Exception e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return Collections.emptyMap();
    }

    @Retryable(retryFor = {RetryableHttpException.class})
    private synchronized <T> ResponseEntity<T> sendPostRequest(String url, Object body, Class<T> responseType) {
        log.trace("Creating POST request to: {}", url);
        ResponseEntity<T> response = restClient.post()
                .uri(url)
                .header("charset", "utf-8")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handle4xxErrors)
                .onStatus(HttpStatusCode::is5xxServerError, this::handle5xxErrors)
                .toEntity(responseType);
        log.trace("POST API call to {} results in: ({}) {}", url, response.getStatusCode(), response.getBody());
        return response;
    }

    @Retryable(retryFor = {RetryableHttpException.class})
    private synchronized <T> ResponseEntity<T> sendGetRequest(String url, Class<T> responseType) {
        log.trace("Creating GET request to: {}", url);
        ResponseEntity<T> response = restClient.get()
                .uri(url)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handle4xxErrors)
                .onStatus(HttpStatusCode::is5xxServerError, this::handle5xxErrors)
                .toEntity(responseType);
        log.trace("GET API call to {} results in: ({}) {}", url, response.getStatusCode(), response.getBody());
        return response;
    }

    @Retryable(retryFor = {RetryableHttpException.class})
    private synchronized ResponseEntity<Void> sendHeadRequest(String url) {
        log.trace("Creating HEAD request to: {}", url);
        ResponseEntity<Void> response = restClient.head()
                .uri(url)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handle4xxErrors)
                .onStatus(HttpStatusCode::is5xxServerError, this::handle5xxErrors)
                .toBodilessEntity();
        log.trace("HEAD API call to {} results in: ({})", url, response.getStatusCode());
        return response;

    }

    private void handle4xxErrors(HttpRequest request, ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().value() == 429) {
            throw new RetryableHttpException(429, "Rate limit exceeded");
        }
        log.warn("API call failed: ({}) {} for {}", response.getStatusCode(), response.getStatusText(), request.getURI());
    }

    private void handle5xxErrors(HttpRequest request, ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().value() == 502) {
            throw new RetryableHttpException(502, "Bad Gateway");
        }
        log.error("API call failed: ({}) {} for {}", response.getStatusCode(), response.getStatusText(), request.getURI());
    }
}