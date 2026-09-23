package com.cryptosignals.notifications;

import com.cryptosignals.analysis.model.TradingSignal;
import com.cryptosignals.config.NotificationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Sends signals to a Telegram chat via the Bot API.
 *
 * <p>Never throws at the caller: a messaging outage must not stop analysis or lose a signal. A
 * failed send returns {@code false}, which leaves the signal queued in the database for the retry
 * sweeper to pick up (specification section 20).
 */
@Component
@Order(10)
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final NotificationProperties.Telegram config;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public TelegramNotifier(NotificationProperties properties, ObjectMapper mapper) {
        this.config = properties.getTelegram();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(config.getTimeout()).build();
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public boolean isEnabled() {
        return config.isUsable();
    }

    @Override
    public boolean send(TradingSignal signal, String formattedMessage) {
        if (!isEnabled()) {
            return false;
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("chat_id", config.getChatId());
            body.put("text", formattedMessage);
            body.put("disable_web_page_preview", true);

            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            config.getApiBaseUrl() + "/bot" + config.getBotToken() + "/sendMessage"))
                    .timeout(config.getTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            // The bot token never appears in a log line; only the status and Telegram's description.
            log.warn("Telegram rejected signal {}: HTTP {} {}", signal.id(), response.statusCode(),
                    describe(response.body()));
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            log.warn("Telegram delivery of signal {} failed: {}", signal.id(), ex.toString());
            return false;
        }
    }

    private String describe(String body) {
        try {
            return mapper.readTree(body).path("description").asText(body);
        } catch (Exception ex) {
            return body == null ? "" : body;
        }
    }
}
