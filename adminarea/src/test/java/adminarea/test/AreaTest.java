package adminarea.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import adminarea.area.Area;

import static org.junit.jupiter.api.Assertions.*;

class AreaTest {
    private Area testArea;

    @BeforeEach
    void setUp() {
        testArea = Area.builder()
            .name("TestArea")
            .world("world")
            .coordinates(0, 100, 0, 100, 0, 100)
            .priority(1)
            .build();
    }

    @Test
    @DisplayName("Test area creation with valid parameters")
    void testValidAreaCreation() {
        assertNotNull(testArea);
        assertEquals("TestArea", testArea.getName());
        assertEquals("world", testArea.getWorld());
        assertEquals(1, testArea.getPriority());
    }

    @ParameterizedTest
    @CsvSource({
        "50,50,50,true",
        "-1,50,50,false",
        "101,50,50,false",
        "50,-1,50,false",
        "50,101,50,false"
    })
    @DisplayName("Test point containment in area")
    void testIsInside(int x, int y, int z, boolean expected) {
        assertEquals(expected, testArea.isInside("world", x, y, z));
    }

    @Test
    @DisplayName("Test area permissions")
    void testAreaPermissions() {
        testArea.setGroupPermission("default", "build", false);
        assertFalse(testArea.getGroupPermission("default", "build"));
        assertTrue(testArea.getGroupPermission("default", "interact")); // Default true
    }
}
