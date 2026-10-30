package com.guo.workflow.datasource;

import com.guo.workflow.contract.exception.FlowableActionException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SpringBusinessDataSourceResolverTest {

    private SpringBusinessDataSourceResolver resolver;

    @AfterEach
    void closeManagedDataSources() {
        if (resolver != null) {
            resolver.destroy();
        }
    }

    @Test
    void hostDataSourceTakesPrecedenceOverEngineConfiguration() {
        DataSource hostDataSource = mock(DataSource.class);
        StaticApplicationContext context = contextWith(
                hostDataSource,
                mock(DataSource.class));
        WorkflowDataSourceProperties properties = autoCreateProperties(
                definition("jdbc:h2:mem:ignored"));
        resolver = new SpringBusinessDataSourceResolver(context, properties);

        assertSame(hostDataSource, resolver.resolve("biz_db"));
    }

    @Test
    void hostDataSourceWorksWhenAutoCreationIsDisabled() {
        DataSource hostDataSource = mock(DataSource.class);
        StaticApplicationContext context = contextWith(
                hostDataSource,
                mock(DataSource.class));
        WorkflowDataSourceProperties properties = new WorkflowDataSourceProperties();
        resolver = new SpringBusinessDataSourceResolver(context, properties);

        assertSame(hostDataSource, resolver.resolve("biz_db"));
    }

    @Test
    void createsConfiguredDataSourceWhenHostBeanIsMissing() throws Exception {
        StaticApplicationContext context = contextWithPrimaryDataSource();
        WorkflowDataSourceProperties properties = autoCreateProperties(
                definition("jdbc:h2:mem:resolver_fallback;DB_CLOSE_DELAY=-1"));
        resolver = new SpringBusinessDataSourceResolver(context, properties);

        DataSource dataSource = resolver.resolve("biz_db");

        assertTrue(dataSource instanceof HikariDataSource);
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(1));
        }
        HikariDataSource managedDataSource = (HikariDataSource) dataSource;
        resolver.destroy();
        resolver = null;
        assertTrue(managedDataSource.isClosed());
    }

    @Test
    void doesNotCreateConfiguredDataSourceWhenAutoCreationIsDisabled() {
        StaticApplicationContext context = contextWithPrimaryDataSource();
        WorkflowDataSourceProperties properties = new WorkflowDataSourceProperties();
        properties.setDefinitions(Map.of(
                "biz_db",
                definition("jdbc:h2:mem:disabled")));
        resolver = new SpringBusinessDataSourceResolver(context, properties);

        assertThrows(
                FlowableActionException.class,
                () -> resolver.resolve("biz_db"));
    }

    @Test
    void rejectsFlowablePrimaryDataSource() {
        StaticApplicationContext context = contextWithPrimaryDataSource();
        WorkflowDataSourceProperties properties = new WorkflowDataSourceProperties();
        resolver = new SpringBusinessDataSourceResolver(context, properties);

        assertThrows(
                FlowableActionException.class,
                () -> resolver.resolve("dataSource"));
    }

    @Test
    void rejectsMissingBusinessDataSource() {
        StaticApplicationContext context = contextWithPrimaryDataSource();
        WorkflowDataSourceProperties properties = new WorkflowDataSourceProperties();
        properties.setAutoCreate(true);
        resolver = new SpringBusinessDataSourceResolver(context, properties);

        assertThrows(
                FlowableActionException.class,
                () -> resolver.resolve("missing_db"));
    }

    private WorkflowDataSourceProperties autoCreateProperties(
            WorkflowDataSourceProperties.DataSourceDefinition definition) {
        WorkflowDataSourceProperties properties = new WorkflowDataSourceProperties();
        properties.setAutoCreate(true);
        properties.setDefinitions(Map.of("biz_db", definition));
        return properties;
    }

    private WorkflowDataSourceProperties.DataSourceDefinition definition(String url) {
        WorkflowDataSourceProperties.DataSourceDefinition definition =
                new WorkflowDataSourceProperties.DataSourceDefinition();
        definition.setUrl(url);
        definition.setUsername("sa");
        definition.setPassword("");
        definition.setDriverClassName("org.h2.Driver");
        definition.setMaximumPoolSize(2);
        definition.setMinimumIdle(0);
        return definition;
    }

    private StaticApplicationContext contextWith(
            DataSource businessDataSource,
            DataSource flowableDataSource) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("biz_db", businessDataSource);
        context.getBeanFactory().registerSingleton("dataSource", flowableDataSource);
        context.refresh();
        return context;
    }

    private StaticApplicationContext contextWithPrimaryDataSource() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", mock(DataSource.class));
        context.refresh();
        return context;
    }
}
