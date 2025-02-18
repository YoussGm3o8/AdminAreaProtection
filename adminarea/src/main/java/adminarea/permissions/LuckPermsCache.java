package adminarea.permissions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;

public class LuckPermsCache {
    private final LuckPerms luckPerms;
    private final Map<String, Integer> groupWeights;
    private final Map<String, Set<String>> inheritanceMap;
    
    public LuckPermsCache(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
        this.groupWeights = new ConcurrentHashMap<>();
        this.inheritanceMap = new ConcurrentHashMap<>();
        refreshCache();
    }

    public void refreshCache() {
        groupWeights.clear();
        inheritanceMap.clear();
        
        // Load all groups
        Collection<Group> groups = luckPerms.getGroupManager().getLoadedGroups();
        
        // Cache group weights
        for (Group group : groups) {
            groupWeights.put(group.getName(), group.getWeight().orElse(0));
            
            // Cache inheritance
            Set<String> inherited = new HashSet<>();
            group.getNodes(NodeType.INHERITANCE).forEach(node -> {
                InheritanceNode inheritanceNode = (InheritanceNode) node;
                inherited.add(inheritanceNode.getGroupName());
            });
            inheritanceMap.put(group.getName(), inherited);
        }
    }

    public int getGroupWeight(String groupName) {
        return groupWeights.getOrDefault(groupName, 0);
    }

    public List<String> getInheritanceChain(String groupName) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        buildInheritanceChain(groupName, chain, visited);
        
        // Sort by weight in descending order
        chain.sort((g1, g2) -> Integer.compare(
            groupWeights.getOrDefault(g2, 0),
            groupWeights.getOrDefault(g1, 0)
        ));
        
        return chain;
    }

    private void buildInheritanceChain(String groupName, List<String> chain, Set<String> visited) {
        if (!visited.add(groupName)) return;
        
        chain.add(groupName);
        Set<String> inherited = inheritanceMap.getOrDefault(groupName, Collections.emptySet());
        
        for (String parent : inherited) {
            buildInheritanceChain(parent, chain, visited);
        }
    }

    public boolean isGroupHigherThan(String group1, String group2) {
        int weight1 = getGroupWeight(group1);
        int weight2 = getGroupWeight(group2);
        return weight1 > weight2;
    }

    public void updateGroupWeight(String groupName, int weight) {
        groupWeights.put(groupName, weight);
    }

    public void invalidateGroup(String groupName) {
        groupWeights.remove(groupName);
    }

    public Set<String> getGroups() {
        return new HashSet<>(groupWeights.keySet());
    }

    public Map<String, List<String>> getGroupTracks() {
        Map<String, List<String>> tracks = new HashMap<>();
        luckPerms.getTrackManager().getLoadedTracks().forEach(track -> {
            tracks.put(track.getName(), track.getGroups());
        });
        return tracks;
    }

    public Set<String> getTrackGroups(String trackName) {
        var track = luckPerms.getTrackManager().getTrack(trackName);
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
}
