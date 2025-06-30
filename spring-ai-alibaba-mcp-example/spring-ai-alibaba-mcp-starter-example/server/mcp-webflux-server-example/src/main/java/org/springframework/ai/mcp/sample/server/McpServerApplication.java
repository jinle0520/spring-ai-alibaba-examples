/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author brianxiadong
 */

package org.springframework.ai.mcp.sample.server;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
        test();
    }

    @Bean
    public ToolCallbackProvider weatherTools(OpenMeteoService openMeteoService) {
        return MethodToolCallbackProvider.builder().toolObjects(openMeteoService).build();
    }

    public record TextInput(String input) {
    }

    /**
     * 将输入字母转为大写的 MCP Tools
     */
    @Bean
    public ToolCallback toUpperCase() {
        return FunctionToolCallback.builder("toUpperCase", (TextInput input) -> input.input().toUpperCase())
                .inputType(TextInput.class)
                .description("Put the text to upper case")
                .build();
    }

    // 手动创建客户端，可以访问到我们定义的方法
    private static void test() {
        // SSE客户端代码示例
        var transport = new WebFluxSseClientTransport(
                // 配置WebClient基础URL
                WebClient.builder().baseUrl("http://localhost:8080")
        );

// 构建同步MCP客户端
        var client = McpClient.sync(transport).build();

// 初始化客户端连接
        client.initialize();

// 调用天气预报工具
        McpSchema.CallToolResult weatherResult = client.callTool(
//                new McpSchema.CallToolRequest("getWeatherForecastByLocation",
//                        Map.of("latitude", "39.9042", "longitude", "116.4074"))
                new McpSchema.CallToolRequest("printOne",
                        new HashMap<>())
        );

// 打印结果
        System.out.println(weatherResult.content());
    }

}
