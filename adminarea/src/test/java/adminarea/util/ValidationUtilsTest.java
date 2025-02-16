package adminarea.util;

import cn.nukkit.Player;
import cn.nukkit.level.Position;
import cn.nukkit.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

class ValidationUtilsTest {

    private Player mockPlayer;
    private Level mockLevel;

    @BeforeEach
    void setUp() {
        mockPlayer = mock(Player.class);
        mockLevel = mock(Level.class);
        when(mockLevel.getName()).thenReturn("world");
    }

    @Test
    void validateAreaName_ValidName_NoException() {
        assertDoesNotThrow(() -> ValidationUtils.validateAreaName("test-area_123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ab", "invalid@name", "too_long_name_that_exceeds_32_characters"})
    void validateAreaName_InvalidNames_ThrowsException(String invalidName) {
        assertThrows(IllegalArgumentException.class, 
            () -> ValidationUtils.validateAreaName(invalidName));
    }

    @Test
    void validateCoordinates_ValidRange_NoException() {
        assertDoesNotThrow(() -> ValidationUtils.validateCoordinates(0, 100, "X"));
    }

    @Test
    void validateCoordinates_InvalidRange_ThrowsException() {
        assertThrows(IllegalArgumentException.class, 
            () -> ValidationUtils.validateCoordinates(100, 0, "X"));
    }

    @Test
    void validatePositions_ValidPositions_NoException() {
        Position pos1 = new Position(0, 0, 0, mockLevel);
        Position pos2 = new Position(10, 10, 10, mockLevel);
        Position[] positions = new Position[]{pos1, pos2};
        
        assertDoesNotThrow(() -> ValidationUtils.validatePositions(positions));
    }

    @Test
    void validatePositions_DifferentWorlds_ThrowsException() {
        Level otherLevel = mock(Level.class);
        when(otherLevel.getName()).thenReturn("other_world");
        
        Position pos1 = new Position(0, 0, 0, mockLevel);
        Position pos2 = new Position(10, 10, 10, otherLevel);
        Position[] positions = new Position[]{pos1, pos2};
        
        assertThrows(IllegalArgumentException.class, 
            () -> ValidationUtils.validatePositions(positions));
    }

    @Test
    void validateString_ValidInput_ReturnsNormalizedString() {
        String result = ValidationUtils.validateString(" test-input ", 3, 20, 
            "^[a-zA-Z0-9-]+$", "Test Field");
        assertEquals("test-input", result);
    }

    @Test
    void validatePlayerState_ValidState_ReturnsTrue() {
        when(mockPlayer.isOnline()).thenReturn(true);
        when(mockPlayer.hasPermission("test.permission")).thenReturn(true);
        
        assertTrue(ValidationUtils.validatePlayerState(mockPlayer, "test.permission"));
    }

    @Test
    void validateCrossFields_ValidDependencies_NoException() {
        Map<String, String> fields = new HashMap<>();
        fields.put("start", "10");
        fields.put("end", "20");
        
        Map<String, String> rules = new HashMap<>();
        rules.put("end", "greater_than:start");
        
        assertDoesNotThrow(() -> ValidationUtils.validateCrossFields(fields, rules));
    }

    @Test
    void validateCrossFields_InvalidDependencies_ThrowsException() {
        Map<String, String> fields = new HashMap<>();
        fields.put("start", "20");
        fields.put("end", "10");
        
        Map<String, String> rules = new HashMap<>();
        rules.put("end", "greater_than:start");
        
        assertThrows(IllegalArgumentException.class, 
            () -> ValidationUtils.validateCrossFields(fields, rules));
    }

    @Test
    void sanitizeFormInput_ValidInput_ReturnsSanitized() {
        String input = " <script>alert('test')</script> Hello World \n";
        String expected = "Hello World";
        assertEquals(expected, ValidationUtils.sanitizeFormInput(input, 20));
    }
}
