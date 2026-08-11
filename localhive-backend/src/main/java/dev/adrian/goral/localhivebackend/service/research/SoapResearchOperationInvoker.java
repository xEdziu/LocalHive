package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.soap.AdminResearchSoapService;
import dev.adrian.goral.localhivebackend.soap.AdminResearchSoapService.SoapResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class SoapResearchOperationInvoker implements ResearchProtocolOperationInvoker {

    private final AdminResearchSoapService soapService;

    @Override
    public ResearchProtocol protocol() {
        return ResearchProtocol.SOAP;
    }

    @Override
    public ResearchProtocolInvocationResult invoke(ResearchOperation operation, UUID executionGroupId) {
        long startedAt = System.nanoTime();
        SoapResponse response = soapService.handle(requestBody(operation, executionGroupId));
        byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
        if (!HttpStatus.OK.equals(response.status())) {
            throw new IllegalStateException("SOAP operation returned HTTP " + response.status().value() + ".");
        }
        return new ResearchProtocolInvocationResult(
                protocol(),
                operation,
                elapsedMillis(startedAt),
                responseBytes.length
        );
    }

    private static String requestBody(ResearchOperation operation, UUID executionGroupId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:lh="https://localhive.dev/research/soap">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <lh:%s>
                      <lh:executionGroupId>%s</lh:executionGroupId>
                    </lh:%s>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(requestName(operation), executionGroupId, requestName(operation));
    }

    private static String requestName(ResearchOperation operation) {
        return switch (operation) {
            case GET_GROUP_DETAIL -> "GetGroupDetailRequest";
            case GET_GROUP_ACTIVITY -> "GetGroupActivityRequest";
            case GET_GROUP_ARTIFACTS -> "GetGroupArtifactsRequest";
            default -> throw new IllegalArgumentException("Unsupported SOAP protocol comparison operation.");
        };
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
