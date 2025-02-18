package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.*;
import cn.nukkit.block.BlockID;
import io.micrometer.core.instrument.Timer;

public class EnvironmentListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final ProtectionListener protectionListener;

    public EnvironmentListener(AdminAreaProtectionPlugin plugin, ProtectionListener protectionListener) {
        this.plugin = plugin;
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (protectionListener.handleProtection(event.getBlock(), null, "fire_spread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(LiquidFlowEvent event) {
        if (protectionListener.handleProtection(event.getBlock(), null, "fluid_flow")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (protectionListener.handleProtection(event.getBlock(), null, "grass_growth")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_spread_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (protectionListener.handleProtection(event.getBlock(), null, "fire_spread")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_ignite_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSoilDry(BlockFadeEvent event) {
        if (event.getBlock().getId() == BlockID.FARMLAND && 
            protectionListener.handleProtection(event.getBlock(), null, "soil_dry")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        if (protectionListener.handleProtection(event.getBlock(), null, "crop_growth")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent event) {
        if (protectionListener.handleProtection(event.getBlock(), null, "leaf_decay")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        String permission = event.getBlock().getId() == BlockID.ICE ? "ice_form" : " snow_form";
        if (protectionListener.handleProtection(event.getBlock(), null, permission)) {
            event.setCancelled(true);
        }
    }
}
