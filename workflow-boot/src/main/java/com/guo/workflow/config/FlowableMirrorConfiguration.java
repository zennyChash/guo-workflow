package com.guo.workflow.config;

import com.guo.workflow.approval.ApprovalNodeListener;
import com.guo.workflow.mirror.FlowableProcessMirrorListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers process mirror capture at the Flowable engine level.
 */
@Configuration(proxyBeanMethods = false)
public class FlowableMirrorConfiguration {

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration>
            processMirrorEngineConfigurer(
                    FlowableProcessMirrorListener mirrorListener,
                    ApprovalNodeListener approvalNodeListener) {
        return configuration -> {
            List<FlowableEventListener> listeners = configuration.getEventListeners() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(configuration.getEventListeners());
            if (!listeners.contains(mirrorListener)) {
                listeners.add(mirrorListener);
            }
            if (!listeners.contains(approvalNodeListener)) {
                listeners.add(approvalNodeListener);
            }
            configuration.setEventListeners(listeners);
        };
    }
}
