package ai.spring.demo.ai.playground.client;

import ai.spring.demo.ai.playground.services.CustomerSupportAssistant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

	private final CustomerSupportAssistant agent;

	public AssistantController(CustomerSupportAssistant agent) {
		this.agent = agent;
	}

	@RequestMapping(path="/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> chat(@RequestParam(name = "chatId") String chatId,
							 @RequestParam(name = "userMessage") String userMessage) {
		return agent.chat(chatId, userMessage); // 和用户对话的接口
	}

}
