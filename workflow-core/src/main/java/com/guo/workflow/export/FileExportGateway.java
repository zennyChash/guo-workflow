package com.guo.workflow.export;

/**
 * Boundary for delegating file export work to the export module.
 *
 * <p>The export module owns task-definition resolution, data retrieval, format
 * handling and template decisions.</p>
 */
public interface FileExportGateway {

    /**
     * Starts or resumes an export and returns the ticket used to retrieve its file.
     */
    String export(FileExportRequest request) throws Exception;
}
