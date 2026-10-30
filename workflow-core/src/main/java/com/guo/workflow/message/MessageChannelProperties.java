package com.guo.workflow.message;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Credentials and transport settings for built-in webhook channels.
 */
@ConfigurationProperties(prefix = "guo.workflow.message.channels")
public class MessageChannelProperties {

    private final DingTalk dingtalk = new DingTalk();
    private final WeCom wecom = new WeCom();

    public DingTalk getDingtalk() {
        return dingtalk;
    }

    public WeCom getWecom() {
        return wecom;
    }

    public static class DingTalk {

        private boolean enabled;
        private String webhook;
        private String secret;
        private String recipientType = "user-id";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhook() {
            return webhook;
        }

        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getRecipientType() {
            return recipientType;
        }

        public void setRecipientType(String recipientType) {
            this.recipientType = recipientType == null
                    ? "user-id"
                    : recipientType.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static class WeCom {

        private boolean enabled;
        private String webhook;
        private String recipientType = "user-id";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhook() {
            return webhook;
        }

        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }

        public String getRecipientType() {
            return recipientType;
        }

        public void setRecipientType(String recipientType) {
            this.recipientType = recipientType == null
                    ? "user-id"
                    : recipientType.trim().toLowerCase(Locale.ROOT);
        }
    }
}
