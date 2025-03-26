# AdminAreaProtection Technical Implementation Plan

## Language Key Fixes

### Analysis of Missing Keys
1. **Issue**: There appear to be inconsistencies between message references in code and actual keys in language files
2. **Specific Cases**:
   - In `ProtectionListener`, some messages may be referencing incorrect keys
   - Form handlers are using hardcoded messages instead of language keys
   - Missing a key for position requirement message (seen in git diff)

### Implementation Steps
1. Search for all string literals containing "messages." to find direct references
2. Compare these with the language file structure
3. Add the following missing keys to en_US.yml:
   - `messages.protection.taming` for animal taming protection
   - `messages.protection.breeding` for animal breeding protection 
   - `messages.protection.projectile` for projectile restrictions
   - Appropriate messages for form validation errors

### Code Locations for Language Key Fixes
- `EntityListener.java` - Animal interaction messages
- `ProtectionListener.java` - Block protection messages
- All form handler classes - Replace hardcoded strings with language keys
- `WandListener.java` - Selection messages (particularly "positionsNeeded" key)

## Bug Fixes

### MobPlugin Integration
1. **Issue**: Monster targeting behavior may not be working properly with the new MobPlugin integration
2. **Solution**:
   - Check `EntityListener` monster target handling
   - Verify proper implementation of `MonsterTargetEvent`
   - Ensure `MonsterHandler.resetTarget()` correctly handles edge cases
   
### Permission Cache Invalidation
1. **Issue**: Permission cache may not be properly invalidated when area settings change
2. **Solution**:
   - Add cache invalidation calls in `Area.clearCaches()`
   - Ensure `PermissionChecker.invalidateCache()` is called when areas are modified
   - Add specific cache invalidation for group permissions

### Form Handling
1. **Issue**: Some form handlers may have issues with validation after recent changes
2. **Solution**:
   - Fix validation in `BasicSettingsHandler.validate()`
   - Address issues in `DeleteAreaHandler` related to confirmation
   - Ensure all form handlers properly handle null values

### Area Management
1. **Issue**: Issues with area overlap checking and boundary validation
2. **Solution**:
   - Fix validation logic in `AreaManager.checkAreaOverlap()`
   - Improve boundary checking in `ValidationUtils`
   - Add proper error handling for area creation edge cases

## Performance Optimizations

### Entity Handling
1. **Issue**: Performance bottlenecks in entity checks
2. **Solutions**:
   - Add spatial caching for entity position checks
   - Use `WeakHashMap` for entity class type caching
   - Implement early bailout in event handlers when not needed

### Permission Checking
1. **Issue**: Redundant permission checks causing performance issues
2. **Solutions**:
   - Optimize cache key generation in `PermissionChecker`
   - Add hierarchy-aware caching for permission inheritance
   - Reduce permission check frequency for common operations

### Area Queries
1. **Issue**: Area lookup performance issues with many areas
2. **Solutions**:
   - Implement spatial indexing for faster area lookup
   - Cache area-in-area relationship results
   - Add priority-based early bailout optimization

## Code Structure Improvements

### ConfigManager Move
1. **Issue**: ConfigManager was moved from `adminarea.config` to `adminarea.managers`
2. **Solution**:
   - Update all import statements referencing the old path
   - Remove deprecated ConfigManager class
   - Update documentation comments

### Error Handling
1. **Issue**: Inconsistent error handling and logging
2. **Solution**:
   - Standardize error handling in critical methods
   - Add performance monitoring around expensive operations
   - Improve debug logging for easier troubleshooting

### Code Cleanup
1. **Issue**: Redundant and deprecated code
2. **Solution**:
   - Remove commented-out code sections
   - Clean up unused variables and methods
   - Standardize naming conventions across classes

## Testing Strategy

### Unit Testing
1. Create tests for:
   - Permission checking logic
   - Area overlap detection
   - Configuration loading/saving
   - Language key resolution

### Integration Testing
1. Test complete workflows:
   - Area creation → modification → deletion
   - Permission checking with LuckPerms
   - Event handling for protection

### Performance Testing
1. Benchmark critical operations:
   - Area lookup performance with many areas
   - Permission checking with complex hierarchies
   - Event handling under load

## Specific Files to Modify

1. **Core Classes**:
   - `AdminAreaProtectionPlugin.java`
   - `PermissionChecker.java`
   - `AreaManager.java`
   - `ConfigManager.java`

2. **Event Handlers**:
   - `EntityListener.java`
   - `ProtectionListener.java`
   - `WandListener.java`

3. **Form Handlers**:
   - `BasicSettingsHandler.java`
   - `DeleteAreaHandler.java`
   - `CreateAreaHandler.java`

4. **Resource Files**:
   - `lang/en_US.yml`
   - `config.yml`

## Implementation Priorities

1. **Must Fix**
   - Missing language keys in en_US.yml
   - MonsterHandler integration bugs
   - Permission cache invalidation issues
   - Form validation bugs

2. **Should Fix**
   - Performance optimizations for permission checking
   - Area query performance improvements
   - Error handling standardization

3. **Nice to Have**
   - Code cleanup and documentation improvements
   - Additional unit tests
   - Performance benchmarking infrastructure