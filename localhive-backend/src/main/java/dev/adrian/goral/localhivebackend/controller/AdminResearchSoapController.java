package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.soap.AdminResearchSoapService;
import dev.adrian.goral.localhivebackend.soap.AdminResearchSoapService.SoapResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/research")
@RequiredArgsConstructor
public class AdminResearchSoapController {

    private static final MediaType SOAP_XML = MediaType.parseMediaType("application/soap+xml");

    private final AdminResearchSoapService soapService;

    @PostMapping(
            value = "/soap",
            consumes = {MediaType.TEXT_XML_VALUE, "application/soap+xml"},
            produces = "application/soap+xml"
    )
    public ResponseEntity<String> handleSoap(@RequestBody String requestBody) {
        SoapResponse response = soapService.handle(requestBody);
        return ResponseEntity
                .status(response.status())
                .contentType(SOAP_XML)
                .body(response.body());
    }
}
