package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerDropItemEvent;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import io.micrometer.core.instrument.Timer;

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
            if (protectionListener.handleProtection(event.getPlayer(), event.getPlayer(), "item_drop")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(event.getPlayer(), "messages.protection.itemDrop");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "item_drop_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(InventoryPickupItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getInventory().getHolder() instanceof Player player &&
                protectionListener.handleProtection(event.getItem(), player, "item_pickup")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.itemPickup");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "item_pickup_check");
        }
    }
}
