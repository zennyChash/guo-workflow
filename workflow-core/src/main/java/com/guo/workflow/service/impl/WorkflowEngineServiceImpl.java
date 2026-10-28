package com.guo.workflow.service.impl;

import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.repository.ProcessConfigRepository;
import com.guo.workflow.service.WorkflowEngineService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WorkflowEngineServiceImpl implements WorkflowEngineService {

    private final RuntimeService runtimeService;
    private final ProcessConfigRepository configRepository;

    public WorkflowEngineServiceImpl(RuntimeService runtimeService,
                                     ProcessConfigRepository configRepository) {
        this.runtimeService = runtimeService;
        this.configRepository = configRepository;
    }

    @Override
    public String startProcess(String processKey, Map<String, Object> variables) {
        if (processKey == null || processKey.isBlank()) {
            throw new FlowableActionException("流程定义 Key 不能为空");
        }
        if (variables == null) {
            throw new FlowableActionException("流程变量不能为空");
        }

        // 1. 查配置，确定业务主键取哪个变量
        String businessKeyVar = configRepository.findBusinessKeyVar(processKey);
        if (businessKeyVar == null || businessKeyVar.isBlank()) {
            throw new FlowableActionException("流程 [" + processKey + "] 未配置 businessKeyVar，无法发起流程");
        }

        // 2. 从表单参数里提取 businessKey
        Object businessKeyObj = variables.get(businessKeyVar);
        if (businessKeyObj == null || businessKeyObj.toString().isBlank()) {
            throw new FlowableActionException("发起流程失败：表单中缺少指定的业务主键字段 [" + businessKeyVar + "]");
        }
        String businessKey = businessKeyObj.toString();

        // 3. 确保变量池里有 businessKey（方便后续动作中通过 #{businessKey} 取到）
        variables.put("businessKey", businessKey);

        // 4. 调用 Flowable 原生 API 发起流程
        try {
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                    processKey,
                    businessKey,
                    variables
            );
            return instance.getProcessInstanceId();
        } catch (Exception e) {
            throw new FlowableActionException("流程发起失败: " + e.getMessage(), e);
        }
    }
}
