package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerDropItemEvent;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.level.Position;

public class ItemListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public ItemListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Position dropPos = player.getPosition();
            
            // Get area at position
            var area = plugin.getHighestPriorityArea(
                dropPos.getLevel().getName(),
                dropPos.getX(),
                dropPos.getY(),
                dropPos.getZ()
            );
            
            // Check permission using the permission checker
            if (area != null && !plugin.getOverrideManager().getPermissionChecker().isAllowed(player, area, "allowItemDrop")) {
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
                
                // Get area at position
                var area = plugin.getHighestPriorityArea(
                    itemPos.getLevel().getName(),
                    itemPos.getX(),
                    itemPos.getY(),
                    itemPos.getZ()
                );
                
                // Check permission using the permission checker
                if (area != null && !plugin.getOverrideManager().getPermissionChecker().isAllowed(player, area, "allowItemPickup")) {
                    event.setCancelled(true);
                    protectionListener.sendProtectionMessage(player, "messages.protection.itemPickup");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "item_pickup_check");
        }
    }
}
