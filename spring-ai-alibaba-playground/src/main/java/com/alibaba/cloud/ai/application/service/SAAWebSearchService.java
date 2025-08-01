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

import com.alibaba.cloud.ai.application.advisor.ReasoningContentAdvisor;
import com.alibaba.cloud.ai.application.modulerag.WebSearchRetriever;
import com.alibaba.cloud.ai.application.modulerag.core.IQSSearchEngine;
import com.alibaba.cloud.ai.application.modulerag.data.DataClean;
import com.alibaba.cloud.ai.application.modulerag.join.ConcatenationDocumentJoiner;
import com.alibaba.cloud.ai.application.modulerag.prompt.CustomContextQueryAugmenter;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.logging.Logger;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

@Service
public class SAAWebSearchService {

	private final DataClean dataCleaner;

	private final ChatClient chatClient;

	private final QueryExpander queryExpander;

	private final QueryTransformer queryTransformer;

	private final WebSearchRetriever webSearchRetriever;

	private final SimpleLoggerAdvisor simpleLoggerAdvisor;

	private final PromptTemplate queryArgumentPromptTemplate;

	private final ReasoningContentAdvisor reasoningContentAdvisor;

	// It works better here with DeepSeek-R1
	private static final String DEFAULT_WEB_SEARCH_MODEL = "deepseek-r1";

	private static final Logger log = Logger.getLogger(SAAWebSearchService.class.getName());

	public SAAWebSearchService(
			DataClean dataCleaner,
			QueryExpander queryExpander,
			IQSSearchEngine searchEngine,
			QueryTransformer queryTransformer,
			SimpleLoggerAdvisor simpleLoggerAdvisor,
			@Qualifier("dashscopeChatModel") ChatModel chatModel,
			@Qualifier("queryArgumentPromptTemplate") PromptTemplate queryArgumentPromptTemplate
	) {

		this.dataCleaner = dataCleaner;
		this.queryTransformer = queryTransformer;
		this.queryExpander = queryExpander;
		this.queryArgumentPromptTemplate = queryArgumentPromptTemplate;

		// reasoning content for DeepSeek-r1 is integrated into the output
		this.reasoningContentAdvisor = new ReasoningContentAdvisor(1);

		// Build chatClient
		this.chatClient = ChatClient.builder(chatModel)
				.defaultOptions(
						DashScopeChatOptions.builder()
								.withModel(DEFAULT_WEB_SEARCH_MODEL)
								// stream 模式下是否开启增量输出
								.withIncrementalOutput(true)
								.build()
				).build();

		this.simpleLoggerAdvisor = simpleLoggerAdvisor;

		this.webSearchRetriever = WebSearchRetriever.builder()
				.searchEngine(searchEngine)
				.dataCleaner(dataCleaner)
				.maxResults(2)
				.build();
	}


	public Flux<String> chat(String prompt) {

		Map<Integer, String> webLink = dataCleaner.getWebLink();

		return chatClient.prompt()
				.advisors(
						createRetrievalAugmentationAdvisor(),  // 这里面涉及联网查询和数据清洗
						reasoningContentAdvisor,
						simpleLoggerAdvisor
				).user(prompt)
				.stream()
				.content();
				// .transform(contentStream -> embedLinks(contentStream, webLink));
	}

	// todo 效果不好，这里只是一种思路
	// stream 中 [[ 可能是一个 chunk 输出，而 ]] 在另一个 stream 中。在遇到第一个 [[ 时，短暂阻塞，到 ]] 出现时，开始替换执行后续逻辑
	private Flux<String> embedLinks(Flux<String> contentStream, Map<Integer, String> webLink) {
		// State for managing incomplete tags
		StringBuilder buffer = new StringBuilder();

		return contentStream.flatMap(chunk -> {
			StringBuilder output = new StringBuilder(); // Output for this chunk
			int i = 0;

			while (i < chunk.length()) {
				char c = chunk.charAt(i);

				if (c == '[' && i + 1 < chunk.length() && chunk.charAt(i + 1) == '[') {
					// Start of [[...]]
					buffer.append("[[");
					i += 2; // Skip [[
				} else if (buffer.length() > 0 && c == ']' && i + 1 < chunk.length() && chunk.charAt(i + 1) == ']') {
					// End of [[...]]
					buffer.append("]]");
					String tag = buffer.toString(); // Complete tag
					output.append(resolveLink(tag, webLink)); // Resolve and append
					buffer.setLength(0); // Clear buffer
					i += 2; // Skip ]]
				} else if (buffer.length() > 0) {
					// Inside [[...]]
					buffer.append(c);
					i++;
				} else {
					// Normal text
					output.append(c);
					i++;
				}
			}

			// If buffer still contains data, leave it for the next chunk
			return Flux.just(output.toString());
		}).concatWith(Flux.defer(() -> {
			// If there's any leftover in the buffer, append it as-is
			if (buffer.length() > 0) {
				return Flux.just(buffer.toString());
			}
			return Flux.empty();
		}));
	}

	private String resolveLink(String tag, Map<Integer, String> webLink) {
		// Extract the number inside [[...]] and resolve the URL
		if (tag.startsWith("[[") && tag.endsWith("]]")) {
			String keyStr = tag.substring(2, tag.length() - 2); // Remove [[ and ]]
			try {
				int key = Integer.parseInt(keyStr);
				if (webLink.containsKey(key)) {
					return "[" + key + "](" + webLink.get(key) + ")";
				}
			} catch (NumberFormatException e) {
				// Not a valid number, return the original tag
			}
		}
		return tag; // Return original tag if no match
	}

	private RetrievalAugmentationAdvisor createRetrievalAugmentationAdvisor() {

		/*
		  QueryAugmenter 查询增强：使用附加的上下文数据信息增强用户 query，提供大模型回答问题时的必要上下文信息；
		  QueryTransformer 查询改写：因为用户的输入通常是片面的，关键信息较少，不便于大模型理解和回答问题。因此需要使用 prompt 调优手段或者大模型改写用户 query；
		  QueryExpander 查询扩展：将用户 query 扩展为多个语义不同的变体以获得不同视角，有助于检索额外的上下文信息并增加找到相关结果的机会。

		  DocumentRetriever：检索器，根据 QueryExpander 使用不同的数据源进行检索，例如 搜索引擎、向量存储、数据库或知识图等；
			DocumentJoiner：将从多个 query 和从多个数据源检索到的 Document 合并为一个 Document 集合；
		 */
		return RetrievalAugmentationAdvisor.builder()
				.documentRetriever(webSearchRetriever)// 2. Retrieval
				.queryTransformers(queryTransformer)// 1.pre-retrival
				.queryAugmenter(  // 1.pre-retrival
						new CustomContextQueryAugmenter(
								queryArgumentPromptTemplate,
								null,
								true)
				).queryExpander(queryExpander) // 1.pre-retrival
				.documentJoiner(new ConcatenationDocumentJoiner())  // 2. Retrieval
				.build();  // 3.post-Retrieval，这个好像被删了
	}

}
