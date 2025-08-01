/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.application.service;

import com.alibaba.cloud.ai.application.config.rag.VectorStoreDelegate;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

@Service
public class SAARAGService {

	private final ChatClient client;

	private final VectorStoreDelegate vectorStoreDelegate;

	private String vectorStoreType;

	public SAARAGService(
			VectorStoreDelegate vectorStoreDelegate,
			SimpleLoggerAdvisor simpleLoggerAdvisor,
			MessageChatMemoryAdvisor messageChatMemoryAdvisor,
			@Qualifier("dashscopeChatModel") ChatModel chatModel,
			@Qualifier("systemPromptTemplate") PromptTemplate systemPromptTemplate
	) {
		this.vectorStoreType = System.getenv("VECTOR_STORE_TYPE");
		this.vectorStoreDelegate = vectorStoreDelegate;
		this.client = ChatClient.builder(chatModel)
				.defaultSystem(
						systemPromptTemplate.getTemplate()
				).defaultAdvisors(
						messageChatMemoryAdvisor,
						simpleLoggerAdvisor
				).build();
	}

	public Flux<String> ragChat(String chatId, String prompt) {

		return client.prompt()
				.user(prompt)
				.advisors(memoryAdvisor -> memoryAdvisor
						.param(ChatMemory.CONVERSATION_ID, chatId)
				).advisors(
						QuestionAnswerAdvisor
								.builder(vectorStoreDelegate.getVectorStore(vectorStoreType))
								.searchRequest(  // 这里实现了rag，寻找相似度最高的6条消息
										SearchRequest.builder()
												// TODO all documents retrieved from ADB are under 0.1
//												.similarityThreshold(0.6d)
												.topK(6)
												.build()
								)
								.build()
				).stream()
				.content();
	}

}
