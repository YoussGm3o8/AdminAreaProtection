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
            .maximumSize(200)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
            
        this.inheritedGroupsCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
            
        registerEventHandlers();
    }

    private void registerEventHandlers() {
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
    }
    
    public void cleanup() {
        eventSubscriptions.forEach(EventSubscription::close);
        eventSubscriptions.clear();
        invalidateAllCaches();
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

    public List<String> getGroups(String username) {
        return groupsCache.get(username, k -> {
            User user = luckPerms.getUserManager().getUser(k);
            if (user == null) return Collections.emptyList();
            
            return user.getNodes().stream()
                .filter(node -> node.getKey().startsWith("group."))
                .map(node -> node.getKey().substring(6))
                .collect(Collectors.toList());
        });
    }
    
    public Set<String> getInheritedGroups(String groupName) {
        Set<String> result = new HashSet<>();
        
        try {
            // Get all loaded groups
            luckPerms.getGroupManager().getLoadedGroups().forEach(group -> {
                // Check if this group inherits from the specified group
                boolean inherits = group.getNodes(NodeType.INHERITANCE).stream()
                    .anyMatch(node -> ((InheritanceNode) node).getGroupName().equals(groupName));
                
                if (inherits) {
                    result.add(group.getName());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().error("Error getting groups that inherit from " + groupName, e);
        }
        
        return result;
    }

    public boolean isGroupHigherThan(String group1, String group2) {
        int weight1 = getGroupWeight(group1);
        int weight2 = getGroupWeight(group2);
        return weight1 > weight2;
    }
    
    public int getGroupWeight(String groupName) {
        return weightCache.get(groupName, k -> {
            Group group = luckPerms.getGroupManager().getGroup(k);
            return group != null ? group.getWeight().orElse(0) : 0;
        });
    }

    public void updateGroupWeight(String groupName, int weight) {
        weightCache.put(groupName, weight);
    }

    public void invalidateGroup(String groupName) {
        if (groupName == null || groupName.isEmpty()) {
            return;
        }
        
        // Clear all related caches
        inheritedGroupsCache.invalidate(groupName);
        weightCache.invalidate(groupName);
        
        // Also invalidate area permission caches for this group through the PermissionOverrideManager
        try {
            // Find all areas that have permissions for this group
            List<String> affectedAreas = plugin.getPermissionOverrideManager().getAreasWithGroupPermissions(groupName);
            // Convert to Set to avoid type mismatch
            Set<String> affectedAreasSet = new HashSet<>(affectedAreas);
            
            // Invalidate the permission checker cache for these areas
            for (String areaName : affectedAreasSet) {
                plugin.getPermissionOverrideManager().invalidateGroupPermissions(areaName, groupName);
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Invalidated group cache for " + groupName + 
                           " affecting " + affectedAreasSet.size() + " areas");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error invalidating group cache for " + groupName, e);
        }
    }

    public Set<String> getGroups() {
        return luckPerms.getGroupManager().getLoadedGroups().stream()
            .map(Group::getName)
            .collect(Collectors.toSet());
    }

    /**
     * Gets all tracks and their groups
     * 
     * @return Map of track names to lists of group names
     */
    public Map<String, List<String>> getGroupTracks() {
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

    public Set<String> getTrackGroups(String trackName) {
        Track track = luckPerms.getTrackManager().getTrack(trackName);
        // Fix: Convert List to Set explicitly
        if (track != null) {
            return new HashSet<>(track.getGroups());
        }
        return Collections.emptySet();
    }

    public List<String> getDirectGroups(String groupName) {
        Group group = luckPerms.getGroupManager().getGroup(groupName);
        if (group == null) return Collections.emptyList();
        
        return group.getNodes(NodeType.INHERITANCE)
            .stream()
            .map(node -> ((InheritanceNode) node).getGroupName())
            .collect(Collectors.toList());
    }

    private void onUserDataRecalculate(UserDataRecalculateEvent event) {
        String username = event.getUser().getUsername();
        if (username != null) {
            invalidateUserCaches(username);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Invalidated LuckPerms cache for user " + username + " due to data recalculation");
            }
        }
    }

    private void onNodeMutate(NodeMutateEvent event) {
        if (event.getTarget() instanceof User) {
            String username = ((User) event.getTarget()).getUsername();
            if (username != null) {
                invalidateUserCaches(username);
                
                // Also invalidate permission cache for this user in all areas
                plugin.getPermissionOverrideManager().getPermissionChecker().invalidatePlayerCache(username);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Invalidated LuckPerms and permission caches for user " + 
                               username + " due to permission node change");
                }
            }
        } else if (event.getTarget() instanceof Group) {
            String groupName = ((Group) event.getTarget()).getName();
            invalidateGroup(groupName);
            
            // Get all users affected by this group change and invalidate their caches
            Set<String> affectedPlayers = getPlayersInGroup(groupName);
            for (String playerName : affectedPlayers) {
                invalidateUserCaches(playerName);
                plugin.getPermissionOverrideManager().getPermissionChecker().invalidatePlayerCache(playerName);
            }
            
            // Also invalidate all inherited groups as they might be affected
            Set<String> inheritedGroups = getInheritedGroups(groupName);
            for (String inheritedGroup : inheritedGroups) {
                invalidateGroup(inheritedGroup);
                
                // Also invalidate players in these inherited groups
                Set<String> inheritedGroupPlayers = getPlayersInGroup(inheritedGroup);
                for (String playerName : inheritedGroupPlayers) {
                    invalidateUserCaches(playerName);
                    plugin.getPermissionOverrideManager().getPermissionChecker().invalidatePlayerCache(playerName);
                }
            }
            
            // Also invalidate any tracks that contain this group
            Map<String, List<String>> groupTracks = getGroupTracks();
            for (Map.Entry<String, List<String>> entry : groupTracks.entrySet()) {
                if (entry.getValue().contains(groupName)) {
                    String trackName = entry.getKey();
                    invalidateTrackCache(trackName);
                    
                    // Invalidate players in this track
                    Set<String> trackPlayers = getPlayersInTrack(trackName);
                    for (String playerName : trackPlayers) {
                        invalidateUserCaches(playerName);
                        plugin.getPermissionOverrideManager().getPermissionChecker().invalidatePlayerCache(playerName);
                    }
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Invalidated LuckPerms cache for group " + groupName + 
                           " and affected users due to node change");
            }
        } else if (event.getTarget() instanceof Track) {
            // Handle track mutation
            String trackName = ((Track) event.getTarget()).getName();
            invalidateTrackCache(trackName);
            
            // Invalidate all players in this track
            Set<String> trackPlayers = getPlayersInTrack(trackName);
            for (String playerName : trackPlayers) {
                invalidateUserCaches(playerName);
                plugin.getPermissionOverrideManager().getPermissionChecker().invalidatePlayerCache(playerName);
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Invalidated LuckPerms cache for track " + trackName + 
                           " and affected users due to node change");
            }
        }
    }

    public void invalidateUserCaches(String username) {
        primaryGroupCache.invalidate(username);
        groupsCache.invalidate(username);
        tracksCache.invalidate(username);
    }

    public void invalidateAllCaches() {
        primaryGroupCache.invalidateAll();
        groupsCache.invalidateAll();
        tracksCache.invalidateAll();
        inheritedGroupsCache.invalidateAll();
        weightCache.invalidateAll();
        
        // Also trigger a permission cache invalidation
        plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated all LuckPerms caches and permission checker cache");
        }
    }
    
    /**
     * Refreshes all caches, force-loading all data
     */
    public void refreshCache() {
        invalidateAllCaches();
        
        // Pre-load common groups
        Set<String> groups = getGroups();
        for (String group : groups) {
            getGroupWeight(group);
            getInheritedGroups(group);
        }
        
        // Pre-load track data
        getGroupTracks();
        
        if (plugin.isDebugMode()) {
            plugin.debug("[LuckPermsCache] Cache refreshed, pre-loaded " + groups.size() + " groups");
        }
    }
    
    /**
     * Gets the inheritance chain for a group, ordered from least to most powerful
     *
     * @param groupName The name of the group
     * @return List of groups in inheritance order
     */
    public List<String> getInheritanceChain(String groupName) {
        List<String> chain = new ArrayList<>();
        buildInheritanceChain(groupName, chain, new HashSet<>());
        
        // Sort by weight (higher weights come last in the chain)
        chain.sort(Comparator.comparingInt(this::getGroupWeight));
        
        return chain;
    }
    
    private void buildInheritanceChain(String groupName, List<String> chain, Set<String> visited) {
        if (visited.contains(groupName)) return; // Prevent circular inheritance
        visited.add(groupName);
        
        if (!chain.contains(groupName)) {
            chain.add(groupName);
        }
        
        // Add parent groups first, then process their parents
        List<String> parents = getDirectGroups(groupName);
        for (String parent : parents) {
            if (!chain.contains(parent)) {
                chain.add(parent);
            }
            buildInheritanceChain(parent, chain, visited);
        }
    }
    
    /**
     * Gets all players that are in a specific group
     * 
     * @param groupName The group name
     * @return Set of player names in the group
     */
    public Set<String> getPlayersInGroup(String groupName) {
        Set<String> players = new HashSet<>();
        
        try {
            // First check online players for efficiency
            plugin.getServer().getOnlinePlayers().values().forEach(player -> {
                String playerName = player.getName();
                List<String> playerGroups = getGroups(playerName);
                
                if (playerGroups.contains(groupName)) {
                    players.add(playerName);
                }
            });
            
            // For a more comprehensive check, we could query LuckPerms directly
            // but this would be more expensive, so we'll stick with online players
            // for now since they're the ones that matter for active permission checks
        } catch (Exception e) {
            plugin.getLogger().error("Error getting players in group " + groupName, e);
        }
        
        return players;
    }
    
    /**
     * Gets all online players who are in any group of a specific track
     *
     * @param trackName The name of the track
     * @return Set of player names in the specified track
     */
    public Set<String> getPlayersInTrack(String trackName) {
        Set<String> players = new HashSet<>();
        
        try {
            Track track = luckPerms.getTrackManager().getTrack(trackName);
            if (track == null) {
                return players;
            }
            
            // Get all groups in this track
            List<String> trackGroups = track.getGroups();
            
            // For each group in the track, get all players
            for (String groupName : trackGroups) {
                players.addAll(getPlayersInGroup(groupName));
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error getting players in track " + trackName, e);
        }
        
        return players;
    }

    public void invalidateTrackCache(String trackName) {
        tracksCache.invalidateAll(); // Invalidate all track caches for simplicity
        
        try {
            // Find all areas that have permissions for this track
            // Fix: Convert List to Set to avoid type mismatch
            List<String> affectedAreasList = plugin.getPermissionOverrideManager().getAreasWithTrackPermissions(trackName);
            Set<String> affectedAreas = new HashSet<>(affectedAreasList);
            
            // Invalidate the permission checker cache for these areas
            for (String areaName : affectedAreas) {
                plugin.getPermissionOverrideManager().invalidateTrackPermissions(areaName, trackName);
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Invalidated track cache for " + trackName + 
                           " affecting " + affectedAreas.size() + " areas");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error invalidating track cache for " + trackName, e);
        }
    }
}
