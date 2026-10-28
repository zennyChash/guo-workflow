
package com.guo.workflow.service;

import java.util.Map;

public interface WorkflowEngineService {

    /**
     * 统一发起流程接口（不暴露 Flowable 原生 API）
     * @param processKey 流程定义 Key（BPMN 的 ID）
     * @param variables  业务表单数据（全量）
     * @return 流程实例 ID
     */
    String startProcess(String processKey, Map<String, Object> variables);
}
