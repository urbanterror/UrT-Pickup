package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PingUrlResponse {

    private String url;

}
