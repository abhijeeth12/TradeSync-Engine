package com.tradesync.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads application.properties from the classpath.
 * Singleton accessed via AppConfig.get().
 */
public class AppConfig {

    private static AppConfig instance;
    private final Properties props = new Properties();

    private AppConfig() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is == null) throw new RuntimeException("application.properties not found on classpath");
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }
    }

    public static synchronized AppConfig get() {
        if (instance == null) instance = new AppConfig();
        return instance;
    }

    public String getString(String key) {
        String val = props.getProperty(key);
        if (val == null) throw new RuntimeException("Missing config key: " + key);
        return val.trim();
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    public long getLong(String key) {
        return Long.parseLong(getString(key));
    }

    /** Returns value or defaultValue if key is absent. */
    public String getString(String key, String defaultValue) {
        return props.getProperty(key, defaultValue).trim();
    }

    public int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        return val == null ? defaultValue : Integer.parseInt(val.trim());
    }
}
