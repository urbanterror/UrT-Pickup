package de.gost0r.pickupbot.ftwgl.models;

import lombok.Data;

@Data
public class RentServerByIdResponse {

    private int id;

    private Config config;

    @Data
    public static class Config {
        private String ip;
        private String password;
        private String rcon;
    }

}
