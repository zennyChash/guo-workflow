package com.guo.workflow.service.impl;

import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.repository.ProcessConfigRepository;
import com.guo.workflow.service.StartProcessCommand;
import com.guo.workflow.service.WorkflowEngineService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class WorkflowEngineServiceImpl implements WorkflowEngineService {

    private final RuntimeService runtimeService;
    private final IdentityService identityService;
    private final ProcessConfigRepository configRepository;

    public WorkflowEngineServiceImpl(RuntimeService runtimeService,
                                     IdentityService identityService,
                                     ProcessConfigRepository configRepository) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
        this.configRepository = configRepository;
    }

    @Override
    public String startProcess(String processKey, Map<String, Object> variables) {
        return startProcess(new StartProcessCommand(
                processKey,
                null,
                null,
                variables));
    }

    @Override
    public String startProcess(StartProcessCommand command) {
        if (command == null) {
            throw new FlowableActionException("Start process command must not be null");
        }
        String processKey = requireText(
                command.processKey(),
                "流程定义 Key");
        if (command.variables() == null) {
            throw new FlowableActionException("流程变量不能为空");
        }
        String operatorId = normalizeOptional(command.operatorId());
        String tenantId = normalizeOptional(command.tenantId());

        // 1. 查配置，确定业务主键取哪个变量
        String businessKeyVar = configRepository.findBusinessKeyVar(processKey);
        if (businessKeyVar == null || businessKeyVar.isBlank()) {
            throw new FlowableActionException("流程 [" + processKey + "] 未配置 businessKeyVar，无法发起流程");
        }

        // 2. 从表单参数里提取 businessKey
        Map<String, Object> variables = new HashMap<>(command.variables());
        Object businessKeyObj = variables.get(businessKeyVar);
        if (businessKeyObj == null || businessKeyObj.toString().isBlank()) {
            throw new FlowableActionException("发起流程失败：表单中缺少指定的业务主键字段 [" + businessKeyVar + "]");
        }
        String businessKey = businessKeyObj.toString();

        // 3. 确保变量池里有 businessKey（方便后续动作中通过 #{businessKey} 取到）
        variables.put("businessKey", businessKey);
        if (operatorId != null) {
            variables.put("initiator", operatorId);
            variables.put("startUserId", operatorId);
        }
        if (tenantId != null) {
            variables.put("tenantId", tenantId);
        }

        // 4. 调用 Flowable 原生 API 发起流程
        try {
            if (operatorId != null) {
                identityService.setAuthenticatedUserId(operatorId);
            }
            ProcessInstance instance = tenantId == null
                    ? runtimeService.startProcessInstanceByKey(
                            processKey,
                            businessKey,
                            variables)
                    : runtimeService.startProcessInstanceByKeyAndTenantId(
                            processKey,
                            businessKey,
                            variables,
                            tenantId);
            return instance.getProcessInstanceId();
        } catch (Exception e) {
            throw new FlowableActionException("流程发起失败: " + e.getMessage(), e);
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new FlowableActionException(fieldName + "不能为空");
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
