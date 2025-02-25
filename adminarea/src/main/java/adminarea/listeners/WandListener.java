package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Position;
import cn.nukkit.level.particle.DustParticle;
import cn.nukkit.level.particle.Particle;
import cn.nukkit.math.Vector3;
import cn.nukkit.utils.LogLevel;
import io.micrometer.core.instrument.Timer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WandListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, Long> lastActionTime;
    private final Map<String, List<Position[]>> undoHistory;
    private final Map<String, Iterator<Vector3>> visualizationPoints;
    private final Map<String, Integer> visualizationTasks;
    private static final long COOLDOWN_MS = 250; // 250ms cooldown
    private static final int MAX_UNDO_HISTORY = 10;
    private static final int PARTICLE_DENSITY = 2; // Distance between particles
    private static final int VISUALIZATION_TICKS = 200; // 10 seconds
    private static final int PARTICLE_BATCH_SIZE = 10;

    public WandListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.lastActionTime = new ConcurrentHashMap<>();
        this.undoHistory = new ConcurrentHashMap<>();
        this.visualizationPoints = new ConcurrentHashMap<>();
        this.visualizationTasks = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            if (!isHoldingWand(player)) return;
            
            if (!player.hasPermission("adminarea.wand.use")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                event.setCancelled(true);
                return;
            }

            if (isOnCooldown(player)) {
                event.setCancelled(true);
                return;
            }

            // Handle shift + left click
            if (player.isSneaking()) {
                plugin.getPlayerPositions().remove(player.getName());
                player.sendMessage(plugin.getLanguageManager().get("messages.selectionCleared"));
                event.setCancelled(true);
                updateLastActionTime(player);
                return;
            }

            handleWandAction(player, event.getBlock(), 0);
            event.setCancelled(true);
            updateLastActionTime(player);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "wand_block_break");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            if (!isHoldingWand(player)) return;

            if (!player.hasPermission("adminarea.wand.use")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                event.setCancelled(true);
                return;
            }

            if (isOnCooldown(player)) {
                event.setCancelled(true);
                return;
            }

            if (event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
                // Handle shift + right click
                if (player.isSneaking()) {
                    // Check if both positions are set
                    Position[] positions = plugin.getPlayerPositions().get(player.getName());
                    if (positions != null && positions[0] != null && positions[1] != null) {
                        plugin.getGuiManager().openCreateForm(player);
                    } else {
                        player.sendMessage(plugin.getLanguageManager().get("wand.positionsNeeded"));
                    }
                    event.setCancelled(true);
                    updateLastActionTime(player);
                    return;
                }

                handleWandAction(player, event.getBlock(), 1);
                event.setCancelled(true);
                updateLastActionTime(player);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "wand_interact");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        cleanupPlayer(playerName);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Position[] positions = plugin.getPlayerPositions().get(player.getName());
        
        // Show temporary particles when near selection points
        if (positions != null) {
            for (Position pos : positions) {
                if (pos != null && pos.distance(event.getTo()) <= 16) { // Only show within 16 blocks
                    showTemporaryParticles(player, pos);
                }
            }
        }
    }

    private void handleWandAction(Player player, Block block, int positionIndex) {
        String playerName = player.getName();
        Position[] positions = plugin.getPlayerPositions()
            .computeIfAbsent(playerName, k -> new Position[2]);

        // Save to undo history before updating
        saveToUndoHistory(playerName, positions.clone());

        // Update position with floor coordinates
        positions[positionIndex] = Position.fromObject(block, block.getLevel()).floor();
        plugin.getPlayerPositions().put(playerName, positions);

        // Send feedback using LanguageManager
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("x", String.valueOf(block.getFloorX()));
        placeholders.put("y", String.valueOf(block.getFloorY()));
        placeholders.put("z", String.valueOf(block.getFloorZ()));
        
        // Add position index to placeholders
        placeholders.put("position", positionIndex == 0 ? "pos1" : "pos2");
        
        player.sendMessage(plugin.getLanguageManager().get("messages.wand.positionSet", placeholders));

        // Notify if both positions are set
        if (positions[0] != null && positions[1] != null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.wand.selectionComplete"));
        }

        // Start or update visualization
        updateVisualization(player);
    }

    private void saveToUndoHistory(String playerName, Position[] positions) {
        undoHistory.computeIfAbsent(playerName, k -> new ArrayList<>())
            .add(positions);
        
        // Maintain history size
        List<Position[]> history = undoHistory.get(playerName);
        while (history.size() > MAX_UNDO_HISTORY) {
            history.remove(0);
        }
    }

    public void undo(Player player) {
        String playerName = player.getName();
        List<Position[]> history = undoHistory.get(playerName);
        
        if (history == null || history.isEmpty()) {
            player.sendMessage(plugin.getLanguageManager().get("messages.wand.noActionsToUndo"));
            return;
        }

        Position[] lastPositions = history.remove(history.size() - 1);
        plugin.getPlayerPositions().put(playerName, lastPositions);
        player.sendMessage(plugin.getLanguageManager().get("messages.wand.undoSuccess"));
        updateVisualization(player);
    }

    public void updateVisualization(Player player) {
        String playerName = player.getName();
        Position[] positions = plugin.getPlayerPositions().get(playerName);
        
        // Cancel existing visualization task
        Integer taskId = visualizationTasks.remove(playerName);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }

        if (positions[0] == null || positions[1] == null) {
            return;
        }

        // Calculate visualization points
        List<Vector3> points = calculateVisualizationPoints(positions[0], positions[1]);
        visualizationPoints.put(playerName, points.iterator());

        // Start new visualization task
        taskId = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, () -> {
            Iterator<Vector3> iterator = visualizationPoints.get(playerName);
            if (iterator == null || !iterator.hasNext()) {
                visualizationPoints.put(playerName, points.iterator());
                iterator = visualizationPoints.get(playerName);
            }

            for (int i = 0; i < 10 && iterator.hasNext(); i++) {
                Vector3 point = iterator.next();
                player.getLevel().addParticle(new DustParticle(point, 255, 0, 0));
            }
        }, 5).getTaskId();

        visualizationTasks.put(playerName, taskId);
    }

    private List<Vector3> calculateVisualizationPoints(Position pos1, Position pos2) {
        List<Vector3> points = new ArrayList<>();
        int minX = Math.min(pos1.getFloorX(), pos2.getFloorX());
        int maxX = Math.max(pos1.getFloorX(), pos2.getFloorX());
        int minY = Math.min(pos1.getFloorY(), pos2.getFloorY());
        int maxY = Math.max(pos1.getFloorY(), pos2.getFloorY());
        int minZ = Math.min(pos1.getFloorZ(), pos2.getFloorZ());
        int maxZ = Math.max(pos1.getFloorZ(), pos2.getFloorZ());

        // Add edge points
        for (int x = minX; x <= maxX; x++) {
            points.add(new Vector3(x, minY, minZ));
            points.add(new Vector3(x, minY, maxZ));
            points.add(new Vector3(x, maxY, minZ));
            points.add(new Vector3(x, maxY, maxZ));
        }
        for (int y = minY; y <= maxY; y++) {
            points.add(new Vector3(minX, y, minZ));
            points.add(new Vector3(maxX, y, minZ));
            points.add(new Vector3(minX, y, maxZ));
            points.add(new Vector3(maxX, y, maxZ));
        }
        for (int z = minZ; z <= maxZ; z++) {
            points.add(new Vector3(minX, minY, z));
            points.add(new Vector3(maxX, minY, z));
            points.add(new Vector3(minX, maxY, z));
            points.add(new Vector3(maxX, maxY, z));
        }

        // Add color variation based on position index
        points.forEach(point -> {
            boolean isPos1Edge = point.getFloorX() == pos1.getFloorX() || 
                               point.getFloorY() == pos1.getFloorY() || 
                               point.getFloorZ() == pos1.getFloorZ();
            if (isPos1Edge) {
                point.setComponents(point.x, point.y + 0.01, point.z); // Slightly offset for different color
            }
        });

        Collections.shuffle(points); // Randomize to make visualization more interesting
        return points;
    }

    private void showTemporaryParticles(Player player, Position pos) {
        DustParticle particle = new DustParticle(pos, 0, 255, 0); // Green particles
        for (int i = 0; i < 5; i++) { // Show 5 particles
            double offset = 0.3;
            Vector3 particlePos = pos.add(
                Math.random() * offset - offset/2,
                Math.random() * offset,
                Math.random() * offset - offset/2
            );
            player.getLevel().addParticle((Particle) particle.setComponents(
                particlePos.x, particlePos.y, particlePos.z
            ));
        }
    }

    private boolean isHoldingWand(Player player) {
        Item item = player.getInventory().getItemInHand();
        return item.hasCustomName() && "§bArea Wand".equals(item.getCustomName());
    }

    private boolean isOnCooldown(Player player) {
        long lastAction = lastActionTime.getOrDefault(player.getName(), 0L);
        return System.currentTimeMillis() - lastAction < COOLDOWN_MS;
    }

    private void updateLastActionTime(Player player) {
        lastActionTime.put(player.getName(), System.currentTimeMillis());
    }

    private void cleanupPlayer(String playerName) {
        lastActionTime.remove(playerName);
        undoHistory.remove(playerName);
        visualizationPoints.remove(playerName);
        
        Integer taskId = visualizationTasks.remove(playerName);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
        
        plugin.getLogger().log(LogLevel.INFO, "Cleaned up resources for player: " + playerName);
    }

    // Call this when plugin is disabled
    public void cleanup() {
        visualizationTasks.forEach((player, taskId) -> 
            plugin.getServer().getScheduler().cancelTask(taskId));
        
        visualizationTasks.clear();
        visualizationPoints.clear();
        undoHistory.clear();
        lastActionTime.clear();
    }
}
