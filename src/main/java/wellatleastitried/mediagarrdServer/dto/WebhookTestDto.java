package wellatleastitried.mediagarrdServer.dto;

public record WebhookTestDto(boolean delivered, Integer status, String error) {}
