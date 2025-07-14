package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PingUrlRequest {

    @JsonProperty(value = "discord_id")
    private Long discordId;

    private String username;

    @JsonProperty(value = "urt_auth")
    private String urtAuth;

}
