package adminarea.test;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.listeners.WandListener;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.scheduler.ServerScheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.nukkit.event.player.PlayerQuitEvent;

class WandListenerTest {

    @Mock private AdminAreaProtectionPlugin plugin;
    @Mock private Player player;
    @Mock private Block block;
    @Mock private Level level;
    @Mock private Item wandItem;
    @Mock private ServerScheduler scheduler;
    
    private WandListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Configure mocks
        when(player.getLevel()).thenReturn(level);
        when(player.hasPermission("adminarea.wand.use")).thenReturn(true);
        when(block.getLevel()).thenReturn(level);
        when(block.getX()).thenReturn(0.0);
        when(block.getY()).thenReturn(0.0);
        when(block.getZ()).thenReturn(0.0);
        
        // Configure wand item
        when(wandItem.hasCustomName()).thenReturn(true);
        when(wandItem.getCustomName()).thenReturn("§aArea Wand");
        when(player.getInventory().getItemInHand()).thenReturn(wandItem);
        
        // Configure plugin and scheduler
        when(plugin.getServer()).thenReturn(mock(cn.nukkit.Server.class));
        when(plugin.getServer().getScheduler()).thenReturn(scheduler);
        
        listener = new WandListener(plugin);
    }

    @Test
    void testBlockBreakWithWand() {
        BlockBreakEvent event = new BlockBreakEvent(player, block, wandItem, new Item[0], true);
        event.setInstaBreak(true);
        listener.onBlockBreak(event);
        
        assertTrue(event.isCancelled());
        verify(plugin).getPlayerPositions();
    }

    @Test
    void testBlockBreakWithoutPermission() {
        when(player.hasPermission("adminarea.wand.use")).thenReturn(false);
        BlockBreakEvent event = new BlockBreakEvent(player, block, wandItem, new Item[0], true);
        event.setInstaBreak(true);
        
        listener.onBlockBreak(event);
        
        assertTrue(event.isCancelled());
        verify(player).sendMessage("§cYou don't have permission to use the Area Wand.");
    }

    @Test
    void testPlayerInteractWithWand() {
        PlayerInteractEvent event = new PlayerInteractEvent(player, null, block, null, PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK);
        listener.onPlayerInteract(event);
        
        assertTrue(event.isCancelled());
        verify(plugin).getPlayerPositions();
    }

    @Test
    void testPlayerInteractWithoutWand() {
        when(wandItem.hasCustomName()).thenReturn(false);
        PlayerInteractEvent event = new PlayerInteractEvent(player, null, block, null, PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK);
        
        listener.onPlayerInteract(event);
        
        assertFalse(event.isCancelled());
    }

    @Test
    void testUndoWithNoHistory() {
        listener.undo(player);
        verify(player).sendMessage("§cNo actions to undo!");
    }

    @Test
    void testCleanup() {
        // Set up some state
        BlockBreakEvent event = new BlockBreakEvent(player, block, wandItem, new Item[0], true);
        event.setInstaBreak(true);
        listener.onBlockBreak(event);
        
        // Test cleanup
        listener.cleanup();
        
        // Verify clean state - we'd need to expose some state or add getters to test this properly
        listener.undo(player); // Should act as if no history exists
        verify(player).sendMessage("§cNo actions to undo!");
    }

    @Test
    void testVisualizationUpdates() {
        PlayerInteractEvent event = new PlayerInteractEvent(player, null, block, null, 
            PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK);
        
        // Configure first position
        when(block.getX()).thenReturn(0.0);
        when(block.getY()).thenReturn(0.0);
        when(block.getZ()).thenReturn(0.0);
        listener.onPlayerInteract(event);
        
        // Configure second position
        when(block.getX()).thenReturn(10.0);
        when(block.getY()).thenReturn(10.0);
        when(block.getZ()).thenReturn(10.0);
        listener.onPlayerInteract(event);
        
        // Verify visualization was started
        verify(plugin.getServer().getScheduler(), times(2))
            .scheduleRepeatingTask(eq(plugin), any(Runnable.class), eq(5));
    }

    @Test
    void testCleanupOnPlayerQuit() {
        // Set up some state
        PlayerInteractEvent event = new PlayerInteractEvent(player, null, block, null, 
            PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK);
        listener.onPlayerInteract(event);
        
        // Trigger player quit
        PlayerQuitEvent quitEvent = new PlayerQuitEvent(player, "");
        listener.onPlayerQuit(quitEvent);
        
        // Verify cleanup
        verify(scheduler).cancelTask(anyInt());
    }

    @Test
    void testCooldownPreventsSpam() {
        PlayerInteractEvent event = new PlayerInteractEvent(player, null, block, null, 
            PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK);
        
        // First interaction should work
        listener.onPlayerInteract(event);
        assertFalse(event.isCancelled());
        
        // Immediate second interaction should be blocked
        PlayerInteractEvent event2 = new PlayerInteractEvent(player, null, block, null, 
            PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK);
        listener.onPlayerInteract(event2);
        assertTrue(event2.isCancelled());
    }
}
