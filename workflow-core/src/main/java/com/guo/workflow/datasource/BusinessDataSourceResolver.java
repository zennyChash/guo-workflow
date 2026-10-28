package com.guo.workflow.datasource;

import javax.sql.DataSource;

/**
 * Resolves named business data sources without falling back to Flowable's primary database.
 */
public interface BusinessDataSourceResolver {

    DataSource resolve(String dataSourceName);
}
