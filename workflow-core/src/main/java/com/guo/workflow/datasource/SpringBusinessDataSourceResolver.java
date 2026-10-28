package com.guo.workflow.datasource;

import com.guo.workflow.contract.exception.FlowableActionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves business data sources by Spring bean name.
 */
@Component
public class SpringBusinessDataSourceResolver implements BusinessDataSourceResolver {

    private static final String FLOWABLE_PRIMARY_DATA_SOURCE = "datasource";
    private static final String FLOWABLE_DATA_SOURCE_PREFIX = "flowable";

    private final ApplicationContext applicationContext;

    public SpringBusinessDataSourceResolver(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public DataSource resolve(String dataSourceName) {
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new FlowableActionException("SQL action must configure a dataSource");
        }

        String requestedName = dataSourceName.trim();
        if (isReservedDataSourceName(requestedName)) {
            throw new FlowableActionException(
                    "Flowable primary data source cannot be used by SQL actions: "
                            + requestedName);
        }

        Map<String, DataSource> dataSources =
                applicationContext.getBeansOfType(DataSource.class);
        DataSource dataSource = dataSources.get(requestedName);
        if (dataSource == null) {
            dataSource = dataSources.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(requestedName))
                    .filter(entry -> !isReservedDataSourceName(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (dataSource == null) {
            throw new FlowableActionException(
                    "Business data source not found: " + requestedName);
        }
        return dataSource;
    }

    private boolean isReservedDataSourceName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return FLOWABLE_PRIMARY_DATA_SOURCE.equals(normalized)
                || normalized.startsWith(FLOWABLE_DATA_SOURCE_PREFIX);
    }
}
