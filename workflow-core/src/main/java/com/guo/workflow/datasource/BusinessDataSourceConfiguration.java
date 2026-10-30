package com.guo.workflow.datasource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables workflow-owned business data source configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowDataSourceProperties.class)
public class BusinessDataSourceConfiguration {
}
