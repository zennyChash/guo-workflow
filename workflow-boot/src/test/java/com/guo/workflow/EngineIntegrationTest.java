package com.guo.workflow;

import com.guo.workflow.mirror.ProcessActivityLog;
import com.guo.workflow.mirror.ProcessInstanceMirror;
import com.guo.workflow.mirror.ProcessTaskMirror;
import com.guo.workflow.service.WorkflowEngineService;
import com.guo.workflow.service.WorkflowQueryService;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
public class EngineIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;

    @Autowired
    private WorkflowEngineService workflowEngineService;
    @Autowired
    private WorkflowQueryService workflowQueryService;

    private final String processKey = "test_order_process";

    @BeforeEach
    public void setup() {
        // 1. 准备流程定义（通过纯代码生成一个简单的 XML）
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://flowable.org/test">
                    <process id="%s" name="Test Process" isExecutable="true">
                        <startEvent id="start" />
                        <sequenceFlow id="flow1" sourceRef="start" targetRef="task_approve" />
                        <userTask id="task_approve" name="审批">
                            <extensionElements>
                                <flowable:taskListener event="complete"
                                                       delegateExpression="${actionTaskListener}" />
                            </extensionElements>
                        </userTask>
                        <sequenceFlow id="flow2" sourceRef="task_approve" targetRef="end" />
                        <endEvent id="end" />
                    </process>
                </definitions>
                """.formatted(processKey);

        // 部署流程
        repositoryService.createDeployment().addString("test.bpmn20.xml", bpmnXml).deploy();

        // 2. 准备流程配置（指定取 orderNo 作为 businessKey）
        jdbcTemplate.update("DELETE FROM t_process_config");
        jdbcTemplate.update("INSERT INTO t_process_config (process_definition_key, business_key_var) VALUES (?, ?)",
                processKey, "orderNo");

        // 3. 准备节点动作配置（在 complete 时执行一段 Groovy 脚本，往变量里塞个值）
        jdbcTemplate.update("DELETE FROM t_node_action_config");
        String actionXml = """
                <actions>
                    <action event="complete" type="job" doType="script">
                        <script lang="groovy">
                            return "Engine_Executed"
                        </script>
                    </action>
                </actions>
                """;
        jdbcTemplate.update("INSERT INTO t_node_action_config (process_definition_key, activity_id, event, action_xml) VALUES (?, ?, ?, ?)",
                processKey, "task_approve", "complete", actionXml);
    }

    @Test
    public void testFullProcessFlow() {
        // 1. 调用包装接口发起流程
        Map<String, Object> variables = new HashMap<>();
        variables.put("orderNo", "ORD-999");
        variables.put("amount", 100);
        variables.put("nodeName_task_approve", "经理审批");

        String processInstanceId = workflowEngineService.startProcess(processKey, variables);
        Assertions.assertNotNull(processInstanceId);

        // 验证：businessKey 是否成功写入 Flowable 底层
        String businessKey = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult()
                .getBusinessKey();
        Assertions.assertEquals("ORD-999", businessKey);

        // 2. 获取当前的 UserTask
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        Assertions.assertNotNull(task);
        Assertions.assertEquals("task_approve", task.getTaskDefinitionKey());

        // 3. 提交任务（这一步会触发 ActionTaskListener）
        taskService.complete(task.getId());

        // 4. 验证动作执行后任务已结束
        Task finishedTask = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        Assertions.assertNull(finishedTask, "任务应当已被删除，流程已流转");

        ProcessInstanceMirror mirror = workflowQueryService
                .findProcess(null, processInstanceId)
                .orElseThrow();
        Assertions.assertEquals("COMPLETED", mirror.processStatus());
        Assertions.assertEquals("ORD-999", mirror.businessKey());
        Assertions.assertEquals(0, mirror.activeTaskCount());
        Assertions.assertNotNull(mirror.endTime());

        List<ProcessTaskMirror> tasks = workflowQueryService
                .findTasksByBusinessKey(null, "ORD-999");
        Assertions.assertEquals(1, tasks.size());
        Assertions.assertEquals("COMPLETED", tasks.get(0).taskStatus());
        Assertions.assertEquals("task_approve", tasks.get(0).activityId());
        Assertions.assertEquals("经理审批", tasks.get(0).nodeName());

        List<ProcessActivityLog> activity = workflowQueryService
                .findActivity(null, processInstanceId);
        Assertions.assertTrue(activity.stream()
                .anyMatch(log -> "START".equals(log.eventType())));
        Assertions.assertTrue(activity.stream()
                .anyMatch(log -> "COMPLETE".equals(log.eventType())
                        && "TASK".equals(log.eventScope())));
        Assertions.assertTrue(activity.stream()
                .anyMatch(log -> "END".equals(log.eventType())));
    }

    @Test
    public void mirrorsParallelTasksWithoutReadingFlowableTables() {
        String processKey = "parallel_mirror_process";
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="http://flowable.org/test">
                    <process id="%s" name="并行审批" isExecutable="true">
                        <startEvent id="start" />
                        <sequenceFlow sourceRef="start" targetRef="fork" />
                        <parallelGateway id="fork" />
                        <sequenceFlow sourceRef="fork" targetRef="task_a" />
                        <sequenceFlow sourceRef="fork" targetRef="task_b" />
                        <userTask id="task_a" name="部门审批" />
                        <userTask id="task_b" name="财务审批" />
                        <sequenceFlow sourceRef="task_a" targetRef="join" />
                        <sequenceFlow sourceRef="task_b" targetRef="join" />
                        <parallelGateway id="join" />
                        <sequenceFlow sourceRef="join" targetRef="end" />
                        <endEvent id="end" />
                    </process>
                </definitions>
                """.formatted(processKey);
        repositoryService.createDeployment()
                .addString("parallel-mirror.bpmn20.xml", bpmnXml)
                .deploy();

        String processInstanceId = runtimeService.startProcessInstanceByKey(
                processKey,
                "PARALLEL-100").getProcessInstanceId();

        List<ProcessTaskMirror> active = workflowQueryService.findActiveTasks(
                null,
                processInstanceId);
        Assertions.assertEquals(2, active.size());
        Assertions.assertEquals(
                2,
                workflowQueryService.findProcess(null, processInstanceId)
                        .orElseThrow()
                        .activeTaskCount());

        Task firstTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey("task_a")
                .singleResult();
        taskService.complete(firstTask.getId());

        Assertions.assertEquals(
                1,
                workflowQueryService.findActiveTasks(null, processInstanceId).size());

        Task secondTask = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey("task_b")
                .singleResult();
        taskService.complete(secondTask.getId());

        Assertions.assertEquals(
                "COMPLETED",
                workflowQueryService.findProcess(null, processInstanceId)
                        .orElseThrow()
                        .processStatus());
    }
}
