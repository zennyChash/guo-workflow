package com.guo.workflow.datasource;

import com.guo.workflow.contract.exception.FlowableActionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SpringBusinessDataSourceResolverTest {

    @Test
    void resolvesNamedBusinessDataSource() {
        DataSource businessDataSource = mock(DataSource.class);
        StaticApplicationContext context = contextWith(
                businessDataSource,
                mock(DataSource.class));
        SpringBusinessDataSourceResolver resolver =
                new SpringBusinessDataSourceResolver(context);

        assertSame(businessDataSource, resolver.resolve("biz_db"));
    }

    @Test
    void rejectsFlowablePrimaryDataSource() {
        StaticApplicationContext context = contextWith(
                mock(DataSource.class),
                mock(DataSource.class));
        SpringBusinessDataSourceResolver resolver =
                new SpringBusinessDataSourceResolver(context);

        assertThrows(
                FlowableActionException.class,
                () -> resolver.resolve("dataSource"));
    }

    @Test
    void rejectsMissingBusinessDataSource() {
        StaticApplicationContext context = contextWith(
                mock(DataSource.class),
                mock(DataSource.class));
        SpringBusinessDataSourceResolver resolver =
                new SpringBusinessDataSourceResolver(context);

        assertThrows(
                FlowableActionException.class,
                () -> resolver.resolve("missing_db"));
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
}
