package com.guo.workflow.datasource;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for business data sources owned by the workflow engine.
 */
@ConfigurationProperties(prefix = "guo.workflow.datasources")
public class WorkflowDataSourceProperties {

    private boolean autoCreate;
    private Map<String, DataSourceDefinition> definitions = new LinkedHashMap<>();

    public boolean isAutoCreate() {
        return autoCreate;
    }

    public void setAutoCreate(boolean autoCreate) {
        this.autoCreate = autoCreate;
    }

    public Map<String, DataSourceDefinition> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(Map<String, DataSourceDefinition> definitions) {
        this.definitions = definitions == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(definitions);
    }

    /**
     * Connection pool definition for one logical data source name.
     */
    public static class DataSourceDefinition {

        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private long connectionTimeoutMs = 30_000L;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }
    }
}
