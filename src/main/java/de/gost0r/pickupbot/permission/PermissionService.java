package de.gost0r.pickupbot.permission;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.pickup.PickupRoleType;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {

    private final PickupRoleCache pickupRoleCache;
    private final RoleService roleService;

    public PermissionService(PickupRoleCache pickupRoleCache, RoleService roleService) {
        this.pickupRoleCache = pickupRoleCache;
        this.roleService = roleService;
    }

    public boolean hasStreamerRights(DiscordUser user) {
        return pickupRoleCache.getRolesByType(PickupRoleType.STREAMER)
                .stream()
                .anyMatch(role -> roleService.hasRole(user, role));
    }

    public boolean hasAdminRights(DiscordUser user) {
        return pickupRoleCache.getRolesByType(PickupRoleType.ADMIN)
                .stream()
                .anyMatch(role -> roleService.hasRole(user, role));
    }

    public boolean hasSuperAdminRights(DiscordUser user) {
        return pickupRoleCache.getRolesByType(PickupRoleType.SUPERADMIN)
                .stream()
                .anyMatch(role -> roleService.hasRole(user, role));
    }
}
