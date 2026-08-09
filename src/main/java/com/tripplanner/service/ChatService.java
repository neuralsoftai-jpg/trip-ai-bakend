package com.tripplanner.service;

import com.tripplanner.client.GeminiClient;
import com.tripplanner.dto.request.ChatRequest;
import com.tripplanner.dto.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final GeminiClient geminiClient;

    public ChatResponse chat(ChatRequest request) {
        log.info("Chat assistant request for destination: '{}', query: '{}'",
                request.getDestination(), request.getMessage());

        String reply = geminiClient.getChatResponse(
                request.getDestination(),
                request.getMessage()
        );

        return ChatResponse.builder()
                .reply(reply)
                .timestamp(Instant.now().toString())
                .build();
    }
}
