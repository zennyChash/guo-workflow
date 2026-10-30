package com.guo.workflow.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves host-managed business data sources first and engine-managed data
 * sources second.
 */
@Component
public class SpringBusinessDataSourceResolver
        implements BusinessDataSourceResolver, DisposableBean {

    private static final Logger log =
            LoggerFactory.getLogger(SpringBusinessDataSourceResolver.class);
    private static final String FLOWABLE_PRIMARY_DATA_SOURCE = "datasource";
    private static final String FLOWABLE_DATA_SOURCE_PREFIX = "flowable";

    private final ApplicationContext applicationContext;
    private final WorkflowDataSourceProperties properties;
    private final ConcurrentMap<String, HikariDataSource> managedDataSources =
            new ConcurrentHashMap<>();

    public SpringBusinessDataSourceResolver(
            ApplicationContext applicationContext,
            WorkflowDataSourceProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    @Override
    public DataSource resolve(String dataSourceName) {
        String requestedName = requireDataSourceName(dataSourceName);
        if (isReservedDataSourceName(requestedName)) {
            throw new FlowableActionException(
                    "Flowable primary data source cannot be used by SQL actions: "
                            + requestedName);
        }

        DataSource hostDataSource = findHostDataSource(requestedName);
        if (hostDataSource != null) {
            closeManagedDataSource(requestedName);
            return hostDataSource;
        }

        WorkflowDataSourceProperties.DataSourceDefinition definition =
                properties.getDefinitions().get(requestedName);
        if (!properties.isAutoCreate()) {
            throw new FlowableActionException(
                    "Business data source not found and engine auto-creation is disabled: "
                            + requestedName);
        }
        if (definition == null) {
            throw new FlowableActionException(
                    "Business data source definition not found: " + requestedName);
        }

        return managedDataSources.computeIfAbsent(
                requestedName,
                name -> createDataSource(name, definition));
    }

    private DataSource findHostDataSource(String requestedName) {
        Map<String, DataSource> dataSources =
                applicationContext.getBeansOfType(DataSource.class);
        DataSource dataSource = dataSources.get(requestedName);
        if (dataSource != null) {
            return dataSource;
        }

        var matches = dataSources.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(requestedName))
                    .filter(entry -> !isReservedDataSourceName(entry.getKey()))
                    .toList();
        if (matches.size() > 1) {
            throw new FlowableActionException(
                    "Multiple business data sources match name: " + requestedName);
        }
        return matches.isEmpty() ? null : matches.get(0).getValue();
    }

    private String requireDataSourceName(String dataSourceName) {
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new FlowableActionException("SQL action must configure a dataSource");
        }
        return dataSourceName.trim();
    }

    private HikariDataSource createDataSource(
            String name,
            WorkflowDataSourceProperties.DataSourceDefinition definition) {
        if (definition.getUrl() == null || definition.getUrl().isBlank()) {
            throw new FlowableActionException(
                    "Business data source URL is missing: " + name);
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("workflow-" + name.replaceAll("[^A-Za-z0-9_.-]", "_"));
        config.setJdbcUrl(definition.getUrl().trim());
        config.setUsername(definition.getUsername());
        config.setPassword(definition.getPassword());
        if (definition.getDriverClassName() != null
                && !definition.getDriverClassName().isBlank()) {
            config.setDriverClassName(definition.getDriverClassName().trim());
        }
        config.setMaximumPoolSize(definition.getMaximumPoolSize());
        config.setMinimumIdle(definition.getMinimumIdle());
        config.setConnectionTimeout(definition.getConnectionTimeoutMs());
        try {
            HikariDataSource dataSource = new HikariDataSource(config);
            log.info("Created engine-managed business data source: {}", name);
            return dataSource;
        } catch (RuntimeException exception) {
            throw new FlowableActionException(
                    "Failed to create business data source: " + name,
                    exception);
        }
    }

    private void closeManagedDataSource(String name) {
        HikariDataSource dataSource = managedDataSources.remove(name);
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    public void destroy() {
        managedDataSources.forEach((name, dataSource) -> {
            try {
                dataSource.close();
            } catch (RuntimeException exception) {
                log.warn("Failed to close business data source: {}", name, exception);
            }
        });
        managedDataSources.clear();
    }

    private boolean isReservedDataSourceName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return FLOWABLE_PRIMARY_DATA_SOURCE.equals(normalized)
                || normalized.startsWith(FLOWABLE_DATA_SOURCE_PREFIX);
    }
}
