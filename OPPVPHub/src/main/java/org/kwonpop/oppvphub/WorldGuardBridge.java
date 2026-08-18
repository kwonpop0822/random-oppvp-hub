package org.kwonpop.oppvphub;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.World;

/** Kept separate so the main plugin can load safely when WorldGuard is absent. */
final class WorldGuardBridge {
    static final Object REGION_MISSING = new Object();

    private WorldGuardBridge() { }

    static Object setPvp(World world, String regionId, boolean allow) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) return REGION_MISSING;
        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) return REGION_MISSING;
        StateFlag.State previous = region.getFlag(Flags.PVP);
        region.setFlag(Flags.PVP, allow ? StateFlag.State.ALLOW : StateFlag.State.DENY);
        try {
            manager.saveChanges();
        } catch (Exception ex) {
            throw new IllegalStateException("WorldGuard 지역 저장 실패", ex);
        }
        return previous;
    }

    static void restorePvp(World world, String regionId, Object original) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) return;
        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) return;
        region.setFlag(Flags.PVP, original instanceof StateFlag.State state ? state : null);
        try {
            manager.saveChanges();
        } catch (Exception ex) {
            throw new IllegalStateException("WorldGuard 지역 복구 저장 실패", ex);
        }
    }
}
