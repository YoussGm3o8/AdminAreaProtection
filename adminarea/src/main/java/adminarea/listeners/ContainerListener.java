package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.*;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityMinecartChest;
import cn.nukkit.entity.item.EntityMinecartHopper;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.inventory.InventoryOpenEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

/**
 * Listens for container access events and records statistics.
 */
public class ContainerListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;

    public ContainerListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle inventory open events to track container access
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (!(event.getPlayer() instanceof Player)) {
                return;
            }
            
            Player player = (Player) event.getPlayer();
            String containerType = "unknown";
            Position pos = null;
            
            // Determine container type and position based on holder
            if (event.getInventory().getHolder() instanceof BlockEntityContainer) {
                BlockEntityContainer container = (BlockEntityContainer) event.getInventory().getHolder();
                BlockEntity blockEntity = (BlockEntity) container;
                pos = new Position(blockEntity.getX(), blockEntity.getY(), blockEntity.getZ(), blockEntity.getLevel());
                
                // Identify the container type
                if (container instanceof BlockEntityChest) {
                    containerType = "chest";
                } else if (container instanceof BlockEntityFurnace) {
                    containerType = "furnace";
                } else if (container instanceof BlockEntityHopper) {
                    containerType = "hopper";
                } else if (container instanceof BlockEntityShulkerBox) {
                    containerType = "shulker_box";
                } else if (container instanceof BlockEntityBarrel) {
                    containerType = "barrel";
                } else if (container instanceof BlockEntityDispenser || container instanceof BlockEntityDropper) {
                    containerType = "dispenser";
                } else {
                    containerType = "container";
                }
            } else if (event.getInventory().getHolder() instanceof EntityMinecartChest) {
                EntityMinecartChest cart = (EntityMinecartChest) event.getInventory().getHolder();
                pos = cart.getPosition();
                containerType = "minecart_chest";
            } else if (event.getInventory().getHolder() instanceof EntityMinecartHopper) {
                EntityMinecartHopper cart = (EntityMinecartHopper) event.getInventory().getHolder();
                pos = cart.getPosition();
                containerType = "minecart_hopper";
            }
            
            // If we have a position, record the container access
            if (pos != null) {
                Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(pos);
                if (area != null) {
                    try {
                        plugin.getAreaManager().getAreaStats(area.getName())
                            .recordContainerAccess(area.getName(), player.getName(), containerType);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Recorded container access for player " + player.getName() + 
                                " to " + containerType + " in area " + area.getName());
                        }
                    } catch (Exception e) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Failed to record container access: " + e.getMessage());
                        }
                    }
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "container_access_record");
        }
    }
} 