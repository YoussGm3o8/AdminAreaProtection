package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerDropItemEvent;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.level.Position;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class ItemListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;
    
    // Add cache for item drop/pickup checks
    private final Cache<String, Boolean> itemActionCache;
    private static final int CACHE_SIZE = 100;
    private static final long CACHE_EXPIRY = TimeUnit.SECONDS.toMillis(10);

    public ItemListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
        
        this.itemActionCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Position dropPos = player.getPosition();
            
            // Check bypass first for performance
            if (plugin.isBypassing(player.getName())) {
                return;
            }
            
            // Check cache
            String cacheKey = "drop:" + player.getName() + ":" + 
                             dropPos.getFloorX() / 16 + ":" + 
                             dropPos.getFloorZ() / 16; // Using chunk coords for better caching
            Boolean cachedResult = itemActionCache.getIfPresent(cacheKey);
            
            if (cachedResult != null) {
                if (cachedResult) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.itemDrop");
                }
                return;
            }
            
            // Get area at position directly rather than using shouldCancel() for better performance
            Area area = plugin.getHighestPriorityArea(
                dropPos.getLevel().getName(),
                dropPos.getX(),
                dropPos.getY(),
                dropPos.getZ()
            );
            
            // Check permission using the permission checker
            boolean shouldCancel = area != null && 
                !plugin.getPermissionOverrideManager().getPermissionChecker().isAllowed(player, area, "allowItemDrop");
            
            // Cache result
            itemActionCache.put(cacheKey, shouldCancel);
            
            if (shouldCancel) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.itemDrop");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "item_drop_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(InventoryPickupItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getInventory().getHolder() instanceof Player player) {
                Position itemPos = event.getItem().getPosition();
                
                // Check bypass first for performance
                if (plugin.isBypassing(player.getName())) {
                    return;
                }
                
                // Check cache
                String cacheKey = "pickup:" + player.getName() + ":" + 
                                 itemPos.getFloorX() / 16 + ":" + 
                                 itemPos.getFloorZ() / 16; // Using chunk coords for better caching
                Boolean cachedResult = itemActionCache.getIfPresent(cacheKey);
                
                if (cachedResult != null) {
                    if (cachedResult) {
                        event.setCancelled(true);
                        protectionListener.sendProtectionMessage(player, "messages.protection.itemPickup");
                    }
                    return;
                }
                
                // Get area at position directly
                Area area = plugin.getHighestPriorityArea(
                    itemPos.getLevel().getName(),
                    itemPos.getX(),
                    itemPos.getY(),
                    itemPos.getZ()
                );
                
                // Check permission using the permission checker
                boolean shouldCancel = area != null && 
                    !plugin.getPermissionOverrideManager().getPermissionChecker().isAllowed(player, area, "allowItemPickup");
                
                // Cache result
                itemActionCache.put(cacheKey, shouldCancel);
                
                if (shouldCancel) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.itemPickup");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "item_pickup_check");
        }
    }
    
    // Method to clear the cache when needed
    public void clearCache() {
        itemActionCache.invalidateAll();
    }
}
