package adminarea;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerInteractEvent.Action;
import cn.nukkit.level.Position;


public class WandListener implements Listener {

    private final AdminAreaProtectionPlugin plugin;

    public WandListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // Cancel if player is holding the Area Wand
        Player player = event.getPlayer();
        if (player.getInventory().getItemInHand().hasCustomName()
            && "§aArea Wand".equals(player.getInventory().getItemInHand().getCustomName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getInventory().getItemInHand().hasCustomName()
            && "§aArea Wand".equals(player.getInventory().getItemInHand().getCustomName())) {

            // We only care if we're interacting with a block
            if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                Block block = event.getBlock();
                Position[] positions = plugin.getPlayerPositions()
                    .getOrDefault(player.getName(), new Position[2]);

                if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                    positions[0] = Position.fromObject(block, block.getLevel());
                    player.sendMessage("§ePos1 set to: " + block.getX() + ", " + block.getY() + ", " + block.getZ());
                } else {
                    positions[1] = Position.fromObject(block, block.getLevel());
                    player.sendMessage("§ePos2 set to: " + block.getX() + ", " + block.getY() + ", " + block.getZ());
                }
                plugin.getPlayerPositions().put(player.getName(), positions);
            }
        }
    }
}
