package com.campusplug.api.messages;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationLongPollNotifier {

    private final ConcurrentHashMap<Long, Object> monitors = new ConcurrentHashMap<>();

    public void notifyNewMessage(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        Object monitor = monitors.computeIfAbsent(conversationId, ignored -> new Object());
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public void await(Long conversationId, long millis) throws InterruptedException {
        if (conversationId == null) {
            Thread.sleep(millis);
            return;
        }
        Object monitor = monitors.computeIfAbsent(conversationId, ignored -> new Object());
        synchronized (monitor) {
            monitor.wait(millis);
        }
    }
}
