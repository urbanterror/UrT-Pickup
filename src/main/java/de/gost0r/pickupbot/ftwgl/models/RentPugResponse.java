package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
public class RentPugResponse {

    private Server server;

    @JsonProperty(value = "server_location")
    private ServerLocation serverLocation;

    private Map<Long, Integer> pings;

    @Data
    public static class Server {
        private int id;
        private int port;
        private Config config;

        @Data
        public static class Config {
            private String rcon;
            private String password;
        }
    }

    @Data
    public static class ServerLocation {
        private String country;
        private String city;
    }

}
