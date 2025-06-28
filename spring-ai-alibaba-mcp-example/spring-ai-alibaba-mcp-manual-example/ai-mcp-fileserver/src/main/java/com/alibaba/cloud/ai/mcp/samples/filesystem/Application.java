package com.alibaba.cloud.ai.mcp.samples.filesystem;

import java.nio.file.Paths;
import java.time.Duration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	public CommandLineRunner predefinedQuestions(ChatClient.Builder chatClientBuilder,
			McpSyncClient mcpClient, ConfigurableApplicationContext context) {

		return args -> {
			// 这里的chatClient，内部是包括了mcpCLient的，就是有调用mcp函数的场景，都是通过chatClient中的mcpClient来调用的。
			var chatClient = chatClientBuilder
					.defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpClient))
					.build();

			System.out.println("Running predefined questions with AI model responses:\n");

			// Question 1
			// 相对路径会报没有权限的错
			String question1 = "Can you explain the content of the D:/ideaProject/spring-ai-alibaba-examples/spring-ai-alibaba-mcp-example/spring-ai-alibaba-mcp-manual-example/ai-mcp-fileserver/target/spring-ai-mcp-overview.txt file?";
			System.out.println("QUESTION: " + question1);
			System.out.println("ASSISTANT: " + chatClient.prompt(question1).call().content());

			// Question 2
			String question2 = "Pleses summarize the content of the D:/ideaProject/spring-ai-alibaba-examples/spring-ai-alibaba-mcp-example/spring-ai-alibaba-mcp-manual-example/ai-mcp-fileserver/target/spring-ai-mcp-overview.txt file and store it a new D:/ideaProject/spring-ai-alibaba-examples/spring-ai-alibaba-mcp-example/spring-ai-alibaba-mcp-manual-example/ai-mcp-fileserver/target/summary1.md as Markdown format?";
			System.out.println("\nQUESTION: " + question2);
			System.out.println("ASSISTANT: " +
					chatClient.prompt(question2).call().content());

			context.close();

		};
	}

	@Bean(destroyMethod = "close")
	public McpSyncClient mcpClient() {

		// based on
		// https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem
		// Windows 系统需要改为 npx.cmd
		// 这个参数是用npx来启动了一个mcp server，这个mcp server中有大概20个tool（McpSchema类中）
		var stdioParams = ServerParameters.builder("npx.cmd")
				.args("-y", "@modelcontextprotocol/server-filesystem", getDbPath())
				.build();
		// 这里注册的时候传入了mcp server的参数，后台怎么就启动了一个mcp server不知道，但是确实是起来了（tasklist | findstr nodek 看)
		var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams))
				.requestTimeout(Duration.ofSeconds(10)).build();

		var init = mcpClient.initialize();

		System.out.println("MCP Initialized: " + init);

		return mcpClient;

	}

	private static String getDbPath() {

		// spring-ai-alibaba-examples/spring-ai-alibaba-mcp-example/spring-ai-alibaba-manual-mcp-example/ai-mcp-fileserver/target
		// windows use: spring-ai-alibaba-mcp-example/spring-ai-alibaba-manual-mcp-example/ai-mcp-fileserver/target
		String path = Paths.get(System.getProperty("user.dir"), "spring-ai-alibaba-mcp-example/spring-ai-alibaba-mcp-manual-example/ai-mcp-fileserver/target").toString();
		System.out.println(path);

		return path;
	}

}
