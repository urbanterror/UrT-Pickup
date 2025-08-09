package de.gost0r.pickupbot.permission;

import de.gost0r.pickupbot.discord.DiscordRole;
import de.gost0r.pickupbot.pickup.PickupRoleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


// TODO this should later be used as Cache
@Component
public class PickupRoleCache {

    private final Map<PickupRoleType, List<DiscordRole>> roleCache = new java.util.HashMap<>();

    public List<DiscordRole> getRolesByType(PickupRoleType type) {
        return roleCache.getOrDefault(type, new ArrayList<>());
    }

    public void addRoleByType(PickupRoleType type, DiscordRole... roles) {
        roleCache.putIfAbsent(type, new ArrayList<>());
        roleCache.get(type).addAll(Arrays.stream(roles).toList());
    }

    public void removeRoleByType(PickupRoleType type, DiscordRole... roles) {
        roleCache.putIfAbsent(type, new ArrayList<>());
        roleCache.get(type).removeAll(Arrays.stream(roles).toList());
    }
}
