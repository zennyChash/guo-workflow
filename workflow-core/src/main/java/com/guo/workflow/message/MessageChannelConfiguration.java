package com.guo.workflow.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers built-in channels only when their webhook is configured.
 */
@Configuration
@EnableConfigurationProperties(MessageChannelProperties.class)
public class MessageChannelConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WebhookClient workflowWebhookClient(
            @Value("${guo.workflow.message.http.connect-timeout-seconds:5}")
            int connectTimeoutSeconds,
            @Value("${guo.workflow.message.http.request-timeout-seconds:10}")
            int requestTimeoutSeconds) {
        return new JdkWebhookClient(
                connectTimeoutSeconds,
                requestTimeoutSeconds);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dingTalkMessageSender")
    @ConditionalOnProperty(
            prefix = "guo.workflow.message.channels.dingtalk",
            name = "enabled",
            havingValue = "true")
    public DingTalkMessageSender dingTalkMessageSender(
            MessageChannelProperties properties,
            WebhookClient webhookClient,
            ObjectMapper objectMapper) {
        MessageChannelProperties.DingTalk dingtalk = properties.getDingtalk();
        return new DingTalkMessageSender(
                dingtalk.getWebhook(),
                dingtalk.getSecret(),
                dingtalk.getRecipientType(),
                webhookClient,
                objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "weComMessageSender")
    @ConditionalOnProperty(
            prefix = "guo.workflow.message.channels.wecom",
            name = "enabled",
            havingValue = "true")
    public WeComMessageSender weComMessageSender(
            MessageChannelProperties properties,
            WebhookClient webhookClient,
            ObjectMapper objectMapper) {
        MessageChannelProperties.WeCom wecom = properties.getWecom();
        return new WeComMessageSender(
                wecom.getWebhook(),
                wecom.getRecipientType(),
                webhookClient,
                objectMapper);
    }
}
