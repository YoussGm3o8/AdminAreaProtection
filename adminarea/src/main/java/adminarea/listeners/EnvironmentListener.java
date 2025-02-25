package adminarea.listeners;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.*;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.Player;

public class EnvironmentListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    private static final int CHUNK_CHECK_RADIUS = 4;
    
    // Add local cache for environment checks to reduce database lookups
    private final Cache<String, Boolean> protectionCache;
    private static final int CACHE_SIZE = 1000;
    private static final long CACHE_EXPIRY = TimeUnit.SECONDS.toMillis(30); // Short TTL for environment events

    public EnvironmentListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
        
        this.protectionCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
    }

    private boolean shouldCheckProtection(Block block, String permission) {
        if (block == null || block.getLevel() == null) {
            return false;
        }

        try {
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                Position pos = new Position(block.x, block.y, block.z, block.level);
                
                // Generate cache key
                String cacheKey = block.getLevel().getName() + ":" + 
                                 block.getChunkX() + ":" + 
                                 block.getChunkZ() + ":" +
                                 permission;
                
                // Check cache first
                Boolean cached = protectionCache.getIfPresent(cacheKey);
                if (cached != null) {
                    return cached;
                }
                
                // Skip unloaded chunks entirely
                if (!block.getLevel().isChunkLoaded(block.getChunkX(), block.getChunkZ())) {
                    protectionCache.put(cacheKey, false);
                    return false;
                }

                // Skip if world is being unloaded
                if (!plugin.getServer().isLevelLoaded(block.getLevel().getName())) {
                    protectionCache.put(cacheKey, false);
                    return false;
                }

                // Master check with try-catch
                try {
                    if (!plugin.getAreaManager().shouldProcessEvent(pos, false)) {
                        protectionCache.put(cacheKey, false);
                        return false;
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error in shouldProcessEvent", e);
                    return false;
                }

                // Get the global area first - more efficient
                Area globalArea = plugin.getAreaManager().getGlobalAreaForWorld(block.getLevel().getName());
                if (globalArea != null) {
                    boolean result = !globalArea.getToggleState(permission);
                    protectionCache.put(cacheKey, result);
                    return result;
                }

                // Only check local areas if we're near a player
                List<Area> areas = plugin.getAreaManager().getAreasAtLocation(
                    pos.getLevel().getName(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
                );

                // No protection if no areas
                if (areas.isEmpty()) {
                    protectionCache.put(cacheKey, false);
                    return false;
                }

                // Check highest priority area first
                boolean result = !areas.get(0).getToggleState(permission);
                protectionCache.put(cacheKey, result);
                return result;

            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "environment_protection_check");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error checking protection", e);
            return false; // Fail safe
        }
    }

    // Optimized isNearAnyPlayer with bbox check
    private boolean isNearAnyPlayer(Block block) {
        int blockChunkX = block.getChunkX();
        int blockChunkZ = block.getChunkZ();
        String worldName = block.getLevel().getName();
        
        // Calculate chunk bounds for more efficient player checks
        int minChunkX = blockChunkX - CHUNK_CHECK_RADIUS;
        int maxChunkX = blockChunkX + CHUNK_CHECK_RADIUS;
        int minChunkZ = blockChunkZ - CHUNK_CHECK_RADIUS;
        int maxChunkZ = blockChunkZ + CHUNK_CHECK_RADIUS;

        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            // Skip players in different worlds
            if (!player.getLevel().getName().equals(worldName)) {
                continue;
            }

            int playerChunkX = player.getChunkX();
            int playerChunkZ = player.getChunkZ();

            // Check if player is within chunk radius using bbox check (more efficient)
            if (playerChunkX >= minChunkX && playerChunkX <= maxChunkX &&
                playerChunkZ >= minChunkZ && playerChunkZ <= maxChunkZ) {
                return true;
            }
        }

        return false;
    }

    // Event handlers with optimized protection checks
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowFireSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(LiquidFlowEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowLiquid")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (event == null || event.getBlock() == null) return;
        
        try {
            Block block = event.getBlock();
            
            // Skip unloaded chunks
            if (!block.getLevel().isChunkLoaded(block.getChunkX(), block.getChunkZ())) {
                return;
            }

            if (shouldCheckProtection(block, "allowBlockSpread")) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling block spread", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (shouldCheckProtection(event.getBlock(), "allowFireStart")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_ignite_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSoilDry(BlockFadeEvent event) {
        if (event.getBlock().getId() == BlockID.FARMLAND && 
            shouldCheckProtection(event.getBlock(), "allowBlockSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowBlockSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent event) {
        if (shouldCheckProtection(event.getBlock(), "allowLeafDecay")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        String permission = event.getBlock().getId() == BlockID.ICE ? "allowIceForm" : "allowSnowForm";
        if (shouldCheckProtection(event.getBlock(), permission)) {
            event.setCancelled(true);
        }
    }

    // Add method to invalidate the cache - call this when areas or permissions change
    public void invalidateCache() {
        protectionCache.invalidateAll();
    }
}
