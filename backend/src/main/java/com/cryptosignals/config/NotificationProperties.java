package com.cryptosignals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Notification channels. Every channel is optional and independently switchable. */
@ConfigurationProperties(prefix = "crypto-signals.notifications")
public class NotificationProperties {

    /**
     * Deliver signals that were produced while replaying backfilled history. Off by default:
     * a first startup over a year of data can legitimately produce hundreds of historical
     * signals, and nobody wants that arriving as hundreds of messages.
     */
    private boolean notifyHistorical = false;

    /** Skip delivery for signals older than this, even live ones. Zero disables the check. */
    private Duration maxSignalAge = Duration.ofHours(1);

    /** How often the retry sweeper looks for persisted-but-undelivered signals. */
    private Duration retryInterval = Duration.ofMinutes(1);

    private Telegram telegram = new Telegram();

    public static class Telegram {
        private boolean enabled = false;
        private String botToken = "";
        private String chatId = "";
        private String apiBaseUrl = "https://api.telegram.org";
        private Duration timeout = Duration.ofSeconds(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getBotToken() { return botToken; }
        public void setBotToken(String v) { this.botToken = v; }
        public String getChatId() { return chatId; }
        public void setChatId(String v) { this.chatId = v; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String v) { this.apiBaseUrl = v; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration v) { this.timeout = v; }

        /** Usable only when switched on and actually configured. */
        public boolean isUsable() {
            return enabled && !botToken.isBlank() && !chatId.isBlank();
        }
    }

    public boolean isNotifyHistorical() { return notifyHistorical; }
    public void setNotifyHistorical(boolean v) { this.notifyHistorical = v; }
    public Duration getMaxSignalAge() { return maxSignalAge; }
    public void setMaxSignalAge(Duration v) { this.maxSignalAge = v; }
    public Duration getRetryInterval() { return retryInterval; }
    public void setRetryInterval(Duration v) { this.retryInterval = v; }
    public Telegram getTelegram() { return telegram; }
    public void setTelegram(Telegram v) { this.telegram = v; }
}
