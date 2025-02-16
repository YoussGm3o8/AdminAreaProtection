package adminarea.test;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.managers.DatabaseManager;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

class DatabaseManagerTest {
    private DatabaseManager dbManager;
    private Area testArea;

    @BeforeEach
    void setUp() {
        // Mock the plugin and its data folder
        AdminAreaProtectionPlugin mockPlugin = mock(AdminAreaProtectionPlugin.class);
        File testDataFolder = new File("test-data");
        when(mockPlugin.getDataFolder()).thenReturn(testDataFolder);

        // Use unique in-memory database per test
        String uniqueDbPath = String.format("file:%s?mode=memory&cache=shared", UUID.randomUUID());
        dbManager = new DatabaseManager(mockPlugin);
        dbManager.init();

        testArea = Area.builder()
            .name("TestArea")
            .world("world")
            .coordinates(0, 100, 0, 100, 0, 100)
            .priority(1)
            .build();
    }

    @Test
    @DisplayName("Test area persistence")
    void testAreaPersistence() {
        // Save area
        dbManager.saveArea(testArea);

        // Load and verify
        Area loadedArea = dbManager.getArea("TestArea");
        assertNotNull(loadedArea);
        assertEquals(testArea.getName(), loadedArea.getName());
        assertEquals(testArea.getPriority(), loadedArea.getPriority());
    }

    @Test
    @DisplayName("Test area deletion")
    void testAreaDeletion() {
        dbManager.saveArea(testArea);
        dbManager.deleteArea(testArea.getName());
        assertNull(dbManager.getArea(testArea.getName()));
    }

    @Test
    @DisplayName("Test loading all areas")
    void testLoadAllAreas() {
        dbManager.saveArea(testArea);
        Area area2 = Area.builder()
            .name("TestArea2")
            .world("world")
            .coordinates(200, 300, 0, 100, 0, 100)
            .priority(2)
            .build();
        dbManager.saveArea(area2);

        List<Area> areas = dbManager.loadAreas();
        assertEquals(2, areas.size());
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }
}
