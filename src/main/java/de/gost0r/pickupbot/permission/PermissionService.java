package de.gost0r.pickupbot.permission;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.pickup.PickupRoleType;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {

    private final PickupRoleCache pickupRoleCache;

    public PermissionService(PickupRoleCache pickupRoleCache) {
        this.pickupRoleCache = pickupRoleCache;
    }

    public boolean hasStreamerRights(DiscordUser user) {
        return pickupRoleCache.getRolesByType(PickupRoleType.STREAMER)
                .stream()
                .anyMatch(role -> user.hasRoleById(role.getId()));
    }

    public boolean hasAdminRights(DiscordUser user) {
        return pickupRoleCache.getRolesByType(PickupRoleType.ADMIN)
                .stream()
                .anyMatch(role -> user.hasRoleById(role.getId()));
    }

    public boolean hasSuperAdminRights(DiscordUser user) {
        return pickupRoleCache.getRolesByType(PickupRoleType.SUPERADMIN)
                .stream()
                .anyMatch(role -> user.hasRoleById(role.getId()));
    }
}
