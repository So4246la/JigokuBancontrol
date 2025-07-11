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
    private Toml config;

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        loadConfig();
    }

    private void loadConfig() {
        Path configFile = dataDirectory.resolve("config.toml");
        if (!Files.exists(configFile)) {
            logger.info("config.tomlが見つかりません。デフォルト設定で起動します。");
            try (InputStream in = getClass().getResourceAsStream("/config.toml")) {
                if (in != null) {
                    Files.copy(in, configFile);
                    logger.info("デフォルトのconfig.tomlを生成しました。");
                } else {
                    logger.warn("デフォルトのconfig.tomlリソースが見つかりません。");
                }
            } catch (IOException e) {
                logger.error("デフォルトのconfig.tomlのコピーに失敗しました。", e);
            }
        }

        try {
            config = new Toml().read(configFile.toFile());
        } catch (Exception e) {
            logger.error("config.tomlの読み込みに失敗しました。デフォルト値を使用します。", e);
            config = new Toml(); // 空のTomlオブジェクト
        }
    }

    public String getString(String key, String defaultValue) {
        return config.getString(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        Long value = config.getLong(key);
        return value != null ? value.intValue() : defaultValue;
    }

    public Toml getTable(String key) {
        return config.getTable(key);
    }
}
