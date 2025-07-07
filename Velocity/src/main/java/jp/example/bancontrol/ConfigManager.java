package jp.example.bancontrol;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {

    private final Path dataDirectory;
    private final Logger logger;
    private Toml toml;

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        loadConfig();
    }

    private void loadConfig() {
        try {
            Path configFile = dataDirectory.resolve("config.toml");
            if (!Files.exists(configFile)) {
                logger.info("Config file not found, creating a default one...");
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.toml")) {
                    if (in == null) {
                        logger.error("Default config.toml not found in JAR resources.");
                        this.toml = new Toml(); // 空のTomlでフォールバック
                        return;
                    }
                    Files.createDirectories(dataDirectory);
                    Files.copy(in, configFile);
                    logger.info("Default config.toml has been copied to {}.", configFile);
                }
            }
            this.toml = new Toml().read(configFile.toFile());
            logger.info("Configuration loaded successfully.");
        } catch (IOException e) {
            logger.error("Failed to load or create config.toml. Using default values.", e);
            this.toml = new Toml(); // エラー時も空のTomlでフォールバック
        }
    }

    public String getString(String key, String defaultValue) {
        return toml.getString(key, defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return toml.getLong(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return toml.getLong(key, (long) defaultValue).intValue();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return toml.getBoolean(key, defaultValue);
    }

    public Toml getTable(String key) {
        return toml.getTable(key);
    }
}
