package com.guo.workflow.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDataSourcePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(BusinessDataSourceConfiguration.class);

    @Test
    void bindsAutoCreationAndNamedDefinitions() {
        contextRunner
                .withPropertyValues(
                        "guo.workflow.datasources.auto-create=true",
                        "guo.workflow.datasources.definitions.biz_db.url=jdbc:h2:mem:properties",
                        "guo.workflow.datasources.definitions.biz_db.username=sa",
                        "guo.workflow.datasources.definitions.biz_db.driver-class-name=org.h2.Driver",
                        "guo.workflow.datasources.definitions.biz_db.maximum-pool-size=6")
                .run(context -> {
                    WorkflowDataSourceProperties properties =
                            context.getBean(WorkflowDataSourceProperties.class);

                    assertTrue(properties.isAutoCreate());
                    assertEquals(
                            "jdbc:h2:mem:properties",
                            properties.getDefinitions().get("biz_db").getUrl());
                    assertEquals(
                            "org.h2.Driver",
                            properties.getDefinitions().get("biz_db").getDriverClassName());
                    assertEquals(
                            6,
                            properties.getDefinitions().get("biz_db").getMaximumPoolSize());
                });
    }

    @Test
    void disablesAutoCreationByDefault() {
        contextRunner.run(context -> {
            WorkflowDataSourceProperties properties =
                    context.getBean(WorkflowDataSourceProperties.class);

            assertFalse(properties.isAutoCreate());
            assertTrue(properties.getDefinitions().isEmpty());
        });
    }
}
