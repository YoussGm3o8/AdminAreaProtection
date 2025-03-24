package adminarea.permissions;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import adminarea.AdminAreaProtectionPlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.track.Track;

/**
 * Caches LuckPerms data and manages permission inheritance
 */
public class LuckPermsCache {
    private final AdminAreaProtectionPlugin plugin;
    private final LuckPerms luckPerms;
    
    // Caches with shorter expiry for more frequent updates
    private final Cache<String, String> primaryGroupCache;
    private final Cache<String, List<String>> groupsCache;
    private final Cache<String, List<String>> tracksCache;
    private final Cache<String, Integer> weightCache;
    private final Cache<String, Set<String>> inheritedGroupsCache;
    
    // Fix: Parameterize EventSubscription to avoid raw type warning
    private final Set<EventSubscription<?>> eventSubscriptions = new HashSet<>();
    private static final long CACHE_DURATION = 60000;
    
    public LuckPermsCache(AdminAreaProtectionPlugin plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        
        // Configure caches with appropriate sizes and expiry
        this.primaryGroupCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
            
        this.groupsCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
            
        this.tracksCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
            
        this.weightCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
            
        this.inheritedGroupsCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
            
        registerEventHandlers();
        
        refreshCache();
    }
    
    /**
     * Register event listeners for LuckPerms data changes
     */
    private void registerEventHandlers() {
        try {
            // User data recalculation events
            eventSubscriptions.add(
                luckPerms.getEventBus().subscribe(UserDataRecalculateEvent.class, this::onUserDataRecalculate)
            );
            
            // Node mutation events
            eventSubscriptions.add(
                luckPerms.getEventBus().subscribe(NodeMutateEvent.class, this::onNodeMutate)
            );
            
            // We'll use the node mutation events to handle track and group changes
            // This is already covered by the NodeMutateEvent handler
            if (plugin.isDebugMode()) {
                plugin.debug("Registered LuckPerms event handlers for permission cache invalidation");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register LuckPerms event handlers: " + e.getMessage());
        }
    }
    
    public void cleanup() {
        eventSubscriptions.forEach(EventSubscription::close);
        eventSubscriptions.clear();
        primaryGroupCache.invalidateAll();
        groupsCache.invalidateAll();
        tracksCache.invalidateAll();
        weightCache.invalidateAll();
        inheritedGroupsCache.invalidateAll();
    }

    public String getPrimaryGroup(String username) {
        return primaryGroupCache.get(username, k -> {
            User user = luckPerms.getUserManager().getUser(k);
            if (user == null) {
                return "default"; // Fallback to default group
            }
            
            return user.getPrimaryGroup();
        });
    }

    /**
     * Get all groups a user belongs to
     */
    public List<String> getGroups(String username) {
        return groupsCache.get(username, k -> {
            User user = luckPerms.getUserManager().getUser(k);
            if (user == null) return Collections.emptyList();
            
            return user.getInheritedGroups(user.getQueryOptions())
                .stream()
                .map(Group::getName)
                .collect(Collectors.toList());
        });
    }
    
    /**
     * Get all groups that inherit from the specified group
     */
    public List<String> getInheritingGroups(String groupName) {
        Set<String> result = new HashSet<>();
        try {
            // Get all loaded groups
            luckPerms.getGroupManager().getLoadedGroups().forEach(group -> {
                // Check if this group inherits from the specified group
                boolean inherits = group.getNodes(NodeType.INHERITANCE).stream()
                    .map(InheritanceNode.class::cast)
                    .anyMatch(node -> node.getGroupName().equalsIgnoreCase(groupName));
                    
                if (inherits) {
                    result.add(group.getName());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().error("Error getting groups that inherit from " + groupName, e);
        }
        return new ArrayList<>(result);
    }
    
    /**
     * Get the weight of a group
     */
    public int getGroupWeight(String groupName) {
        return weightCache.get(groupName, k -> {
            Group group = luckPerms.getGroupManager().getGroup(k);
            return group != null ? group.getWeight().orElse(0) : 0;
        });
    }
    
    /**
     * Clear a specific user's cached data
     */
    public void clearUserCache(String username) {
        primaryGroupCache.invalidate(username);
        groupsCache.invalidate(username);
    }

    /**
     * Get all groups in LuckPerms
     */
    public Set<String> getGroups() {
        return luckPerms.getGroupManager().getLoadedGroups().stream()
            .map(Group::getName)
            .collect(Collectors.toSet());
    }
    
    /**
     * Get all tracks and their groups
     */
    public Map<String, List<String>> getAllTracks() {
        Map<String, List<String>> result = new HashMap<>();
        try {
            // Get all tracks from LuckPerms
            luckPerms.getTrackManager().getLoadedTracks().forEach(track -> {
                result.put(track.getName(), track.getGroups());
            });
        } catch (Exception e) {
            plugin.getLogger().error("Error getting group tracks", e);
        }
        return result;
    }

    /**
     * Get all groups in a track
     */
    public Set<String> getTrackGroups(String trackName) {
        Track track = luckPerms.getTrackManager().getTrack(trackName);
        // Fix: Convert List to Set explicitly
        if (track != null) {
            return new HashSet<>(track.getGroups());
        }
        return Collections.emptySet();
    }

    /**
     * Get the inheritance chain for a group
     */
    public List<String> getInheritanceChain(String groupName) {
        return new ArrayList<>(getDirectGroups(groupName));
    }
    
    /**
     * Get all groups that this group directly inherits from
     */
    public List<String> getDirectGroups(String groupName) {
        Group group = luckPerms.getGroupManager().getGroup(groupName);
        if (group == null) return Collections.emptyList();
        
        return group.getNodes(NodeType.INHERITANCE).stream()
            .map(InheritanceNode.class::cast)
            .map(InheritanceNode::getGroupName)
            .collect(Collectors.toList());
    }
    
    /**
     * Refresh the entire cache when configuration changes
     */
    public void refreshCache() {
        // First clear the cache
        primaryGroupCache.invalidateAll();
        groupsCache.invalidateAll();
        tracksCache.invalidateAll();
        weightCache.invalidateAll();
        inheritedGroupsCache.invalidateAll();
        
        // Preload some common data
        try {
            // Cache all group weights
            luckPerms.getGroupManager().getLoadedGroups().forEach(group -> {
                weightCache.put(group.getName(), group.getWeight().orElse(0));
            });
            
            if (plugin.isDebugMode()) {
                plugin.debug("Refreshed LuckPerms cache with " + weightCache.estimatedSize() + " groups");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error preloading LuckPerms cache", e);
        }
    }
    
    /**
     * Get all players in a specific track
     */
    public Set<String> getPlayersInTrack(String trackName) {
        Set<String> players = new HashSet<>();
        try {
            // First check online players for efficiency
            plugin.getServer().getOnlinePlayers().values().forEach(player -> {
                String playerName = player.getName();
                List<String> playerGroups = getGroups(playerName);
                
                // Get the track groups
                Set<String> trackGroups = getTrackGroups(trackName);
                
                // Check if this player has any group in the track
                if (playerGroups.stream().anyMatch(trackGroups::contains)) {
                    players.add(playerName);
                }
            });
            
            // For a more comprehensive check, we could query LuckPerms directly
            // But this would be expensive and probably not needed for our use case
            
        } catch (Exception e) {
            plugin.getLogger().error("Error getting players in track", e);
        }
        
        return players;
    }
    
    /**
     * Get all players in a specific track or group
     */
    public Set<String> getPlayersInTrackOrGroup(String trackName, String groupName) {
        Set<String> players = new HashSet<>();
        
        try {
            Track track = luckPerms.getTrackManager().getTrack(trackName);
            if (track == null) {
                return players;
            }
            
            // Get groups in the track
            List<String> trackGroups = track.getGroups();
            
            // Get players with groups in the track
            plugin.getServer().getOnlinePlayers().values().forEach(player -> {
                String playerName = player.getName();
                List<String> playerGroups = getGroups(playerName);
                
                // Check if this player has the specific group or any track groups
                if (playerGroups.contains(groupName) || 
                    playerGroups.stream().anyMatch(trackGroups::contains)) {
                    players.add(playerName);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().error("Error getting players in track or group", e);
        }
        
        return players;
    }

    /**
     * Handle user data recalculation events
     */
    private void onUserDataRecalculate(UserDataRecalculateEvent event) {
        User user = event.getUser();
        String username = null;
        try {
            // Call getUsername() directly - if it returns null, that's fine
            username = user.getUsername();
            // Only proceed if we have a valid username
            if (username == null) {
                return;
            }
        } catch (Exception e) {
            // Handle case where username might not be available
            return;
        }
        
        clearUserCache(username);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Cleared user cache for " + username + " due to data recalculation");
        }
    }
    
    /**
     * Handle node mutation events
     */
    private void onNodeMutate(NodeMutateEvent event) {
        // Clear all caches for track and group weights when inheritance nodes change
        boolean hasInheritanceNode = false;
        try {
            hasInheritanceNode = event.getTarget() instanceof Group;
        } catch (Exception e) {
            // Ignore exceptions, play it safe and refresh
            hasInheritanceNode = true;
        }
        
        if (hasInheritanceNode) {
            refreshCache();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Refreshed permission caches due to inheritance node change");
            }
        }
    }
    
    /**
     * Clears all caches
     * Called when the plugin is disabled
     */
    public void clearCache() {
        primaryGroupCache.invalidateAll();
        groupsCache.invalidateAll();
        tracksCache.invalidateAll();
        weightCache.invalidateAll();
        inheritedGroupsCache.invalidateAll();
        
        // Unregister event handlers
        eventSubscriptions.forEach(EventSubscription::close);
        eventSubscriptions.clear();
    }
}
