package com.ecommerce.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LLMConfig {

    @Bean
    public ChatClient ecommerceChatClient(ObjectProvider<ChatClient.Builder> builderProvider) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        return builder == null ? null : builder.build();
    }
}
