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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.alibaba.cloud.ai.application.entity.mcp.McpServer;
import com.alibaba.cloud.ai.application.entity.tools.ToolCallResp;
import com.alibaba.cloud.ai.application.mcp.McpServerContainer;
import com.alibaba.cloud.ai.application.mcp.McpServerUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import static com.alibaba.cloud.ai.application.mcp.McpServerUtils.getMcpLibsAbsPath;

/**
 * @author brianxiadong
 *         Spring AI Alibaba MCP (Model, Chat, Prompt) Service
 */
@Service
public class SAAMcpService {

	private final ChatClient chatClient;

	private final ObjectMapper objectMapper;

	private final ToolCallbackProvider tools;

	private final ToolCallingManager toolCallingManager;

	private final McpStdioClientProperties mcpStdioClientProperties;

	private static final Logger logger = LoggerFactory.getLogger(SAAMcpService.class);

	public SAAMcpService(
			ObjectMapper objectMapper,
			ToolCallbackProvider tools,
			SimpleLoggerAdvisor simpleLoggerAdvisor,
			ToolCallingManager toolCallingManager,
			McpStdioClientProperties mcpStdioClientProperties,
			@Qualifier("openAiChatModel") ChatModel chatModel
	) throws IOException {

		this.objectMapper = objectMapper;
		this.mcpStdioClientProperties = mcpStdioClientProperties;

		// Initialize chat client with non-blocking configuration
		this.chatClient = ChatClient.builder(chatModel)
				.defaultAdvisors(
						simpleLoggerAdvisor
				).defaultToolCallbacks(tools)
				.build();
		this.tools = tools;
		this.toolCallingManager = toolCallingManager;

		McpServerUtils.initMcpServerContainer(tools); // 用这些工具初始化mcp server 的container
	}

	public ToolCallResp chat(String prompt) {

		// manual run tools flag  tools.getToolCallbacks()中有关于经纬度的两个工具
		ChatOptions chatOptions = ToolCallingChatOptions.builder()
				.toolCallbacks(tools.getToolCallbacks())
				.internalToolExecutionEnabled(false)
				.build();
//大模型的输出，这里输出了大模型需要调用的函数是哪个，以及入参
		ChatResponse response = chatClient.prompt(new Prompt(prompt, chatOptions))
				.call().chatResponse();

		logger.debug("ChatResponse: {}", response);
		assert response != null;
		List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
		logger.debug("ToolCalls: {}", toolCalls);
		String responseByLLm = response.getResult().getOutput().getText();
		logger.debug("Response by LLM: {}", responseByLLm);

		// execute tools with no chat memory messages.
		var tcr = ToolCallResp.TCR();
		if (!toolCalls.isEmpty()) {

			tcr = ToolCallResp.startExecute(
					responseByLLm,
					toolCalls.get(0).name(),
					toolCalls.get(0).arguments()
			);
			tcr.setToolParameters(toolCalls.get(0).arguments());
			logger.debug("Start ToolCallResp: {}", tcr);
			ToolExecutionResult toolExecutionResult = null;

			try {  // toolExecutionResult
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
				ChatResponse finalResponse = chatClient.prompt().messages(toolExecutionResult.conversationHistory())
						.call().chatResponse(); //  这里是真正的获取到了mcp的结果
				if (finalResponse != null) {
					llmCallResponse = finalResponse.getResult().getOutput().getText();
				}

				StringBuilder sb = new StringBuilder();
				toolExecutionResult.conversationHistory().stream()
						.filter(message -> message instanceof ToolResponseMessage)
						.forEach(message -> {
							ToolResponseMessage toolResponseMessage = (ToolResponseMessage) message;
							toolResponseMessage.getResponses().forEach(tooResponse -> {
								sb.append(tooResponse.responseData());
							});
						});
				tcr.setToolResponse(sb.toString());
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

	public ToolCallResp run(String id, Map<String, String> envs, String prompt) throws IOException {
		// 根据id获取对应的mcp server
		Optional<McpServer> runMcpServer = McpServerContainer.getServerById(id);
		if (runMcpServer.isEmpty()) {
			logger.error("McpServer not found, id: {}", id);
			return ToolCallResp.TCR();
		}

		String runMcpServerName = runMcpServer.get().getName();
		var mcpServerConfig = McpServerUtils.getMcpServerConfig();
		McpStdioClientProperties.Parameters parameters = new McpStdioClientProperties.Parameters(
				mcpServerConfig.getMcpServers().get(runMcpServerName).command(),
				mcpServerConfig.getMcpServers().get(runMcpServerName).args(),
				envs
		);

		if (parameters.command().startsWith("java")) {
			String oldMcpLibsPath = McpServerUtils.getLibsPath(parameters.args());
			String rewriteMcpLibsAbsPath = getMcpLibsAbsPath(McpServerUtils.getLibsPath(parameters.args()));
			// rewriteMcpLibsAbsPath = D:\ideaProject\spring-ai-alibaba-examples\spring-ai-alibaba-playground\mcp-libs\weather.jar
			parameters.args().remove(oldMcpLibsPath);
			parameters.args().add(rewriteMcpLibsAbsPath);
		}

		String mcpServerConfigJSON = objectMapper.writeValueAsString(mcpServerConfig);
		mcpStdioClientProperties.setServersConfiguration(new ByteArrayResource(mcpServerConfigJSON.getBytes()));

		return chat(prompt);
	}

}

