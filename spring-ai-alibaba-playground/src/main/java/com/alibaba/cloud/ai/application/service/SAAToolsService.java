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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import com.alibaba.cloud.ai.application.entity.tools.ToolCallResp;
import com.alibaba.cloud.ai.application.tools.ToolsInit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

@Service
public class SAAToolsService {

	private static final Logger logger = LoggerFactory.getLogger(SAAToolsService.class);

	private final ChatClient chatClient;

	private final ToolCallingManager toolCallingManager;

	private final ToolsInit toolsInit;

	public SAAToolsService(
			ToolsInit toolsInit,
			ToolCallingManager toolCallingManager,
			SimpleLoggerAdvisor simpleLoggerAdvisor,
			MessageChatMemoryAdvisor messageChatMemoryAdvisor,
			@Qualifier("openAiChatModel") ChatModel chatModel
	) {

		this.toolsInit = toolsInit;
		this.toolCallingManager = toolCallingManager;

		this.chatClient = ChatClient.builder(chatModel)
				.defaultAdvisors(
						simpleLoggerAdvisor
//						messageChatMemoryAdvisor
				).build();
	}

	public ToolCallResp chat(String prompt) {

		// manual run tools flag
		ChatOptions chatOptions = ToolCallingChatOptions.builder()
				.toolCallbacks(toolsInit.getTools()) // 获取到两个百度的tool
				.internalToolExecutionEnabled(false)
				.build();
		Prompt userPrompt = new Prompt(prompt, chatOptions);
		// response为大模型组装函数以及入参后的结果
		ChatResponse response = chatClient.prompt(userPrompt)
				.call().chatResponse();

		logger.debug("ChatResponse: {}", response);
		assert response != null; // toolCalls为大模型需要调用的函数
		List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
		logger.debug("ToolCalls: {}", toolCalls);// responseByLLm一直是空，不知道为啥
		String responseByLLm = response.getResult().getOutput().getText();
		logger.debug("Response by LLM: {}", responseByLLm);

		// execute tools with no chat memory messages.
		var tcr = ToolCallResp.TCR();
		if (!toolCalls.isEmpty()) {
//tcr为组装的参数
			tcr = ToolCallResp.startExecute(
					responseByLLm,
					toolCalls.get(0).name(),
					toolCalls.get(0).arguments()
			);
			logger.debug("Start ToolCallResp: {}", tcr);
			ToolExecutionResult toolExecutionResult = null;

			try { // 在这里就获取到了，函数调用的结果
				toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(prompt, chatOptions), response);

				tcr.setToolEndTime(LocalDateTime.now());
			}
			catch (Exception e) {

				tcr.setStatus(ToolCallResp.ToolState.FAILURE);
				tcr.setErrorMessage(e.getMessage());
				tcr.setToolEndTime(LocalDateTime.now());
				tcr.setToolCostTime((long) (tcr.getToolEndTime().getNano() - tcr.getToolStartTime().getNano()));
				logger.error("Error ToolCallResp: {}, msg: {}", tcr, e.getMessage());
				// throw new RuntimeException("Tool execution failed, please check the logs for details.");
			}

			String llmCallResponse = "";
			if (Objects.nonNull(toolExecutionResult)) {
//				ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory()
//						.get(toolExecutionResult.conversationHistory().size() - 1);
//				llmCallResponse = toolResponseMessage.getResponses().get(0).responseData();
				// 这里我理解是把历史消息，以及函数调用之后的结果，回传给了大模型，大模型再组装之后返回给用户
				ChatResponse finalResponse = chatClient.prompt().messages(toolExecutionResult.conversationHistory()).call().chatResponse();
				llmCallResponse = finalResponse.getResult().getOutput().getText();
			}

			tcr.setStatus(ToolCallResp.ToolState.SUCCESS);
			tcr.setToolResult(llmCallResponse);
			tcr.setToolCostTime((long) (tcr.getToolEndTime().getNano() - tcr.getToolStartTime().getNano()));
			logger.debug("End ToolCallResp: {}", tcr);
		}
		else {
			logger.debug("ToolCalls is empty, no tool execution needed.");
			tcr.setToolResult(responseByLLm);
		}

		return tcr;
	}

}
