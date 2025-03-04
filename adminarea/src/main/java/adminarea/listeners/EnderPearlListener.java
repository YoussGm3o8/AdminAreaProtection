package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.entity.projectile.EntityEnderPearl;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.entity.ProjectileHitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.event.player.PlayerTeleportEvent;

public class EnderPearlListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public EnderPearlListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItem();

        // Check if player is trying to use ender pearl
        if (item != null && item.getId() == Item.ENDER_PEARL) {
            // Check if ender pearls are allowed at player's position
            if (protectionListener.handleProtection(player.getPosition(), player, "allowEnderPearl")) {
                event.setCancelled(true);
                protectionListener.sendProtectionMessage(player, "messages.protection.enderPearl");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlLand(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EntityEnderPearl pearl)) {
            return;
        }

        if (!(pearl.shootingEntity instanceof Player player)) {
            return;
        }

        // Check if ender pearls are allowed at landing position
        if (protectionListener.handleProtection(event.getEntity().getPosition(), player, "allowEnderPearl")) {
            // Cancel the upcoming teleport by sending player back
            player.teleport(player.getPosition());
            protectionListener.sendProtectionMessage(player, "messages.protection.enderPearl");
        }
    }
}
