package com.guo.workflow.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.contract.InstructionMsg;
import com.guo.workflow.dispatcher.BusinessActionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncTaskConsumerTest {

    @Test
    void fallsBackToClobWhenStringReadFails() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BusinessActionRegistry registry = mock(BusinessActionRegistry.class);
        ResultSet resultSet = mock(ResultSet.class);
        Clob clob = mock(Clob.class);
        String payloadJson = "{\"taskId\":\"TASK-1\"}";

        when(resultSet.getString("payload_json"))
                .thenThrow(new SQLException("CLOB requires explicit access"));
        when(resultSet.getClob("payload_json")).thenReturn(clob);
        when(clob.length()).thenReturn((long) payloadJson.length());
        when(clob.getSubString(1, payloadJson.length())).thenReturn(payloadJson);

        AsyncTaskConsumer consumer = new AsyncTaskConsumer(
                jdbcTemplate,
                new ObjectMapper(),
                registry,
                new SyncTaskExecutor(),
                10,
                2,
                300,
                0,
                600);

        InstructionMsg message = consumer.readPayloadJson(resultSet);
        assertEquals("TASK-1", message.taskId());
        verify(clob).free();
    }
}
