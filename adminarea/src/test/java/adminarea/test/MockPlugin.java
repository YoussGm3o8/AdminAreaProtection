package adminarea.test;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.Server;
import cn.nukkit.plugin.PluginLoader;
import cn.nukkit.plugin.PluginLogger;
import cn.nukkit.utils.Config;

import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class MockPlugin extends AdminAreaProtectionPlugin {
    private final File mockDataFolder;
    private final PluginLogger mockLogger;
    private final Config mockConfig;
    private final Server mockServer;
    private final PluginLoader mockPluginLoader;
    private File dataFolder;
    
        public MockPlugin() {
            try {
                // Create temporary directory that will be deleted on JVM exit
                this.mockDataFolder = Files.createTempDirectory("admin-area-test").toFile();
                this.mockDataFolder.deleteOnExit();
                this.dataFolder = this.mockDataFolder;  // Set the protected field from PluginBase
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test data folder", e);
        }
        
        this.mockServer = mock(Server.class);
        this.mockPluginLoader = mock(PluginLoader.class);
        this.mockLogger = new PluginLogger(this);
        this.mockConfig = new Config();
    }

    @Override
    public PluginLogger getLogger() {
        return mockLogger;
    }

    @Override 
    public Config getConfig() {
        return mockConfig;
    }

    @Override
    public Server getServer() {
        return mockServer;
    }

    @Override
    public PluginLoader getPluginLoader() {
        return mockPluginLoader;
    }

    /**
     * Clean up test resources
     */
    public void cleanup() {
        try {
            org.apache.commons.io.FileUtils.deleteDirectory(mockDataFolder);
        } catch (IOException e) {
            mockLogger.warning("Failed to cleanup test directory: " + e.getMessage());
        }
    }
}
