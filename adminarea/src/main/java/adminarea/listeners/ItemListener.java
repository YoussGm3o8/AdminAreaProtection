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
            
            // Use the protectionListener.handleProtection method directly
            // Note: handleProtection returns true if protection should be applied (action blocked)
            boolean shouldCancel = protectionListener.handleProtection(dropPos, player, "allowItemDrop");
            
            // Cache result
            itemActionCache.put(cacheKey, shouldCancel);
            
            if (shouldCancel) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.itemDrop");
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Blocked item drop by " + player.getName() + 
                                " at " + dropPos.getFloorX() + "," + dropPos.getFloorY() + "," + 
                                dropPos.getFloorZ());
                }
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
                
                // Use the protectionListener.handleProtection method directly
                // Note: handleProtection returns true if protection should be applied (action blocked)
                boolean shouldCancel = protectionListener.handleProtection(itemPos, player, "allowItemPickup");
                
                // Cache result
                itemActionCache.put(cacheKey, shouldCancel);
                
                if (shouldCancel) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.itemPickup");
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Blocked item pickup by " + player.getName() + 
                                    " at " + itemPos.getFloorX() + "," + itemPos.getFloorY() + "," + 
                                    itemPos.getFloorZ());
                    }
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
