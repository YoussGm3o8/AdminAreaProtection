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
    
    private final Set<EventSubscription> eventSubscriptions = new HashSet<>();
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(2);
    
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
        return inheritedGroupsCache.get(groupName, k -> {
            Set<String> inherited = new HashSet<>();
            collectInheritedGroups(k, inherited, new HashSet<>());
            return inherited;
        });
    }
    
    private void collectInheritedGroups(String groupName, Set<String> inherited, Set<String> visited) {
        if (visited.contains(groupName)) return; // Prevent circular inheritance
        visited.add(groupName);
        
        Group group = luckPerms.getGroupManager().getGroup(groupName);
        if (group == null) return;
        
        // Get direct inheritance nodes
        group.getNodes(NodeType.INHERITANCE).forEach(node -> {
            String parentName = ((InheritanceNode) node).getGroupName();
            inherited.add(parentName);
            collectInheritedGroups(parentName, inherited, visited);
        });
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
        weightCache.invalidate(groupName);
        inheritedGroupsCache.invalidate(groupName);
        
        // Also invalidate any groups that inherit from this one
        luckPerms.getGroupManager().getLoadedGroups().forEach(group -> {
            if (group.getNodes(NodeType.INHERITANCE).stream()
                    .anyMatch(node -> ((InheritanceNode) node).getGroupName().equals(groupName))) {
                inheritedGroupsCache.invalidate(group.getName());
            }
        });
    }

    public Set<String> getGroups() {
        return luckPerms.getGroupManager().getLoadedGroups().stream()
            .map(Group::getName)
            .collect(Collectors.toSet());
    }

    public Map<String, List<String>> getGroupTracks() {
        return luckPerms.getTrackManager().getLoadedTracks().stream()
            .collect(Collectors.toMap(
                Track::getName,
                track -> new ArrayList<>(track.getGroups())
            ));
    }

    public Set<String> getTrackGroups(String trackName) {
        Track track = luckPerms.getTrackManager().getTrack(trackName);
        return track != null ? new HashSet<>(track.getGroups()) : Collections.emptySet();
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
                plugin.getOverrideManager().getPermissionChecker().invalidatePlayerCache(username);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Invalidated LuckPerms and permission caches for user " + 
                               username + " due to permission node change");
                }
            }
        } else if (event.getTarget() instanceof Group) {
            String groupName = ((Group) event.getTarget()).getName();
            invalidateGroup(groupName);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Invalidated LuckPerms cache for group " + groupName + " due to node change");
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
        weightCache.invalidateAll();
        inheritedGroupsCache.invalidateAll();
    }

    private void logDebug(String message) {
        if (plugin.isDebugMode()) {
            plugin.debug("[LuckPermsCache] " + message);
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
     * Gets all online players who are in a specific group
     *
     * @param groupName The name of the group
     * @return Set of player names in the specified group
     */
    public Set<String> getPlayersInGroup(String groupName) {
        Set<String> players = new HashSet<>();
        
        // Get all online players from the plugin
        plugin.getServer().getOnlinePlayers().values().forEach(player -> {
            String playerName = player.getName();
            // Check if player is in this group or inherits from it
            List<String> playerGroups = getGroups(playerName);
            if (playerGroups.contains(groupName) || 
                getInheritedGroups(getPrimaryGroup(playerName)).contains(groupName)) {
                players.add(playerName);
            }
        });
        
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
        Set<String> trackGroups = getTrackGroups(trackName);
        
        if (trackGroups.isEmpty()) {
            return players;
        }
        
        // Get all online players from the plugin
        plugin.getServer().getOnlinePlayers().values().forEach(player -> {
            String playerName = player.getName();
            // Check if player is in any group in this track
            List<String> playerGroups = getGroups(playerName);
            
            for (String group : playerGroups) {
                if (trackGroups.contains(group)) {
                    players.add(playerName);
                    break;
                }
            }
        });
        
        return players;
    }
}
